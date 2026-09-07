"""Tests for the treadmill collector: FIT construction and the HTTP flow.

Nothing here touches the network - the Garmin upload is monkeypatched, and
garminconnect is never imported.

    python -m unittest discover -s server -v
"""
import json
import os
import shutil
import tempfile
import unittest

os.environ.setdefault('DATA_DIR', tempfile.mkdtemp(prefix='treadmill-import-'))
os.environ.setdefault('TREADMILL_TOKEN', 'test-secret')

import fit_builder
from fit_builder import GARMIN_EPOCH, build_fit, derive
from fitlib import parse

import app as server

TOKEN = 'test-secret'
START = 1756200000
SPEED = 2.78
INCLINE = 10.0


def steady(count, speed=SPEED, incline=INCLINE, hr=150):
    return [[speed, incline, hr] for _ in range(count)]


def messages_of(blob, gnum):
    _, messages = parse(blob)

    return [m for m in messages if m.gnum == gnum]


def read_bytes(path):
    with open(path, 'rb') as fh:
        return fh.read()


class FitBuild(unittest.TestCase):
    """300 s at 2.78 m/s on a 10% incline must come back out of the file."""

    @classmethod
    def setUpClass(cls):
        cls.blob, cls.totals = build_fit(steady(300), START, start_altitude=100.0,
                                         tz_offset=7200)
        cls.header, cls.messages = parse(cls.blob)

    def test_file_starts_with_a_file_id_and_a_start_event(self):
        self.assertEqual(self.messages[0].gnum, 0)
        self.assertEqual(self.messages[0].get(0), 4)
        self.assertEqual(self.messages[0].get(4), START - GARMIN_EPOCH)

        event = self.messages[1]

        self.assertEqual(event.gnum, 21)
        self.assertEqual((event.get(0), event.get(1)), (0, 0))

    def test_message_order_is_records_then_stop_lap_session_activity(self):
        tail = [m.gnum for m in self.messages[-4:]]

        self.assertEqual(tail, [21, 19, 18, 34])
        self.assertEqual(self.messages[-4].get(1), 4)

    def test_one_record_per_sample_with_second_by_second_timestamps(self):
        records = messages_of(self.blob, 20)

        self.assertEqual(len(records), 300)

        stamps = [r.get(253) for r in records]

        self.assertEqual(stamps[0], START - GARMIN_EPOCH + 1)
        self.assertEqual(stamps[-1], START - GARMIN_EPOCH + 300)
        self.assertEqual(stamps, sorted(stamps))

    def test_records_carry_native_and_enhanced_copies(self):
        last = messages_of(self.blob, 20)[-1]

        self.assertAlmostEqual(last.get(5) / 100.0, 834.0, places=1)
        self.assertEqual(last.get(6), 2780)
        self.assertEqual(last.get(73), 2780)
        self.assertEqual(last.get(9), 1000)
        self.assertEqual(last.get(3), 150)
        self.assertEqual(last.get(2), last.get(78))
        self.assertAlmostEqual(last.get(78) / 5.0 - 500.0,
                               self.totals['end_altitude'], places=0)

    def test_distance_and_altitude_only_ever_climb(self):
        records = messages_of(self.blob, 20)
        distances = [r.get(5) for r in records]
        altitudes = [r.get(78) for r in records]

        self.assertEqual(distances, sorted(distances))
        self.assertEqual(altitudes, sorted(altitudes))

    def test_session_totals(self):
        session = messages_of(self.blob, 18)[0]

        self.assertEqual(session.get(5), 1)
        self.assertEqual(session.get(6), 58)   # virtual_activity
        self.assertEqual(session.get(2), START - GARMIN_EPOCH)
        self.assertEqual(session.get(253), START - GARMIN_EPOCH + 300)
        self.assertEqual(session.get(7), 300000)
        self.assertEqual(session.get(8), 300000)
        self.assertAlmostEqual(session.get(9) / 100.0, 834.0, places=1)
        self.assertEqual(session.get(14), 2780)
        self.assertEqual(session.get(15), 2780)
        self.assertEqual(session.get(124), 2780)
        self.assertEqual(session.get(125), 2780)
        self.assertEqual(session.get(16), 150)
        self.assertEqual(session.get(17), 150)
        self.assertEqual(session.get(26), 1)
        self.assertEqual(session.get(254), 0)

    def test_ascent_matches_the_trigonometry(self):
        session = messages_of(self.blob, 18)[0]
        expected = 300 * SPEED * 0.1 / (1.01 ** 0.5)

        self.assertAlmostEqual(self.totals['ascent'], expected, places=3)
        self.assertEqual(session.get(22), round(expected))
        self.assertEqual(session.get(23), 0)

    def test_lap_agrees_with_the_session(self):
        lap = messages_of(self.blob, 19)[0]
        session = messages_of(self.blob, 18)[0]

        self.assertEqual(lap.get(9), session.get(9))
        self.assertEqual(lap.get(13), session.get(14))
        self.assertEqual(lap.get(14), session.get(15))
        self.assertEqual(lap.get(21), session.get(22))
        self.assertEqual(lap.get(2), session.get(2))
        self.assertEqual(lap.get(254), 0)

    def test_activity_carries_local_time(self):
        activity = messages_of(self.blob, 34)[0]

        self.assertEqual(activity.get(0), 300000)
        self.assertEqual(activity.get(1), 1)
        self.assertEqual(activity.get(3), 26)
        self.assertEqual(activity.get(5), activity.get(253) + 7200)

    def test_downhill_accumulates_descent(self):
        _, totals = derive(steady(100, incline=-5.0))

        self.assertEqual(totals['ascent'], 0.0)
        self.assertGreater(totals['descent'], 0.0)

    def test_missing_heart_rate_is_written_as_invalid(self):
        blob, totals = build_fit([[2.0, 0.0, 0], [2.0, 0.0, None]], START)
        records = messages_of(blob, 20)

        self.assertIsNone(records[0].get(3))
        self.assertIsNone(records[1].get(3))
        self.assertIsNone(totals['avg_hr'])

    def test_empty_sample_list_is_refused(self):
        with self.assertRaises(ValueError):
            build_fit([], START)


class HttpFlow(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix='treadmill-test-')
        self.previous_dir = server.DATA_DIR
        server.DATA_DIR = self.dir
        os.environ['TREADMILL_TOKEN'] = TOKEN
        server.app.config['TESTING'] = True
        self.client = server.app.test_client()
        self.uploads = []
        self.previous_upload = server.upload_fit
        server.upload_fit = self.uploads.append

    def tearDown(self):
        server.upload_fit = self.previous_upload
        server.DATA_DIR = self.previous_dir
        shutil.rmtree(self.dir, ignore_errors=True)

    def post(self, path, payload, token=TOKEN):
        headers = {} if token is None else {'X-Token': token}

        return self.client.post(path, json=payload, headers=headers)

    def open_session(self, alt=100.0):
        response = self.post('/v1/session', {'start': START, 'alt': alt})

        self.assertEqual(response.status_code, 200)

        return response.get_json()['id']

    def test_health_needs_no_token(self):
        response = self.client.get('/health')

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_json(), {'ok': True})

    def test_every_other_endpoint_rejects_a_bad_or_missing_token(self):
        for token in (None, '', 'wrong'):
            response = self.post('/v1/session', {'start': START}, token=token)

            self.assertEqual(response.status_code, 401)
            self.assertEqual(response.get_json()['ok'], False)

    def test_missing_server_secret_locks_everything(self):
        os.environ['TREADMILL_TOKEN'] = ''
        response = self.post('/v1/session', {'start': START})

        self.assertEqual(response.status_code, 401)

    def test_every_response_body_is_a_json_object(self):
        sid = self.open_session()
        responses = [
            self.client.get('/health'),
            self.post('/v1/session/%s/samples' % sid, {'seq': 0, 's': steady(2)}),
            self.client.get('/v1/session/%s' % sid, headers={'X-Token': TOKEN}),
            self.post('/v1/session/nope1234/samples', {'seq': 0, 's': []}),
        ]

        for response in responses:
            self.assertIsInstance(response.get_json(), dict)

    def test_chunks_are_assembled_in_seq_order_however_they_arrive(self):
        sid = self.open_session()

        self.post('/v1/session/%s/samples' % sid, {'seq': 2, 's': steady(30, speed=3.0)})
        self.post('/v1/session/%s/samples' % sid, {'seq': 0, 's': steady(30, speed=1.0)})
        self.post('/v1/session/%s/samples' % sid, {'seq': 1, 's': steady(30, speed=2.0)})

        response = self.post('/v1/session/%s/finish' % sid, {})
        body = response.get_json()

        self.assertEqual(response.status_code, 200)
        self.assertEqual(body['seconds'], 90)
        self.assertAlmostEqual(body['distance'], 30 * (1.0 + 2.0 + 3.0), places=1)

        blob = read_bytes(os.path.join(self.dir, sid, 'built.fit'))
        speeds = [r.get(73) for r in messages_of(blob, 20)]

        self.assertEqual(speeds[0], 1000)
        self.assertEqual(speeds[30], 2000)
        self.assertEqual(speeds[60], 3000)

    def test_resending_a_seq_overwrites_that_chunk(self):
        sid = self.open_session()

        self.post('/v1/session/%s/samples' % sid, {'seq': 0, 's': steady(10)})
        response = self.post('/v1/session/%s/samples' % sid, {'seq': 0, 's': steady(4)})

        self.assertEqual(response.get_json(), {'ok': True, 'seq': 0})

        chunks = [n for n in os.listdir(os.path.join(self.dir, sid))
                  if n.startswith('chunk_')]

        self.assertEqual(chunks, ['chunk_0.json'])

        status = self.client.get('/v1/session/%s' % sid,
                                 headers={'X-Token': TOKEN}).get_json()

        self.assertEqual(status['samples'], 4)
        self.assertEqual(status['chunks'], 1)

    def test_finish_uploads_once_and_replays_the_stored_result(self):
        sid = self.open_session()

        self.post('/v1/session/%s/samples' % sid, {'seq': 0, 's': steady(300)})

        first = self.post('/v1/session/%s/finish' % sid, {})
        second = self.post('/v1/session/%s/finish' % sid, {})

        self.assertEqual(first.status_code, 200)
        self.assertEqual(second.status_code, 200)
        self.assertEqual(first.get_json(), second.get_json())
        self.assertEqual(len(self.uploads), 1)
        self.assertAlmostEqual(first.get_json()['distance'], 834.0, places=1)
        self.assertEqual(first.get_json()['seconds'], 300)

        stored = json.loads(read_bytes(os.path.join(self.dir, sid, 'result.json')))

        self.assertEqual(stored, first.get_json())

    def test_a_failed_upload_keeps_the_fit_for_a_retry(self):
        sid = self.open_session()

        self.post('/v1/session/%s/samples' % sid, {'seq': 0, 's': steady(60)})

        def explode(path):
            raise RuntimeError('garmin said no')

        server.upload_fit = explode
        response = self.post('/v1/session/%s/finish' % sid, {})

        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.get_json()['ok'], False)
        self.assertIn('garmin said no', response.get_json()['error'])
        self.assertTrue(os.path.exists(os.path.join(self.dir, sid, 'built.fit')))
        self.assertFalse(os.path.exists(os.path.join(self.dir, sid, 'result.json')))

        server.upload_fit = self.uploads.append
        retry = self.post('/v1/session/%s/finish' % sid, {})

        self.assertEqual(retry.status_code, 200)
        self.assertEqual(len(self.uploads), 1)

    def test_finish_without_samples_is_refused(self):
        sid = self.open_session()
        response = self.post('/v1/session/%s/finish' % sid, {})

        self.assertEqual(response.status_code, 400)
        self.assertEqual(self.uploads, [])

    def test_unknown_and_malformed_session_ids(self):
        self.assertEqual(self.post('/v1/session/aaaaaaaa/samples',
                                   {'seq': 0, 's': []}).status_code, 404)
        self.assertEqual(self.post('/v1/session/../../etc/samples',
                                   {'seq': 0, 's': []}).status_code, 404)
        self.assertEqual(self.post('/v1/session/ZZZZ/finish', {}).status_code, 400)

    def test_bad_chunk_bodies_are_refused(self):
        sid = self.open_session()

        self.assertEqual(self.post('/v1/session/%s/samples' % sid,
                                   {'seq': 0}).status_code, 400)
        self.assertEqual(self.post('/v1/session/%s/samples' % sid,
                                   {'seq': -1, 's': []}).status_code, 400)
        self.assertEqual(self.post('/v1/session/%s/samples' % sid,
                                   {'seq': 'x', 's': []}).status_code, 400)

    def test_status_reports_progress(self):
        sid = self.open_session(alt=1500.0)
        before = self.client.get('/v1/session/%s' % sid,
                                 headers={'X-Token': TOKEN}).get_json()

        self.assertEqual((before['chunks'], before['samples']), (0, 0))
        self.assertFalse(before['built'])
        self.assertFalse(before['uploaded'])
        self.assertEqual(before['alt'], 1500.0)

        self.post('/v1/session/%s/samples' % sid, {'seq': 0, 's': steady(5)})
        self.post('/v1/session/%s/finish' % sid, {})

        after = self.client.get('/v1/session/%s' % sid,
                                headers={'X-Token': TOKEN}).get_json()

        self.assertTrue(after['built'])
        self.assertTrue(after['uploaded'])
        self.assertEqual(after['result']['seconds'], 5)

    def test_start_altitude_comes_from_the_session_metadata(self):
        sid = self.open_session(alt=1500.0)

        self.post('/v1/session/%s/samples' % sid, {'seq': 0, 's': steady(3)})
        self.post('/v1/session/%s/finish' % sid, {})

        blob = read_bytes(os.path.join(self.dir, sid, 'built.fit'))
        first = messages_of(blob, 20)[0]

        self.assertAlmostEqual(first.get(78) / 5.0 - 500.0, 1500.28, delta=0.2)


class Environment(unittest.TestCase):

    def test_tz_offset_defaults_to_south_africa(self):
        previous = os.environ.pop('TZ_OFFSET_SECONDS', None)

        try:
            self.assertEqual(fit_builder.tz_offset_seconds(), 7200)
            os.environ['TZ_OFFSET_SECONDS'] = '-18000'
            self.assertEqual(fit_builder.tz_offset_seconds(), -18000)
            os.environ['TZ_OFFSET_SECONDS'] = 'nonsense'
            self.assertEqual(fit_builder.tz_offset_seconds(), 7200)
        finally:
            os.environ.pop('TZ_OFFSET_SECONDS', None)

            if previous is not None:
                os.environ['TZ_OFFSET_SECONDS'] = previous


if __name__ == '__main__':
    unittest.main(verbosity=2)
