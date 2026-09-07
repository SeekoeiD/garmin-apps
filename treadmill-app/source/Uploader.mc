using Toybox.Activity;
using Toybox.Application;
using Toybox.Communications;
using Toybox.Lang;
using Toybox.Time;

typedef PhoneCallback as Method(msg as Communications.PhoneAppMessage) as Void;

//! Reports the outcome of one Communications.transmit back to the uploader.
//!
//! The generation number is what makes a late callback harmless: anything that
//! resets or abandons the transmit sequence bumps the uploader's generation, so
//! a reply belonging to a run that has been thrown away is swallowed instead of
//! advancing the part counter of the run that replaced it.
class PartListener extends Communications.ConnectionListener {

    private var _uploader as Uploader;
    private var _gen as Lang.Number;

    function initialize(uploader as Uploader, gen as Lang.Number) {
        Communications.ConnectionListener.initialize();
        _uploader = uploader;
        _gen = gen;
    }

    function onComplete() as Void {
        _uploader.onPartSent(_gen);
    }

    function onError() as Void {
        _uploader.onPartFailed(_gen);
    }
}

//! Records the run compactly on the watch, then hands it to the Android
//! companion over the Connect IQ phone channel, which builds the FIT and
//! uploads it to Garmin. There is no HTTP anywhere: the watch talks only to the
//! paired phone.
//!
//! On-watch FIT recording could only express treadmill elevation through
//! developer fields with :nativeNum, which Garmin Connect and Strava ignore, so
//! the watch ships the run and the phone owns the FIT.
//!
//! Recording is part change-point encoded - incline is stored only when it
//! changes - and part per-second: heart rate and speed both get one small
//! Number every second, so a 90 minute run is a few KB and survives being
//! parked in Application.Storage when the phone is out of reach. Speed is also
//! kept as change-points, which the phone falls back to when a run predates the
//! per-second series.
class Uploader {

    enum {
        STATE_IDLE,
        STATE_STARTING,
        STATE_STREAMING,
        STATE_FLUSHING,
        STATE_DONE,
        STATE_FAILED
    }

    // Wire protocol. The phone app is built against these exact strings.
    const MSG_TYPE = "tl_run";
    const RESULT_TYPE = "tl_result";

    const HR_CHUNK = 500;       // values per Storage value, per series (~2.5 KB)
    // Seconds per wire part. A 1500-value part (~7 KB) never reached the phone
    // on a real 44-minute run while the ~300-byte head part did, so parts stay
    // under about 1 KB. Each part now carries two values per second - heart
    // rate and speed - so the second count is half what the HR-only build used.
    const PART = 60;
    const MAX_DUR = 14400;      // 4 h at 1 Hz, after which sampling stops
    const MAX_BACKOFF = 60;     // seconds between retries, at the ceiling
    const MAX_ATTEMPTS = 8;     // consecutive transmit failures = give up
    const RESULT_TIMEOUT = 90;  // seconds to wait for the phone's verdict

    //! lastCode, shown on the debug page:
    //!   0 nothing has happened yet
    //!   1 every part delivered, waiting for the phone's verdict
    //!   2 the phone confirmed the upload
    //!  -1 Communications.transmit threw
    //!  -2 transmit reported onError
    //!  -3 no tl_result within RESULT_TIMEOUT
    //!  -4 the phone answered ok=false; see lastError
    const CODE_NONE = 0;
    const CODE_WAITING = 1;
    const CODE_OK = 2;
    const CODE_THREW = -1;
    const CODE_TX_FAILED = -2;
    const CODE_TIMEOUT = -3;
    const CODE_REJECTED = -4;

    const KEY_INDEX = "pend_index";

    var state as Lang.Number = STATE_IDLE;

    // Parts the phone has acknowledged receipt of, out of the sealed part list.
    var chunksSent as Lang.Number = 0;
    var lastCode as Lang.Number = CODE_NONE;
    var lastError as Lang.String = "";

    // Set once the run hits MAX_DUR; surfaced on the debug page.
    var overflow as Lang.Boolean = false;

    // From the phone's tl_result, for the "UPLOADED" line.
    var finishDistanceM as Lang.Float or Null = null;
    var finishAscentM as Lang.Float or Null = null;

    // The run's identity key: the start epoch, which the phone dedupes on.
    private var _k as Lang.Number = 0;
    private var _alt as Lang.Float or Null = null;
    private var _dur as Lang.Number = 0;

    // Change points as [sec, value] pairs, and one HR and one speed reading per
    // second held in chunks of at most HR_CHUNK so no single Storage value gets
    // large. The two per-second series always advance together.
    private var _sp as Lang.Array = [];
    private var _inc as Lang.Array = [];
    private var _hrChunks as Lang.Array = [];
    private var _vChunks as Lang.Array = [];

    private var _parts as Lang.Array = [];
    private var _txActive as Lang.Boolean = false;
    private var _gen as Lang.Number = 0;

    private var _awaitResult as Lang.Boolean = false;
    private var _resultAge as Lang.Number = 0;
    private var _wait as Lang.Number = 0;      // ticks left before the next try
    private var _backoff as Lang.Number = 1;
    private var _attempts as Lang.Number = 0;

    function initialize() {
    }

    //! Begin a run starting now. The start epoch doubles as the run's key.
    function start() as Void {
        reset();
        _k = Time.now().value();

        var info = Activity.getActivityInfo();

        if (info != null && info.altitude != null) {
            _alt = info.altitude;
        }

        state = STATE_STARTING;
    }

    //! Record one second of run data. Incline only costs anything when it
    //! changes; heart rate and speed are one small Number each per second.
    function addSample(speedMps as Lang.Float, inclinePct as Lang.Float, hr as Lang.Number) as Void {
        if (state != STATE_STARTING && state != STATE_STREAMING) {
            return;
        }

        state = STATE_STREAMING;

        if (_dur >= MAX_DUR) {
            overflow = true;

            return;
        }

        var sec = _dur;
        var v100 = scaled(speedMps, 100.0);

        appendChange(_sp, sec, v100);
        appendChange(_inc, sec, scaled(inclinePct, 10.0));
        appendSample(_hrChunks, clamped(hr, 0, 255));
        appendSample(_vChunks, clamped(v100, 0, 65535));

        _dur += 1;
    }

    //! One second of wall clock: retry pacing and the result-wait timeout are
    //! driven from here rather than from a timer of their own.
    function tick() as Void {
        if (_wait > 0) {
            _wait -= 1;
        }

        // Every part landed but the phone never answered. Without this the run
        // would sit in FLUSHING for ever, neither uploaded nor stored.
        if (_awaitResult) {
            _resultAge += 1;

            if (_resultAge >= RESULT_TIMEOUT) {
                lastCode = CODE_TIMEOUT;
                lastError = "no reply";
                fail();

                return;
            }
        }

        pump();
    }

    //! Seal the run and start shipping it to the phone.
    function finish() as Void {
        if (state != STATE_STARTING && state != STATE_STREAMING) {
            return;
        }

        _parts = buildParts();
        beginTransmit();
    }

    //! Drop the run entirely: nothing sent, no stored leftovers.
    //! Drop the current run. Storage is only cleared when it holds this same
    //! run, so discarding a fresh or empty run leaves an earlier pending run
    //! intact for its retry.
    function discard() as Void {
        var raw = Application.Storage.getValue(KEY_INDEX);
        var stored = (raw instanceof Lang.Dictionary) ? (raw as Lang.Dictionary)["k"] : null;

        if (stored instanceof Lang.Number && (stored as Lang.Number) == _k) {
            clearStorage();
        }

        reset();
    }

    //! Called when the app is closing mid-run: park everything in storage so
    //! the run can be replayed rather than lost.
    function persistIfUnfinished() as Void {
        if (state != STATE_STARTING && state != STATE_STREAMING && state != STATE_FLUSHING) {
            return;
        }

        persist();
        state = STATE_FAILED;
    }

    function hasPending() as Lang.Boolean {
        return Application.Storage.getValue(KEY_INDEX) != null;
    }

    //! Replay a stored run, keeping its original key. The phone dedupes on that
    //! key, so resending every part - including any the phone already has - is
    //! safe and is simpler than tracking which ones landed.
    function retry() as Lang.Boolean {
        if (!loadPending()) {
            return false;
        }

        _parts = buildParts();
        beginTransmit();

        return true;
    }

    //! Reload a stored run into memory without sending it. Split out of retry()
    //! so the tests can check the storage round trip without opening a
    //! transmit that would outlive them.
    function loadPending() as Lang.Boolean {
        var raw = Application.Storage.getValue(KEY_INDEX);

        if (!(raw instanceof Lang.Dictionary)) {
            return false;
        }

        var index = raw as Lang.Dictionary;
        var k = index["k"];
        var dur = index["dur"];
        var sp = index["sp"];
        var inc = index["inc"];
        var count = index["hrChunks"];

        if (!(k instanceof Lang.Number) || !(dur instanceof Lang.Number)
            || !(sp instanceof Lang.Array) || !(inc instanceof Lang.Array)
            || !(count instanceof Lang.Number)) {
            clearStorage();

            return false;
        }

        var key = k as Lang.Number;
        var chunks = count as Lang.Number;
        var alt = index["alt"];

        reset();
        _k = key;
        _dur = dur as Lang.Number;
        _sp = sp as Lang.Array;
        _inc = inc as Lang.Array;

        if (alt instanceof Lang.Float) {
            _alt = alt as Lang.Float;
        }

        // Both series were written chunk-for-chunk, so one count covers both.
        // A missing or short speed chunk - a run parked by an older build, or a
        // storage write that only half succeeded - drops the speed series
        // entirely rather than shipping one that does not line up with the HR
        // seconds; the phone then falls back to the "sp" change points.
        var speedOk = true;

        for (var j = 0; j < chunks; j += 1) {
            var hr = Application.Storage.getValue(hrKey(key, j));

            if (hr instanceof Lang.Array) {
                _hrChunks.add(hr);
            }

            var v = Application.Storage.getValue(vKey(key, j));

            if (v instanceof Lang.Array) {
                _vChunks.add(v);
            } else {
                speedOk = false;
            }
        }

        if (!speedOk || seriesSize(_vChunks) != seriesSize(_hrChunks)) {
            _vChunks = [];
        }

        return true;
    }

    //! Seconds recorded, which is also the length of the HR series.
    function samplesBuffered() as Lang.Number {
        return _dur;
    }

    //! The run's identity key, 0 when there is no run. On the debug page.
    function runKey() as Lang.Number {
        return _k;
    }

    //! Parts the phone has not acknowledged yet.
    function pendingChunks() as Lang.Number {
        var left = _parts.size() - chunksSent;

        return (left > 0) ? left : 0;
    }

    //! True when nothing is outstanding, so the app may exit.
    function isSettled() as Lang.Boolean {
        return state == STATE_IDLE || state == STATE_DONE || state == STATE_FAILED;
    }

    function stateLabel() as Lang.String {
        if (state == STATE_STARTING) { return "STARTING"; }

        if (state == STATE_STREAMING) { return "STREAMING"; }

        if (state == STATE_FLUSHING) { return "FLUSHING"; }

        if (state == STATE_DONE) { return "DONE"; }

        if (state == STATE_FAILED) { return "FAILED"; }

        return "IDLE";
    }

    //! The run as it goes on the wire: a head part carrying the change lists,
    //! then sample parts of at most PART seconds each, re-sliced from however
    //! the Storage chunks happen to be sized so a replay of an older, larger
    //! chunk still goes out in small messages. Each sample part carries "hr"
    //! and, whenever the per-second speed series is intact, an equally long
    //! "v". Public so the tests can inspect the exact payload the phone is
    //! built against.
    function buildParts() as Lang.Array {
        var hrParts = sliceSeries(_hrChunks);
        var vParts = (seriesSize(_vChunks) == seriesSize(_hrChunks))
            ? sliceSeries(_vChunks)
            : ([] as Lang.Array);
        var n = 1 + hrParts.size();
        var parts = [] as Lang.Array;

        var head = {} as Lang.Dictionary;
        head["t"] = MSG_TYPE;
        head["k"] = _k;
        head["i"] = 0;
        head["n"] = n;
        head["start"] = _k;

        if (_alt != null) {
            head["alt"] = _alt;
        }

        head["dur"] = _dur;
        head["sp"] = _sp;
        head["inc"] = _inc;
        parts.add(head);

        for (var j = 0; j < hrParts.size(); j += 1) {
            var hr = hrParts[j] as Lang.Array;
            var part = {} as Lang.Dictionary;
            part["t"] = MSG_TYPE;
            part["k"] = _k;
            part["i"] = j + 1;
            part["n"] = n;
            part["hr"] = hr;

            if (j < vParts.size()) {
                var v = vParts[j] as Lang.Array;

                if (v.size() == hr.size()) {
                    part["v"] = v;
                }
            }

            parts.add(part);
        }

        return parts;
    }

    //! Every recorded value of one series, in order, cut into arrays of at most
    //! PART. Both series are cut the same way, so part i of one lines up second
    //! for second with part i of the other.
    private function sliceSeries(chunks as Lang.Array) as Lang.Array {
        var out = [] as Lang.Array;
        var current = [] as Lang.Array;

        for (var j = 0; j < chunks.size(); j += 1) {
            var chunk = chunks[j] as Lang.Array;

            for (var i = 0; i < chunk.size(); i += 1) {
                current.add(chunk[i]);

                if (current.size() >= PART) {
                    out.add(current);
                    current = [] as Lang.Array;
                }
            }
        }

        if (current.size() > 0) {
            out.add(current);
        }

        return out;
    }

    //! Total values held across a series' chunks.
    private function seriesSize(chunks as Lang.Array) as Lang.Number {
        var total = 0;

        for (var j = 0; j < chunks.size(); j += 1) {
            total += (chunks[j] as Lang.Array).size();
        }

        return total;
    }

    //! The phone's verdict on a run. Everything here is untrusted input: any
    //! key may be absent, null or the wrong type, and none of that may throw.
    //! Public so the app-level phone message callback can forward to it.
    function onPhoneMessage(msg as Communications.PhoneAppMessage) as Void {
        var data = msg.data;

        if (!(data instanceof Lang.Dictionary)) {
            return;
        }

        var dict = data as Lang.Dictionary;
        var type = dict["t"];

        if (!(type instanceof Lang.String) || !(type as Lang.String).equals(RESULT_TYPE)) {
            return;
        }

        // A verdict is only interesting for the run currently being sent, or
        // for one that has just been written off - a late success still means
        // the run reached Garmin, and clearing storage saves a pointless retry.
        if (_k == 0 || (state != STATE_FLUSHING && state != STATE_FAILED)) {
            return;
        }

        var key = dict["k"];

        if (!(key instanceof Lang.Number) || (key as Lang.Number) != _k) {
            return;
        }

        var ok = dict["ok"];

        if (ok instanceof Lang.Boolean && (ok as Lang.Boolean)) {
            finishDistanceM = floatFrom(dict["dist"]);
            finishAscentM = floatFrom(dict["asc"]);
            lastCode = CODE_OK;
            lastError = "";
            settle();
            state = STATE_DONE;
            clearStorage();

            return;
        }

        lastCode = CODE_REJECTED;
        lastError = textFrom(dict["err"]);
        fail();
    }

    //! Transmit callback. Public because the listener is a separate class.
    function onPartSent(gen as Lang.Number) as Void {
        if (!claim(gen)) {
            return;
        }

        chunksSent += 1;
        succeeded();

        if (chunksSent >= _parts.size()) {
            lastCode = CODE_WAITING;
            _awaitResult = true;
            _resultAge = 0;

            return;
        }

        pump();
    }

    //! Transmit callback. Public because the listener is a separate class.
    function onPartFailed(gen as Lang.Number) as Void {
        if (!claim(gen)) {
            return;
        }

        lastCode = CODE_TX_FAILED;
        backoff();
    }

    //! Send the next undelivered part. Strictly one at a time: the phone
    //! assembles parts in order and a burst would race its own acknowledgements.
    private function pump() as Void {
        if (_txActive || _wait > 0 || _awaitResult || state != STATE_FLUSHING) {
            return;
        }

        if (chunksSent >= _parts.size()) {
            return;
        }

        _gen += 1;
        _txActive = true;

        try {
            Communications.transmit(_parts[chunksSent], null, new PartListener(self, _gen));
        } catch (e) {
            _txActive = false;
            lastCode = CODE_THREW;
            backoff();
        }
    }

    private function beginTransmit() as Void {
        state = STATE_FLUSHING;
        chunksSent = 0;
        lastCode = CODE_NONE;
        lastError = "";
        finishDistanceM = null;
        finishAscentM = null;
        _awaitResult = false;
        _resultAge = 0;
        succeeded();
        pump();
    }

    //! Claim a callback for the transmit currently in flight, or swallow it as
    //! the late reply to one already abandoned.
    private function claim(gen as Lang.Number) as Lang.Boolean {
        if (!_txActive || gen != _gen) {
            return false;
        }

        _txActive = false;

        return true;
    }

    private function succeeded() as Void {
        _attempts = 0;
        _backoff = 1;
        _wait = 0;
    }

    //! Exponential backoff, 1 2 4 ... 60 s. The sequence only ends after
    //! MAX_ATTEMPTS consecutive failures, at which point the run goes to
    //! storage and the menu's retry is the way back in.
    private function backoff() as Void {
        _attempts += 1;
        _wait = _backoff;
        _backoff *= 2;

        if (_backoff > MAX_BACKOFF) {
            _backoff = MAX_BACKOFF;
        }

        if (_attempts >= MAX_ATTEMPTS) {
            fail();
        }
    }

    //! Park the run in storage and stop trying.
    private function fail() as Void {
        persist();
        settle();
        state = STATE_FAILED;
    }

    //! Stop expecting anything from the transmit currently outstanding.
    private function settle() as Void {
        _gen += 1;
        _txActive = false;
        _awaitResult = false;
        _resultAge = 0;
        _wait = 0;
    }

    private function appendChange(list as Lang.Array, sec as Lang.Number, value as Lang.Number) as Void {
        if (list.size() > 0) {
            var last = list[list.size() - 1] as Lang.Array;

            if ((last[1] as Lang.Number) == value) {
                return;
            }
        }

        list.add([sec, value]);
    }

    //! One reading per second, 0 for unknown, kept in bounded chunks that map
    //! straight onto individual Storage values. Every per-second series is
    //! chunked identically, so one chunk count in the index covers them all.
    private function appendSample(chunks as Lang.Array, value as Lang.Number) as Void {
        var chunk = null;

        if (chunks.size() > 0) {
            chunk = chunks[chunks.size() - 1] as Lang.Array;
        }

        if (chunk == null || chunk.size() >= HR_CHUNK) {
            chunk = [] as Lang.Array;
            chunks.add(chunk);
        }

        chunk.add(value);
    }

    private function clamped(v as Lang.Number, low as Lang.Number, high as Lang.Number) as Lang.Number {
        if (v < low) {
            return low;
        }

        if (v > high) {
            return high;
        }

        return v;
    }

    //! Storage values have to stay small, so each chunk of each per-second
    //! series is its own key and the index carries only how many there are.
    //! One count is enough: the series are chunked in lockstep.
    private function persist() as Void {
        // A run with nothing in it is not worth keeping. It must not touch
        // storage either: an empty run started by accident would otherwise
        // wipe an earlier run still waiting for its retry.
        if (_dur == 0) {
            return;
        }

        try {
            for (var j = 0; j < _hrChunks.size(); j += 1) {
                Application.Storage.setValue(hrKey(_k, j), _hrChunks[j]);
            }

            for (var j = 0; j < _vChunks.size(); j += 1) {
                Application.Storage.setValue(vKey(_k, j), _vChunks[j]);
            }

            var index = {} as Lang.Dictionary;
            index["k"] = _k;
            index["dur"] = _dur;

            if (_alt != null) {
                index["alt"] = _alt;
            }

            index["sp"] = _sp;
            index["inc"] = _inc;
            index["hrChunks"] = _hrChunks.size();

            Application.Storage.setValue(KEY_INDEX, index);
        } catch (e) {
            // Storage full or a value too large: the run is lost either way,
            // and throwing out of a save would be worse.
        }
    }

    private function clearStorage() as Void {
        var raw = Application.Storage.getValue(KEY_INDEX);

        if (raw instanceof Lang.Dictionary) {
            var index = raw as Lang.Dictionary;
            var k = index["k"];
            var count = index["hrChunks"];

            if (k instanceof Lang.Number && count instanceof Lang.Number) {
                var key = k as Lang.Number;
                var chunks = count as Lang.Number;

                for (var j = 0; j < chunks; j += 1) {
                    Application.Storage.deleteValue(hrKey(key, j));
                    Application.Storage.deleteValue(vKey(key, j));
                }
            }

            clearLegacy(index);
        }

        Application.Storage.deleteValue(KEY_INDEX);
    }

    //! A watch upgraded from the streaming build can still hold a pending run
    //! in the old shape, whose sample chunks would otherwise be orphaned in
    //! storage for good.
    private function clearLegacy(index as Lang.Dictionary) as Void {
        var key = index["key"];
        var seqs = index["seqs"];

        if (key == null || !(seqs instanceof Lang.Array)) {
            return;
        }

        var list = seqs as Lang.Array;

        for (var i = 0; i < list.size(); i += 1) {
            Application.Storage.deleteValue("pend_" + key.toString() + "_" + list[i].toString());
        }
    }

    private function reset() as Void {
        settle();
        state = STATE_IDLE;
        chunksSent = 0;
        lastCode = CODE_NONE;
        lastError = "";
        overflow = false;
        finishDistanceM = null;
        finishAscentM = null;
        _k = 0;
        _alt = null;
        _dur = 0;
        _sp = [];
        _inc = [];
        _hrChunks = [];
        _vChunks = [];
        _parts = [];
        _backoff = 1;
        _attempts = 0;
    }

    private function hrKey(k as Lang.Number, j as Lang.Number) as Lang.String {
        return "pend_hr_" + k.toString() + "_" + j.toString();
    }

    private function vKey(k as Lang.Number, j as Lang.Number) as Lang.String {
        return "pend_v_" + k.toString() + "_" + j.toString();
    }

    private function floatFrom(v as Lang.Object or Null) as Lang.Float or Null {
        if (v instanceof Lang.Number) {
            return (v as Lang.Number).toFloat();
        }

        if (v instanceof Lang.Float) {
            return v as Lang.Float;
        }

        if (v instanceof Lang.Double) {
            return (v as Lang.Double).toFloat();
        }

        return null;
    }

    private function textFrom(v as Lang.Object or Null) as Lang.String {
        if (v == null) {
            return "?";
        }

        return v.toString();
    }

    //! Round to a fixed-point integer, which is all the wire carries.
    private function scaled(v as Lang.Float, factor as Lang.Float) as Lang.Number {
        var x = v * factor;

        return (x < 0) ? (x - 0.5).toNumber() : (x + 0.5).toNumber();
    }
}
