using Toybox.Lang;
using Toybox.System;
using Toybox.Test;

//! Drives a whole recording without a treadmill: builds the session, feeds a
//! minute of synthetic 10 km/h at 5 % incline, and saves the FIT file.
//!
//! The point is the artefact, not the assertions - the saved file is what
//! shows whether :nativeNum actually lands a native_field_num in the developer
//! field description.
(:test)
function testSyntheticRecording(logger as Test.Logger) as Lang.Boolean {
    var recorder = new Recorder(null, Recorder.MODE_LEGACY);
    var client = new FtmsClient(null);

    client.speedMps = 10.0 / 3.6;
    client.inclinePct = 5.0;

    recorder.start();
    Test.assert(recorder.isRecording());
    Test.assertEqual(recorder.nativeRejected, "");

    for (var i = 0; i < 60; i += 1) {
        // Keep the reading fresh so update() does not treat it as stalled.
        client.lastPacketMs = System.getTimer();
        recorder.update(client, 1.0);
    }

    // 60 s at 10 km/h is 166.7 m along the belt; at 5 % that is 8.32 m of climb.
    logger.debug("distance " + recorder.distanceM.toString());
    logger.debug("ascent " + recorder.ascentM.toString());

    Test.assert(recorder.distanceM > 166.0 && recorder.distanceM < 167.5);
    Test.assert(recorder.ascentM > 8.2 && recorder.ascentM < 8.4);

    recorder.save();
    Test.assert(!recorder.hasSession());

    return true;
}
