using Toybox.Lang;
using Toybox.Test;

//! The wire format the Android companion is built against. The phone parses
//! these exact keys, so anything that changes the shape below breaks the
//! companion silently - hence the assertions on every field of part 0.
(:test)
function testWireFormat(logger as Test.Logger) as Lang.Boolean {
    var up = new Uploader();
    up.start();

    // 1501 seconds: 26 sample parts of 60 seconds, the last one holding 1.
    for (var i = 0; i < 1501; i += 1) {
        var speed = (i < 600) ? 2.5 : 3.0;
        var incline = (i < 900) ? 1.0 : 2.5;

        up.addSample(speed, incline, 120 + (i % 3));
    }

    Test.assertEqual(up.samplesBuffered(), 1501);
    Test.assert(!up.overflow);

    var parts = up.buildParts();
    Test.assertEqual(parts.size(), 27);

    var head = parts[0] as Lang.Dictionary;
    var key = head["k"] as Lang.Number;

    Test.assert((head["t"] as Lang.String).equals("tl_run"));
    Test.assertEqual(head["i"] as Lang.Number, 0);
    Test.assertEqual(head["n"] as Lang.Number, 27);
    Test.assertEqual(head["start"] as Lang.Number, key);
    Test.assertEqual(head["dur"] as Lang.Number, 1501);
    Test.assertEqual(up.runKey(), key);

    // The head keeps the speed change points, which the phone falls back to
    // when a part carries no per-second "v": two speeds, two inclines.
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
    var firstV = first["v"] as Lang.Array;

    Test.assertEqual(first["i"] as Lang.Number, 1);
    Test.assertEqual(first["n"] as Lang.Number, 27);
    Test.assertEqual(first["k"] as Lang.Number, key);
    Test.assertEqual(firstHr.size(), 60);
    Test.assertEqual(firstHr[0] as Lang.Number, 120);
    Test.assertEqual(firstHr[1] as Lang.Number, 121);
    Test.assertEqual(firstV.size(), 60);
    Test.assertEqual(firstV[0] as Lang.Number, 250);
    Test.assertEqual(firstV[59] as Lang.Number, 250);

    // Every sample part carries both series, equally long, so the phone can
    // walk them second for second.
    for (var i = 1; i < parts.size(); i += 1) {
        var part = parts[i] as Lang.Dictionary;
        var hr = part["hr"] as Lang.Array;
        var v = part["v"] as Lang.Array;

        Test.assertEqual(part["i"] as Lang.Number, i);
        Test.assert(v != null);
        Test.assertEqual(v.size(), hr.size());
    }

    // The speed step at second 600 falls on a part boundary: 600 / 60 == 10,
    // so part 10 ends on 2.5 m/s and part 11 opens on 3.0.
    Test.assertEqual(((parts[10] as Lang.Dictionary)["v"] as Lang.Array)[59] as Lang.Number, 250);
    Test.assertEqual(((parts[11] as Lang.Dictionary)["v"] as Lang.Array)[0] as Lang.Number, 300);

    // Parts must be a re-slice of one continuous series, not per-chunk copies:
    // the 1500th value (index 1499, 1499 % 3 == 2) lands in part 25 at offset
    // 59, well past the 500-value Storage chunking.
    var tail = parts[25] as Lang.Dictionary;
    var tailHr = tail["hr"] as Lang.Array;

    Test.assertEqual(tailHr.size(), 60);
    Test.assertEqual(tailHr[59] as Lang.Number, 122);
    Test.assertEqual(((tail["v"] as Lang.Array)[59]) as Lang.Number, 300);

    // 1501 seconds do not divide evenly: the last part holds the odd second.
    var last = parts[26] as Lang.Dictionary;
    var lastHr = last["hr"] as Lang.Array;

    Test.assertEqual(last["i"] as Lang.Number, 26);
    Test.assertEqual(lastHr.size(), 1);

    // Index 1500, 1500 % 3 == 0 -> 120.
    Test.assertEqual(lastHr[0] as Lang.Number, 120);
    Test.assertEqual((last["v"] as Lang.Array).size(), 1);
    Test.assertEqual(((last["v"] as Lang.Array)[0]) as Lang.Number, 300);

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

    // The per-second speed series has to survive the trip too, not just the
    // change points, or a replayed run would draw a flat staircase.
    var sample = parts[1] as Lang.Dictionary;
    var hr = sample["hr"] as Lang.Array;
    var v = sample["v"] as Lang.Array;

    Test.assertEqual(hr.size(), 30);
    Test.assert(v != null);
    Test.assertEqual(v.size(), 30);
    Test.assertEqual(v[0] as Lang.Number, 200);
    Test.assertEqual(v[29] as Lang.Number, 200);

    replay.discard();
    Test.assert(!replay.hasPending());

    return true;
}

function assertPair(pair as Lang.Array, sec as Lang.Number, value as Lang.Number) as Void {
    Test.assertEqual(pair.size(), 2);
    Test.assertEqual(pair[0] as Lang.Number, sec);
    Test.assertEqual(pair[1] as Lang.Number, value);
}
