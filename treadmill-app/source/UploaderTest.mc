using Toybox.Lang;
using Toybox.Test;

//! The wire format the Android companion is built against. The phone parses
//! these exact keys, so anything that changes the shape below breaks the
//! companion silently - hence the assertions on every field of part 0.
(:test)
function testWireFormat(logger as Test.Logger) as Lang.Boolean {
    var up = new Uploader();
    up.start();

    // 1501 seconds: 13 HR parts of 120, the last one holding 61 values.
    for (var i = 0; i < 1501; i += 1) {
        var speed = (i < 600) ? 2.5 : 3.0;
        var incline = (i < 900) ? 1.0 : 2.5;

        up.addSample(speed, incline, 120 + (i % 3));
    }

    Test.assertEqual(up.samplesBuffered(), 1501);
    Test.assert(!up.overflow);

    var parts = up.buildParts();
    Test.assertEqual(parts.size(), 14);

    var head = parts[0] as Lang.Dictionary;
    var key = head["k"] as Lang.Number;

    Test.assert((head["t"] as Lang.String).equals("tl_run"));
    Test.assertEqual(head["i"] as Lang.Number, 0);
    Test.assertEqual(head["n"] as Lang.Number, 14);
    Test.assertEqual(head["start"] as Lang.Number, key);
    Test.assertEqual(head["dur"] as Lang.Number, 1501);
    Test.assertEqual(up.runKey(), key);

    // Change points only: two speeds and two inclines over 1501 seconds.
    var sp = head["sp"] as Lang.Array;
    Test.assertEqual(sp.size(), 2);
    assertPair(sp[0] as Lang.Array, 0, 250);
    assertPair(sp[1] as Lang.Array, 600, 300);

    var inc = head["inc"] as Lang.Array;
    Test.assertEqual(inc.size(), 2);
    assertPair(inc[0] as Lang.Array, 0, 10);
    assertPair(inc[1] as Lang.Array, 900, 25);

    var first = parts[1] as Lang.Dictionary;
    var firstHr = first["hr"] as Lang.Array;

    Test.assertEqual(first["i"] as Lang.Number, 1);
    Test.assertEqual(first["n"] as Lang.Number, 14);
    Test.assertEqual(first["k"] as Lang.Number, key);
    Test.assertEqual(firstHr.size(), 120);
    Test.assertEqual(firstHr[0] as Lang.Number, 120);
    Test.assertEqual(firstHr[1] as Lang.Number, 121);

    var last = parts[13] as Lang.Dictionary;
    var lastHr = last["hr"] as Lang.Array;

    Test.assertEqual(last["i"] as Lang.Number, 13);
    Test.assertEqual(lastHr.size(), 61);

    // Value 1440 (12 * 120) is the 1441st reading: 1440 % 3 == 0 -> 120.
    Test.assertEqual(lastHr[0] as Lang.Number, 120);

    // Parts must be a re-slice of one continuous series, not per-chunk copies:
    // the 1500th value (index 1499, 1499 % 3 == 2) lands in part 13 at offset 59.
    Test.assertEqual(lastHr[59] as Lang.Number, 122);

    up.discard();

    return true;
}

//! A run that recorded nothing still ships, as a single part with no heart
//! rate slices behind it.
(:test)
function testEmptyRunIsOnePart(logger as Test.Logger) as Lang.Boolean {
    var up = new Uploader();
    up.start();

    var parts = up.buildParts();
    Test.assertEqual(parts.size(), 1);

    var head = parts[0] as Lang.Dictionary;
    Test.assertEqual(head["n"] as Lang.Number, 1);
    Test.assertEqual(head["dur"] as Lang.Number, 0);
    Test.assertEqual((head["sp"] as Lang.Array).size(), 0);

    up.discard();

    return true;
}

//! Parking a run in storage and replaying it must reproduce the same payload
//! under the same key, because the phone dedupes runs on that key.
(:test)
function testPersistAndRetryRoundTrip(logger as Test.Logger) as Lang.Boolean {
    var up = new Uploader();
    up.start();

    for (var i = 0; i < 30; i += 1) {
        up.addSample(2.0, 3.0, 130);
    }

    var key = up.runKey();
    var expected = up.buildParts();

    up.persistIfUnfinished();
    Test.assertEqual(up.state, Uploader.STATE_FAILED);
    Test.assert(up.hasPending());

    // loadPending() rather than retry(): a transmit opened here would outlive
    // the test and keep the harness from ever finishing.
    var replay = new Uploader();
    Test.assert(replay.loadPending());
    Test.assertEqual(replay.runKey(), key);
    Test.assertEqual(replay.samplesBuffered(), 30);

    var parts = replay.buildParts();
    Test.assertEqual(parts.size(), expected.size());

    var head = parts[0] as Lang.Dictionary;
    Test.assertEqual(head["dur"] as Lang.Number, 30);
    assertPair((head["sp"] as Lang.Array)[0] as Lang.Array, 0, 200);
    assertPair((head["inc"] as Lang.Array)[0] as Lang.Array, 0, 30);
    Test.assertEqual(((parts[1] as Lang.Dictionary)["hr"] as Lang.Array).size(), 30);

    replay.discard();
    Test.assert(!replay.hasPending());

    return true;
}

function assertPair(pair as Lang.Array, sec as Lang.Number, value as Lang.Number) as Void {
    Test.assertEqual(pair.size(), 2);
    Test.assertEqual(pair[0] as Lang.Number, sec);
    Test.assertEqual(pair[1] as Lang.Number, value);
}
