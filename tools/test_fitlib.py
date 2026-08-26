"""Tests for the hand-written FIT parser, encoder and corrector.

Run against a real activity file:

    python -m unittest test_fitlib -v

The file under test is picked up from archive/ or passed via FIT_TEST_FILE.
Tests that need one skip cleanly if none is present.
"""
import glob
import os
import struct
import unittest

import fitfix
from fitlib import (BASE_TYPES, build, decode_scalar, encode_scalar, fit_crc,
                    parse)

HERE = os.path.dirname(os.path.abspath(__file__))


def read(path):
    with open(path, 'rb') as fh:
        return fh.read()


def find_fit():
    path = os.environ.get('FIT_TEST_FILE')

    if path and os.path.exists(path):
        return path

    for pattern in ('archive/*-original.fit', 'archive/*.fit', '*.fit'):
        hits = sorted(glob.glob(os.path.join(HERE, pattern)))

        if hits:
            return hits[0]

    return None


FIT_FILE = find_fit()


class ScalarCodec(unittest.TestCase):

    def test_round_trip_every_base_type(self):
        for base, (name, size, fmt, invalid) in BASE_TYPES.items():
            if name == 'string':
                continue

            value = 3.5 if fmt in 'fd' else 42
            raw = encode_scalar(base, value)

            self.assertEqual(len(raw), size, '%s wrong width' % name)
            self.assertAlmostEqual(decode_scalar(base, raw), value, places=3,
                                   msg='%s did not round-trip' % name)

    def test_none_encodes_as_the_invalid_marker(self):
        # uint16 invalid is 0xFFFF, and must decode back to None.
        raw = encode_scalar(0x84, None)

        self.assertEqual(raw, b'\xff\xff')
        self.assertIsNone(decode_scalar(0x84, raw))

    def test_values_are_clamped_not_wrapped(self):
        # A silly ascent must saturate at the uint16 ceiling rather than wrap
        # around to a small number, which would look like real data.
        raw = encode_scalar(0x84, 999999)

        self.assertEqual(decode_scalar(0x84, raw), None)      # 0xFFFF is invalid
        self.assertEqual(struct.unpack('<H', raw)[0], 0xFFFF)

        raw = encode_scalar(0x83, -99999)
        self.assertEqual(struct.unpack('<h', raw)[0], -32768)

    def test_rounds_to_nearest(self):
        self.assertEqual(struct.unpack('<H', encode_scalar(0x84, 10.6))[0], 11)
        self.assertEqual(struct.unpack('<H', encode_scalar(0x84, 10.4))[0], 10)


class CrcAgainstGarmin(unittest.TestCase):
    """Garmin computed the CRCs in the source file; ours must agree."""

    @unittest.skipIf(FIT_FILE is None, 'no FIT file available')
    def test_matches_the_files_own_crcs(self):
        buf = read(FIT_FILE)

        self.assertEqual(fit_crc(buf[:12]), struct.unpack('<H', buf[12:14])[0],
                         'header CRC disagrees with Garmin')
        self.assertEqual(fit_crc(buf[:-2]), struct.unpack('<H', buf[-2:])[0],
                         'file CRC disagrees with Garmin')


@unittest.skipIf(FIT_FILE is None, 'no FIT file available')
class RoundTrip(unittest.TestCase):
    """Re-emitting an untouched file must preserve every message exactly.

    This exercises the local-message-type allocator hard: a Garmin activity
    carries far more message types than the 16 local slots FIT allows, so the
    eviction-and-redefine path runs many times over.
    """

    def setUp(self):
        self.buf = read(FIT_FILE)
        self.header, self.messages = parse(self.buf)

    def test_more_layouts_than_local_slots(self):
        layouts = {m.signature() for m in self.messages}

        self.assertGreater(len(layouts), 16,
                           'this file would not exercise slot eviction')

    def test_messages_survive_a_rebuild(self):
        rebuilt = build(self.header, self.messages)
        _, again = parse(rebuilt)

        self.assertEqual(len(again), len(self.messages))

        for i, (a, b) in enumerate(zip(self.messages, again)):
            self.assertEqual(a.gnum, b.gnum, 'message %d changed type' % i)
            self.assertEqual(list(a.fields.items()), list(b.fields.items()),
                             'message %d fields changed' % i)
            self.assertEqual(list(a.devs.items()), list(b.devs.items()),
                             'message %d developer fields changed' % i)

    def test_rebuilt_file_is_self_consistent(self):
        rebuilt = build(self.header, self.messages)
        size = struct.unpack('<I', rebuilt[4:8])[0]

        self.assertEqual(rebuilt[0] + size + 2, len(rebuilt), 'declared size wrong')
        self.assertEqual(fit_crc(rebuilt[:12]), struct.unpack('<H', rebuilt[12:14])[0])
        self.assertEqual(fit_crc(rebuilt[:-2]), struct.unpack('<H', rebuilt[-2:])[0])


@unittest.skipIf(FIT_FILE is None, 'no FIT file available')
class Corrector(unittest.TestCase):
    """The rewrite must add native data without disturbing anything else."""

    @classmethod
    def setUpClass(cls):
        cls.out = os.path.join(HERE, '_test-corrected.fit')
        fitfix.main(FIT_FILE, cls.out)
        _, cls.before = parse(read(FIT_FILE))
        _, cls.after = parse(read(cls.out))

    @classmethod
    def tearDownClass(cls):
        if os.path.exists(cls.out):
            os.remove(cls.out)

    def records(self, msgs):
        return [m for m in msgs if m.gnum == 20]

    def test_heart_rate_and_timestamps_are_untouched(self):
        before, after = self.records(self.before), self.records(self.after)

        self.assertEqual(len(before), len(after), 'record count changed')

        for i, (a, b) in enumerate(zip(before, after)):
            self.assertEqual(a.get(253), b.get(253), 'record %d timestamp moved' % i)
            self.assertEqual(a.get(3), b.get(3), 'record %d heart rate changed' % i)

    def test_native_fields_match_the_developer_values(self):
        for a, b in zip(self.records(self.before), self.records(self.after)):
            alt = a.get_dev(0)
            speed = a.get_dev(1)
            dist = a.get_dev(2)

            if alt is None:
                continue

            self.assertAlmostEqual(b.get(78) / 5.0 - 500, alt, places=0)
            self.assertAlmostEqual(b.get(73) / 1000.0, speed, places=2)
            self.assertAlmostEqual(b.get(5) / 100.0, dist, places=1)

    def test_altitude_climb_agrees_with_total_ascent(self):
        recs = self.records(self.after)
        alts = [r.get(78) / 5.0 - 500 for r in recs if r.get(78) is not None]
        climb = sum(max(0.0, b - a) for a, b in zip(alts, alts[1:]))

        session = [m for m in self.after if m.gnum == 18][0]
        ascent = session.get(22)

        self.assertIsNotNone(ascent, 'session has no native total_ascent')
        self.assertLess(abs(climb - ascent), 2.0,
                        'altitude climbs %.1f m but total_ascent says %d m'
                        % (climb, ascent))

    def test_sport_is_still_a_treadmill_run(self):
        session = [m for m in self.after if m.gnum == 18][0]

        self.assertEqual(session.get(5), 1, 'sport is no longer running')
        self.assertEqual(session.get(6), 1, 'sub_sport is no longer treadmill')

    def test_mapped_developer_fields_are_gone_unmapped_ones_remain(self):
        names = {fitfix.field_name(m) for m in self.after if m.gnum == 206}

        self.assertEqual(names, {'incline', 'treadmill_ascent'},
                         'wrong developer fields survived')

    def test_distance_never_goes_backwards(self):
        last = -1.0

        for r in self.records(self.after):
            d = r.get(5)

            if d is None:
                continue

            self.assertGreaterEqual(d / 100.0, last, 'distance went backwards')
            last = d / 100.0


if __name__ == '__main__':
    unittest.main(verbosity=2)
