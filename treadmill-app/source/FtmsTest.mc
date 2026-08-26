using Toybox.Lang;
using Toybox.Test;

//! Unit tests for the Treadmill Data decoder. The packet layouts below are
//! hand-assembled from the FTMS spec, so they check the flag-to-offset walk
//! that is otherwise only exercised on a real treadmill.

//! Flags 0x041C: instantaneous speed, total distance, inclination + ramp
//! angle, elevation gain, elapsed time.
(:test)
function testFullPacket(logger as Test.Logger) as Lang.Boolean {
    var packet = [
        0x1C, 0x04,             // flags
        0xE8, 0x03,             // speed 1000 -> 10.00 km/h
        0xD2, 0x04, 0x00,       // total distance 1234 m
        0x19, 0x00,             // inclination 25 -> 2.5 %
        0x0E, 0x00,             // ramp angle 14 -> 1.4 deg
        0x7B, 0x00,             // positive elevation gain 123 -> 12.3 m
        0x00, 0x00,             // negative elevation gain 0
        0x58, 0x02              // elapsed time 600 s
    ]b;

    var d = Ftms.parseTreadmillData(packet);

    Test.assertEqual(d[:flags], 0x041C);
    assertClose(d[:speedMps], 10.0 / 3.6, 0.001);
    Test.assertEqual(d[:distanceM], 1234);
    assertClose(d[:inclinePct], 2.5, 0.001);
    assertClose(d[:rampDeg], 1.4, 0.001);
    assertClose(d[:elevGainM], 12.3, 0.001);
    Test.assertEqual(d[:elapsedSec], 600);

    return true;
}

//! Downhill: inclination is signed, so 0xFFE2 must decode to -3.0 %.
(:test)
function testNegativeIncline(logger as Test.Logger) as Lang.Boolean {
    var packet = [
        0x08, 0x00,             // flags: inclination (More Data clear, so speed present)
        0xE8, 0x03,             // speed 1000 -> 10.00 km/h
        0xE2, 0xFF,             // inclination -30 -> -3.0 %
        0x00, 0x00              // ramp angle 0
    ]b;

    var d = Ftms.parseTreadmillData(packet);

    assertClose(d[:speedMps], 10.0 / 3.6, 0.001);
    assertClose(d[:inclinePct], -3.0, 0.001);

    return true;
}

//! More Data set means instantaneous speed is absent, so every later field
//! shifts two bytes earlier. Getting this backwards would misread every value.
(:test)
function testMoreDataSkipsSpeed(logger as Test.Logger) as Lang.Boolean {
    var packet = [
        0x09, 0x00,             // flags: More Data + inclination
        0x32, 0x00,             // inclination 50 -> 5.0 %
        0x00, 0x00              // ramp angle 0
    ]b;

    var d = Ftms.parseTreadmillData(packet);

    Test.assert(!d.hasKey(:speedMps));
    assertClose(d[:inclinePct], 5.0, 0.001);

    return true;
}

//! A packet that claims fields it does not carry must return what was decoded
//! rather than reading past the end of the buffer.
(:test)
function testTruncatedPacket(logger as Test.Logger) as Lang.Boolean {
    var packet = [
        0x0C, 0x00,             // flags: speed + total distance
        0xE8, 0x03              // speed present, distance bytes missing
    ]b;

    var d = Ftms.parseTreadmillData(packet);

    assertClose(d[:speedMps], 10.0 / 3.6, 0.001);
    Test.assert(!d.hasKey(:distanceM));

    return true;
}

(:test)
function testEmptyPacket(logger as Test.Logger) as Lang.Boolean {
    Test.assert(Ftms.parseTreadmillData(null).isEmpty());
    Test.assert(Ftms.parseTreadmillData([0x00]b).isEmpty());

    return true;
}

//! Heart rate sits after the variable-length energy block, so this checks the
//! cumulative offset rather than a single field.
(:test)
function testHeartRateAfterEnergy(logger as Test.Logger) as Lang.Boolean {
    var packet = [
        0x80, 0x01,             // flags: expended energy + heart rate
        0xE8, 0x03,             // speed 1000 -> 10.00 km/h
        0x64, 0x00,             // total energy 100 kcal
        0xC8, 0x00,             // energy per hour 200
        0x05,                   // energy per minute 5
        0x8E                    // heart rate 142
    ]b;

    var d = Ftms.parseTreadmillData(packet);

    Test.assertEqual(d[:kcal], 100);
    Test.assertEqual(d[:heartRate], 142);

    return true;
}

//! Elevation derived from belt speed and grade: at 10 km/h on a 10 % incline
//! the vertical component is v * sin(atan(0.1)).
(:test)
function testVerticalComponent(logger as Test.Logger) as Lang.Boolean {
    var v = 10.0 / 3.6;
    var g = 0.1;
    var expected = v * g / Toybox.Math.sqrt(1.0 + g * g);

    assertClose(expected, 0.27639, 0.0005);

    return true;
}

function assertClose(actual as Lang.Object or Null, expected as Lang.Float, tol as Lang.Float) as Void {
    Test.assert(actual != null);

    var a = actual as Lang.Float;
    var diff = a - expected;

    if (diff < 0) {
        diff = -diff;
    }

    Test.assertMessage(diff <= tol, "expected " + expected.toString() + " got " + a.toString());
}
