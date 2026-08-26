using Toybox.Activity;
using Toybox.ActivityRecording;
using Toybox.FitContributor;
using Toybox.Lang;
using Toybox.Math;

//! Owns the FIT recording session and derives elevation from treadmill
//! speed and incline.
//!
//! The interesting part is the :nativeNum option on Session.createField().
//! It is the only mechanism Connect IQ offers for writing a value that a
//! consumer should treat as a native FIT field rather than a plain developer
//! field; a data field cannot do this at all, which is why this app owns the
//! recording session instead of being a data field. Whether Garmin Connect
//! and Strava honour the mapping for summary totals is the open question this
//! app exists to answer - see NATIVE_* below to flip individual mappings off.
class Recorder {

    // FIT native field numbers (FIT Profile, Rev 21.x).
    const REC_DISTANCE          = 5;   // record.distance, m
    const REC_GRADE             = 9;   // record.grade, %
    const REC_ENHANCED_SPEED    = 73;  // record.enhanced_speed, m/s
    const REC_ENHANCED_ALTITUDE = 78;  // record.enhanced_altitude, m
    const LAP_TOTAL_ASCENT      = 21;  // lap.total_ascent, m
    const SES_TOTAL_ASCENT      = 22;  // session.total_ascent, m
    const SES_TOTAL_DESCENT     = 23;  // session.total_descent, m

    var recording as Lang.Boolean = false;
    var elapsedSec as Lang.Number = 0;

    var distanceM as Lang.Float = 0.0;
    var altitudeM as Lang.Float = 0.0;
    var ascentM as Lang.Float = 0.0;
    var descentM as Lang.Float = 0.0;
    var lapAscentM as Lang.Float = 0.0;

    // Set when a createField() call with :nativeNum was rejected and had to be
    // retried without the mapping. Surfaced on the debug page.
    var nativeRejected as Lang.String = "";

    private var _session as ActivityRecording.Session or Null = null;
    private var _fAltitude as FitContributor.Field or Null = null;
    private var _fSpeed as FitContributor.Field or Null = null;
    private var _fDistance as FitContributor.Field or Null = null;
    private var _fGrade as FitContributor.Field or Null = null;
    private var _fIncline as FitContributor.Field or Null = null;
    private var _fSesAscent as FitContributor.Field or Null = null;
    private var _fSesDescent as FitContributor.Field or Null = null;
    private var _fSesAscentPlain as FitContributor.Field or Null = null;
    private var _fLapAscent as FitContributor.Field or Null = null;

    function initialize() {
    }

    function isRecording() as Lang.Boolean {
        return recording;
    }

    function hasSession() as Lang.Boolean {
        return _session != null;
    }

    //! Create the session and its fields, then start the timer.
    function start() as Void {
        if (_session == null) {
            _session = ActivityRecording.createSession({
                :name => "Treadmill",
                :sport => Activity.SPORT_RUNNING,
                :subSport => Activity.SUB_SPORT_TREADMILL
            });

            createFields();
            seedAltitude();
        }

        _session.start();
        recording = true;
    }

    function stop() as Void {
        if (_session != null) {
            _session.stop();
        }

        recording = false;
    }

    function save() as Void {
        if (_session == null) {
            return;
        }

        writeSummaryFields();
        _session.stop();
        _session.save();
        _session = null;
        recording = false;
    }

    function discard() as Void {
        if (_session == null) {
            return;
        }

        _session.stop();
        _session.discard();
        _session = null;
        recording = false;
    }

    function addLap() as Void {
        if (_session == null) {
            return;
        }

        if (_fLapAscent != null) {
            _fLapAscent.setData(round(lapAscentM));
        }

        _session.addLap();
        lapAscentM = 0.0;
    }

    //! Advance the derived state by dt seconds using the latest treadmill
    //! values, then push them into the FIT record fields.
    //!
    //! Belt speed is the along-the-incline speed, so the vertical component is
    //! v * sin(atan(grade)) and the horizontal component is v * cos(atan(grade)).
    //! Distance is integrated along the belt, matching what the treadmill
    //! console shows.
    function update(client as FtmsClient, dt as Lang.Float) as Void {
        if (!recording) {
            return;
        }

        elapsedSec += 1;

        var v = client.speedMps;

        if (v == null || !client.isFresh()) {
            v = 0.0;
        }

        var incline = client.inclinePct;

        if (incline == null) {
            incline = 0.0;
        }

        var g = incline / 100.0;
        var cosine = 1.0 / Math.sqrt(1.0 + g * g);
        var vertical = v * g * cosine;

        distanceM += v * dt;
        altitudeM += vertical * dt;

        if (vertical > 0) {
            ascentM += vertical * dt;
            lapAscentM += vertical * dt;
        } else {
            descentM -= vertical * dt;
        }

        if (_fSpeed != null) { _fSpeed.setData(v); }

        if (_fDistance != null) { _fDistance.setData(distanceM); }

        if (_fAltitude != null) { _fAltitude.setData(altitudeM); }

        if (_fGrade != null) { _fGrade.setData(incline); }

        if (_fIncline != null) { _fIncline.setData(incline); }
    }

    //! Session totals are written once at the end of the recording, but set
    //! them every update too so an unclean exit still carries a value.
    function writeSummaryFields() as Void {
        if (_fSesAscent != null) { _fSesAscent.setData(round(ascentM)); }

        if (_fSesDescent != null) { _fSesDescent.setData(round(descentM)); }

        if (_fSesAscentPlain != null) { _fSesAscentPlain.setData(round(ascentM)); }

        if (_fLapAscent != null) { _fLapAscent.setData(round(lapAscentM)); }
    }

    //! Start from the barometric altitude so the elevation profile sits at a
    //! plausible absolute height rather than at zero.
    private function seedAltitude() as Void {
        var info = Activity.getActivityInfo();

        if (info != null && info.altitude != null) {
            altitudeM = info.altitude;
        }
    }

    private function createFields() as Void {
        _fAltitude = field("altitude", 0, FitContributor.DATA_TYPE_FLOAT,
            FitContributor.MESG_TYPE_RECORD, "m", REC_ENHANCED_ALTITUDE);

        _fSpeed = field("speed", 1, FitContributor.DATA_TYPE_FLOAT,
            FitContributor.MESG_TYPE_RECORD, "m/s", REC_ENHANCED_SPEED);

        _fDistance = field("distance", 2, FitContributor.DATA_TYPE_FLOAT,
            FitContributor.MESG_TYPE_RECORD, "m", REC_DISTANCE);

        _fGrade = field("grade", 3, FitContributor.DATA_TYPE_FLOAT,
            FitContributor.MESG_TYPE_RECORD, "%", REC_GRADE);

        // Deliberately unmapped: whatever happens to the native mappings, the
        // incline trace is always readable in Garmin Connect and Strava.
        _fIncline = field("incline", 4, FitContributor.DATA_TYPE_FLOAT,
            FitContributor.MESG_TYPE_RECORD, "%", null);

        _fSesAscent = field("total_ascent", 5, FitContributor.DATA_TYPE_UINT16,
            FitContributor.MESG_TYPE_SESSION, "m", SES_TOTAL_ASCENT);

        _fSesDescent = field("total_descent", 6, FitContributor.DATA_TYPE_UINT16,
            FitContributor.MESG_TYPE_SESSION, "m", SES_TOTAL_DESCENT);

        _fSesAscentPlain = field("treadmill_ascent", 7, FitContributor.DATA_TYPE_UINT16,
            FitContributor.MESG_TYPE_SESSION, "m", null);

        _fLapAscent = field("lap_ascent", 8, FitContributor.DATA_TYPE_UINT16,
            FitContributor.MESG_TYPE_LAP, "m", LAP_TOTAL_ASCENT);
    }

    //! Create one field, falling back to an unmapped field if the runtime
    //! refuses the :nativeNum option, so a rejected mapping degrades to a
    //! plain developer field instead of killing the recording.
    private function field(
        name as Lang.String,
        id as Lang.Number,
        type as FitContributor.DataType,
        mesgType as Lang.Number,
        units as Lang.String,
        nativeNum as Lang.Number or Null
    ) as FitContributor.Field or Null {
        if (nativeNum != null) {
            try {
                return _session.createField(name, id, type, {
                    :mesgType => mesgType,
                    :units => units,
                    :nativeNum => nativeNum
                });
            } catch (e) {
                nativeRejected += name + " ";
            }
        }

        try {
            return _session.createField(name, id, type, {
                :mesgType => mesgType,
                :units => units
            });
        } catch (e) {
            return null;
        }
    }

    private function round(v as Lang.Float) as Lang.Number {
        if (v < 0) {
            return 0;
        }

        return (v + 0.5).toNumber();
    }
}
