using Toybox.Activity;
using Toybox.Application;
using Toybox.Communications;
using Toybox.Lang;
using Toybox.Time;

typedef WebCallback as Method(responseCode as Lang.Number,
    data as Lang.Dictionary or Lang.String or Null) as Void;

//! Streams the run to a server that assembles and uploads the FIT centrally.
//!
//! On-watch recording could only express treadmill elevation through
//! developer fields with :nativeNum, which Garmin Connect and Strava ignore,
//! so the watch now ships raw samples and the server owns the FIT.
//!
//! Everything is buffered: samples accumulate into fixed-size chunks, at most
//! one request is in flight at a time, and failures back off and retry. If the
//! phone is out of reach when the run ends, the whole run is written to
//! Application.Storage and can be replayed from the menu on a later launch.
class Uploader {

    enum {
        STATE_IDLE,
        STATE_STARTING,
        STATE_STREAMING,
        STATE_FLUSHING,
        STATE_DONE,
        STATE_FAILED
    }

    const CHUNK = 120;          // samples per POST; ~2-3 KB of JSON
    const MAX_SAMPLES = 14400;  // 4 h at 1 Hz, after which sampling stops
    const MAX_BACKOFF = 60;     // seconds between retries, at the ceiling
    const MAX_ATTEMPTS = 8;     // consecutive failures while flushing = give up
    const REQUEST_TIMEOUT = 45; // seconds before a silent request is written off

    // lastCode values that did not come from the server.
    const CODE_THREW = -1;
    const CODE_TIMEOUT = -2;

    const KEY_INDEX = "pend_index";
    const DEFAULT_URL = "https://treadmill.156.155.98.24.sslip.io";
    const DEFAULT_TOKEN = "0709deaee949c66863e9352f296e1dbf";

    var state as Lang.Number = STATE_IDLE;
    var sessionId as Lang.String or Null = null;
    var chunksSent as Lang.Number = 0;
    var lastCode as Lang.Number = 0;

    // Set once the sample buffer hits MAX_SAMPLES; surfaced on the debug page.
    var overflow as Lang.Boolean = false;

    // From the finish response, for the "UPLOADED" line.
    var finishDistanceM as Lang.Float or Null = null;
    var finishAscentM as Lang.Float or Null = null;

    private var _samples as Lang.Array = [];   // the chunk still filling up
    private var _pending as Lang.Array = [];   // sealed chunks awaiting a POST
    private var _inFlight as Lang.Boolean = false;
    private var _inFlightAge as Lang.Number = 0;

    // Callbacks belonging to requests already written off as timed out, which
    // must be swallowed so a late reply cannot double-count a chunk.
    private var _stale as Lang.Number = 0;
    private var _finishSent as Lang.Boolean = false;
    private var _startEpoch as Lang.Number = 0;
    private var _startAlt as Lang.Float or Null = null;
    private var _seq as Lang.Number = 0;
    private var _wait as Lang.Number = 0;      // ticks left before the next try
    private var _backoff as Lang.Number = 1;
    private var _attempts as Lang.Number = 0;

    function initialize() {
    }

    //! Open a server session for a run starting now.
    function start() as Void {
        reset();
        _startEpoch = Time.now().value();

        var info = Activity.getActivityInfo();

        if (info != null && info.altitude != null) {
            _startAlt = info.altitude;
        }

        state = STATE_STARTING;
        pump();
    }

    //! Buffer one second of run data. Samples keep accumulating through a
    //! failed upload, so a dropped phone connection costs nothing.
    function addSample(speedMps as Lang.Float, inclinePct as Lang.Float, hr as Lang.Number) as Void {
        if (state == STATE_IDLE || state == STATE_DONE) {
            return;
        }

        if (samplesBuffered() >= MAX_SAMPLES) {
            overflow = true;
            return;
        }

        _samples.add([r2(speedMps), r2(inclinePct), hr]);

        if (_samples.size() >= CHUNK) {
            sealChunk();
        }

        pump();
    }

    //! One second of wall clock: retries are paced off this, not off a timer
    //! of its own.
    function tick() as Void {
        if (_wait > 0) {
            _wait -= 1;
        }

        // A request whose callback never arrives would otherwise wedge the
        // pipeline for good, leaving the run neither uploaded nor stored.
        if (_inFlight) {
            _inFlightAge += 1;

            if (_inFlightAge >= REQUEST_TIMEOUT) {
                _inFlight = false;
                _inFlightAge = 0;
                _finishSent = false;
                _stale += 1;
                lastCode = CODE_TIMEOUT;
                backoff();
            }
        }

        pump();
    }

    //! Flush whatever is buffered, then close the session server-side.
    function finish() as Void {
        if (state == STATE_IDLE || state == STATE_DONE) {
            return;
        }

        sealChunk();
        state = STATE_FLUSHING;
        _attempts = 0;
        _backoff = 1;
        _wait = 0;
        pump();
    }

    //! Drop the run entirely: no server finish, no stored leftovers.
    function discard() as Void {
        reset();
        clearStorage();
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

    //! Replay a stored run. If chunks already reached a server session that
    //! session is resumed, because a new one would orphan them - the server
    //! keys chunks by seq and recomputes totals at finish, so re-attaching is
    //! both safe and idempotent. Only when there is no usable session id (or
    //! the server has forgotten it) does a fresh one get created, carrying the
    //! original start time.
    function retry() as Lang.Boolean {
        var raw = Application.Storage.getValue(KEY_INDEX);

        if (!(raw instanceof Lang.Dictionary)) {
            return false;
        }

        var index = raw as Lang.Dictionary;
        var key = index["key"];
        var startEpoch = index["start"];
        var seqs = index["seqs"];

        if (key == null || !(startEpoch instanceof Lang.Number) || !(seqs instanceof Lang.Array)) {
            clearStorage();

            return false;
        }

        reset();
        _startEpoch = startEpoch;

        var sid = index["sid"];

        if (sid != null) {
            sessionId = sid.toString();
        }

        var alt = index["alt"];

        if (alt instanceof Lang.Float) {
            _startAlt = alt;
        }

        var list = seqs as Lang.Array;

        for (var i = 0; i < list.size(); i += 1) {
            var seq = list[i] as Lang.Number;
            var samples = Application.Storage.getValue(chunkKey(key.toString(), seq));

            if (samples instanceof Lang.Array) {
                var chunk = {} as Lang.Dictionary;
                chunk["seq"] = seq;
                chunk["s"] = samples;
                _pending.add(chunk);

                if (seq >= _seq) {
                    _seq = seq + 1;
                }
            }
        }

        state = STATE_FLUSHING;
        pump();

        return true;
    }

    function samplesBuffered() as Lang.Number {
        var total = _samples.size();

        for (var i = 0; i < _pending.size(); i += 1) {
            var chunk = _pending[i] as Lang.Dictionary;
            var s = chunk["s"] as Lang.Array;
            total += s.size();
        }

        return total;
    }

    function pendingChunks() as Lang.Number {
        return _pending.size();
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

    //! Drive the one-request-at-a-time pipeline: open the session, drain the
    //! chunk queue, then finish. Called after every event that could unblock it.
    private function pump() as Void {
        if (_inFlight || _wait > 0) {
            return;
        }

        if (state == STATE_IDLE || state == STATE_DONE || state == STATE_FAILED) {
            return;
        }

        if (sessionId == null) {
            postStart();

            return;
        }

        if (_pending.size() > 0) {
            postChunk();

            return;
        }

        if (state == STATE_FLUSHING && !_finishSent) {
            postFinish();
        }
    }

    private function postStart() as Void {
        var body = {} as Lang.Dictionary;
        body["start"] = _startEpoch;

        if (_startAlt != null) {
            body["alt"] = _startAlt;
        }

        send(baseUrl() + "/v1/session", body, method(:onStartResponse));
    }

    private function postChunk() as Void {
        var chunk = _pending[0] as Lang.Dictionary;
        var body = {} as Lang.Dictionary;
        body["seq"] = chunk["seq"];
        body["s"] = chunk["s"];

        send(baseUrl() + "/v1/session/" + sessionId + "/samples", body, method(:onChunkResponse));
    }

    private function postFinish() as Void {
        _finishSent = true;
        send(baseUrl() + "/v1/session/" + sessionId + "/finish", {} as Lang.Dictionary,
            method(:onFinishResponse));
    }

    function onStartResponse(responseCode as Lang.Number, data as Lang.Dictionary or Lang.String or Null) as Void {
        if (!accept()) {
            return;
        }

        lastCode = responseCode;

        if (responseCode == 200 && data instanceof Lang.Dictionary) {
            var id = (data as Lang.Dictionary)["id"];

            if (id != null) {
                sessionId = id.toString();
                succeeded();

                if (state == STATE_STARTING) {
                    state = STATE_STREAMING;
                }

                pump();

                return;
            }
        }

        backoff();
    }

    function onChunkResponse(responseCode as Lang.Number, data as Lang.Dictionary or Lang.String or Null) as Void {
        if (!accept()) {
            return;
        }

        lastCode = responseCode;

        if (responseCode == 200) {
            if (_pending.size() > 0) {
                _pending = _pending.slice(1, null);
            }

            chunksSent += 1;
            succeeded();
            pump();

            return;
        }

        if (responseCode == 404) {
            sessionGone();

            return;
        }

        backoff();
    }

    function onFinishResponse(responseCode as Lang.Number, data as Lang.Dictionary or Lang.String or Null) as Void {
        if (!accept()) {
            return;
        }

        lastCode = responseCode;

        if (responseCode == 200) {
            if (data instanceof Lang.Dictionary) {
                var d = data as Lang.Dictionary;
                finishDistanceM = floatFrom(d["distance"]);
                finishAscentM = floatFrom(d["ascent"]);
            }

            state = STATE_DONE;
            clearStorage();

            return;
        }

        _finishSent = false;

        if (responseCode == 404) {
            sessionGone();

            return;
        }

        backoff();
    }

    //! The server has no record of this session - it was resumed against a id
    //! that has since been cleaned up. Drop it so pump() opens a fresh one and
    //! replays whatever is still buffered.
    private function sessionGone() as Void {
        sessionId = null;
        _finishSent = false;
        backoff();
    }

    //! Claim a callback for the request currently in flight, or swallow it as
    //! the late reply to one already written off.
    private function accept() as Lang.Boolean {
        if (_stale > 0) {
            _stale -= 1;

            return false;
        }

        _inFlight = false;
        _inFlightAge = 0;

        return true;
    }

    private function succeeded() as Void {
        _attempts = 0;
        _backoff = 1;
        _wait = 0;
    }

    //! Exponential backoff. Giving up is only an option once the run is over:
    //! mid-run the phone is often simply out of range and everything is still
    //! buffered, so streaming retries indefinitely.
    private function backoff() as Void {
        _attempts += 1;
        _backoff *= 2;

        if (_backoff > MAX_BACKOFF) {
            _backoff = MAX_BACKOFF;
        }

        _wait = _backoff;

        if (state == STATE_FLUSHING && _attempts >= MAX_ATTEMPTS) {
            persist();
            state = STATE_FAILED;
        }
    }

    private function sealChunk() as Void {
        if (_samples.size() == 0) {
            return;
        }

        var chunk = {} as Lang.Dictionary;
        chunk["seq"] = _seq;
        chunk["s"] = _samples;
        _pending.add(chunk);
        _seq += 1;
        _samples = [];
    }

    //! Storage values have to stay small, so each chunk is its own key and the
    //! index carries only the seq numbers.
    private function persist() as Void {
        sealChunk();

        // A run that never buffered or sent a sample is not worth keeping, and
        // storing it would leave the retry prompt up for nothing.
        if (chunksSent == 0 && _pending.size() == 0) {
            clearStorage();

            return;
        }

        var key = _startEpoch.toString();
        var seqs = [] as Lang.Array;

        try {
            for (var i = 0; i < _pending.size(); i += 1) {
                var chunk = _pending[i] as Lang.Dictionary;
                var seq = chunk["seq"] as Lang.Number;

                Application.Storage.setValue(chunkKey(key, seq), chunk["s"]);
                seqs.add(seq);
            }

            var index = {} as Lang.Dictionary;
            index["key"] = key;
            index["start"] = _startEpoch;
            index["seqs"] = seqs;

            if (sessionId != null) {
                index["sid"] = sessionId;
            }

            if (_startAlt != null) {
                index["alt"] = _startAlt;
            }

            Application.Storage.setValue(KEY_INDEX, index);
        } catch (e) {
            // Storage full or value too large: the run is lost either way,
            // and throwing out of a save would be worse.
        }
    }

    private function clearStorage() as Void {
        var raw = Application.Storage.getValue(KEY_INDEX);

        if (raw instanceof Lang.Dictionary) {
            var index = raw as Lang.Dictionary;
            var key = index["key"];
            var seqs = index["seqs"];

            if (key != null && seqs instanceof Lang.Array) {
                var list = seqs as Lang.Array;

                for (var i = 0; i < list.size(); i += 1) {
                    Application.Storage.deleteValue(chunkKey(key.toString(), list[i] as Lang.Number));
                }
            }
        }

        Application.Storage.deleteValue(KEY_INDEX);
    }

    private function reset() as Void {
        // Anything still in flight belongs to the run being thrown away, so
        // its callback must not be allowed to touch the new one.
        var carry = _stale + (_inFlight ? 1 : 0);

        state = STATE_IDLE;
        sessionId = null;
        chunksSent = 0;
        lastCode = 0;
        overflow = false;
        finishDistanceM = null;
        finishAscentM = null;
        _samples = [];
        _pending = [];
        _inFlight = false;
        _inFlightAge = 0;
        _stale = carry;
        _finishSent = false;
        _startEpoch = 0;
        _startAlt = null;
        _seq = 0;
        _wait = 0;
        _backoff = 1;
        _attempts = 0;
    }

    private function send(
        url as Lang.String,
        body as Lang.Dictionary,
        callback as WebCallback
    ) as Void {
        var options = {
            :method => Communications.HTTP_REQUEST_METHOD_POST,
            :headers => {
                "X-Token" => token(),
                "Content-Type" => Communications.REQUEST_CONTENT_TYPE_JSON
            },
            :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON
        };

        _inFlight = true;
        _inFlightAge = 0;

        try {
            Communications.makeWebRequest(url, body, options, callback);
        } catch (e) {
            _inFlight = false;
            lastCode = CODE_THREW;
            backoff();
        }
    }

    private function chunkKey(sessionKey as Lang.String, seq as Lang.Number) as Lang.String {
        return "pend_" + sessionKey + "_" + seq.toString();
    }

    private function baseUrl() as Lang.String {
        var v = property("serverUrl", DEFAULT_URL);

        // A trailing slash would produce "//v1/session".
        if (v.length() > 0 && v.substring(v.length() - 1, v.length()).equals("/")) {
            return v.substring(0, v.length() - 1);
        }

        return v;
    }

    private function token() as Lang.String {
        return property("serverToken", DEFAULT_TOKEN);
    }

    private function property(key as Lang.String, fallback as Lang.String) as Lang.String {
        var v = null;

        try {
            v = Application.Properties.getValue(key);
        } catch (e) {
            v = null;
        }

        if (v == null || !(v instanceof Lang.String) || (v as Lang.String).length() == 0) {
            return fallback;
        }

        return v as Lang.String;
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

    //! Two decimals is all the payload needs, and it keeps the JSON short.
    private function r2(v as Lang.Float) as Lang.Float {
        var scaled = v * 100.0;
        var n = (scaled < 0) ? (scaled - 0.5).toNumber() : (scaled + 0.5).toNumber();

        return n / 100.0;
    }
}
