"""Treadmill run collector: takes 1 Hz samples from the watch, builds the FIT
and uploads it to Garmin Connect.

The Forerunner app streams chunks over HTTPS while the run is going and calls
finish at the end. Every chunk hits the disk before the response goes out, so a
crash between chunks costs nothing, and finish can be replayed safely.

    TREADMILL_TOKEN     shared secret, required in the X-Token header
    DATA_DIR            where runs are stored (default /data)
    GARMINTOKENS        garminconnect token store: JSON string or a path
    TZ_OFFSET_SECONDS   local time offset written into the activity message
"""
import hmac
import json
import os
import re
import secrets
import time

from flask import Flask, jsonify, request

from fit_builder import DEFAULT_ALTITUDE, build_fit

app = Flask(__name__)

ID_PATTERN = re.compile(r'^[0-9a-f]{8}$')
CHUNK_PATTERN = re.compile(r'^chunk_(\d+)\.json$')


def resolve_data_dir():
    wanted = os.environ.get('DATA_DIR', '/data')

    try:
        os.makedirs(wanted, exist_ok=True)
        probe = os.path.join(wanted, '.writable')

        with open(probe, 'w') as fh:
            fh.write('')

        os.remove(probe)

        return wanted
    except OSError:
        fallback = os.path.join(
            os.path.dirname(os.path.abspath(__file__)), 'data')
        os.makedirs(fallback, exist_ok=True)

        return fallback


DATA_DIR = resolve_data_dir()

_client = None


def session_dir(sid):
    return os.path.join(DATA_DIR, sid)


def write_json(path, payload):
    tmp = path + '.tmp'

    with open(tmp, 'w') as fh:
        json.dump(payload, fh)
        fh.flush()
        os.fsync(fh.fileno())

    os.replace(tmp, path)


def read_json(path):
    with open(path) as fh:
        return json.load(fh)


def load_meta(sid):
    path = os.path.join(session_dir(sid), 'meta.json')

    if not os.path.exists(path):
        return None

    return read_json(path)


def fail(message, status):
    return jsonify({'ok': False, 'error': message}), status


@app.errorhandler(404)
def handle_not_found(_):
    return fail('not found', 404)


@app.errorhandler(405)
def handle_not_allowed(_):
    return fail('method not allowed', 405)


@app.errorhandler(500)
def handle_server_error(_):
    return fail('internal error', 500)


@app.before_request
def require_token():
    if request.path == '/health':
        return None

    secret = os.environ.get('TREADMILL_TOKEN', '')
    given = request.headers.get('X-Token', '')

    if not secret or not hmac.compare_digest(secret, given):
        return fail('unauthorized', 401)

    return None


@app.get('/health')
def health():
    return jsonify({'ok': True})


@app.post('/v1/session')
def create_session():
    body = request.get_json(silent=True) or {}
    start = body.get('start')
    altitude = body.get('alt')
    sid = secrets.token_hex(4)

    meta = {
        'id': sid,
        'start': int(start) if start else int(time.time()),
        'alt': DEFAULT_ALTITUDE if altitude is None else float(altitude),
        'sport': body.get('sport') or 'treadmill_running',
        'created': int(time.time()),
    }

    os.makedirs(session_dir(sid), exist_ok=True)
    write_json(os.path.join(session_dir(sid), 'meta.json'), meta)

    return jsonify({'id': sid})


@app.post('/v1/session/<sid>/samples')
def add_samples(sid):
    if not ID_PATTERN.match(sid or ''):
        return fail('bad session id', 400)

    if load_meta(sid) is None:
        return fail('unknown session', 404)

    body = request.get_json(silent=True) or {}
    samples = body.get('s')

    if not isinstance(samples, list):
        return fail('s must be a list', 400)

    try:
        seq = int(body.get('seq'))
    except (TypeError, ValueError):
        return fail('seq must be an integer', 400)

    if seq < 0:
        return fail('seq must not be negative', 400)

    path = os.path.join(session_dir(sid), 'chunk_%d.json' % seq)
    write_json(path, samples)

    return jsonify({'ok': True, 'seq': seq})


def collect_samples(sid):
    """Every stored chunk, concatenated in seq order."""
    directory = session_dir(sid)
    chunks = []

    for name in os.listdir(directory):
        match = CHUNK_PATTERN.match(name)

        if match:
            chunks.append((int(match.group(1)), name))

    samples = []

    for _, name in sorted(chunks):
        samples.extend(read_json(os.path.join(directory, name)))

    return samples, len(chunks)


def garmin_tokenstore():
    """Deploy passes the token JSON base64-encoded: Coolify injects env vars
    into the Dockerfile as ARG lines, where raw JSON is a syntax error."""
    b64 = os.environ.get('GARMINTOKENS_B64')

    if b64:
        import base64

        return base64.b64decode(b64).decode('utf-8')

    return os.environ.get('GARMINTOKENS') or None


def garmin_client():
    global _client

    if _client is None:
        from garminconnect import Garmin

        client = Garmin()
        client.login(tokenstore=garmin_tokenstore())
        _client = client

    return _client


def upload_fit(path):
    """Upload, and rebuild the client once in case the session went stale."""
    global _client

    try:
        return garmin_client().upload_activity(path)
    except Exception:
        _client = None

    return garmin_client().upload_activity(path)


@app.post('/v1/session/<sid>/finish')
def finish_session(sid):
    if not ID_PATTERN.match(sid or ''):
        return fail('bad session id', 400)

    meta = load_meta(sid)

    if meta is None:
        return fail('unknown session', 404)

    result_path = os.path.join(session_dir(sid), 'result.json')

    if os.path.exists(result_path):
        return jsonify(read_json(result_path))

    samples, _ = collect_samples(sid)

    if not samples:
        return fail('no samples', 400)

    blob, totals = build_fit(samples, meta['start'], meta.get('alt', DEFAULT_ALTITUDE))
    fit_path = os.path.join(session_dir(sid), 'built.fit')
    tmp = fit_path + '.tmp'

    with open(tmp, 'wb') as fh:
        fh.write(blob)
        fh.flush()
        os.fsync(fh.fileno())

    os.replace(tmp, fit_path)

    summary = {
        'ok': True,
        'distance': round(totals['distance'], 1),
        'ascent': round(totals['ascent'], 1),
        'seconds': int(totals['seconds']),
    }

    try:
        upload_fit(fit_path)
    except Exception as exc:
        app.logger.warning('session %s upload failed: %s', sid, exc)

        return fail(str(exc) or exc.__class__.__name__, 502)

    write_json(result_path, summary)

    return jsonify(summary)


@app.get('/v1/session/<sid>')
def session_status(sid):
    if not ID_PATTERN.match(sid or ''):
        return fail('bad session id', 400)

    meta = load_meta(sid)

    if meta is None:
        return fail('unknown session', 404)

    samples, chunks = collect_samples(sid)
    result_path = os.path.join(session_dir(sid), 'result.json')

    return jsonify({
        'ok': True,
        'id': sid,
        'start': meta['start'],
        'alt': meta.get('alt'),
        'chunks': chunks,
        'samples': len(samples),
        'built': os.path.exists(os.path.join(session_dir(sid), 'built.fit')),
        'uploaded': os.path.exists(result_path),
        'result': read_json(result_path) if os.path.exists(result_path) else None,
    })


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8080)
