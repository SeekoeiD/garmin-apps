"""Deploy the treadmill upload server to Coolify on worker7.

The application is created as an inline-Dockerfile app: the server sources are
base64-embedded into the Dockerfile, so no git hosting is involved anywhere.
Re-running updates the Dockerfile and env vars and triggers a redeploy.

    python deploy_server.py           # deploy / update
    python deploy_server.py --status  # show app status only

Requires COOLIFY_WORKER7_TOKEN in the environment (never printed) and a valid
Garmin token store in tools/tokens (created by garmin_login.py).
"""
import argparse
import base64
import json
import os
import secrets
import sys
import time

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
STATE_FILE = os.path.join(HERE, 'deploy_state.json')

BASE = 'http://worker7:8000/api/v1'
SERVER_UUID = 's88oc4s4oc8g4ks00occ4cco'          # worker7 "localhost"
PROJECT_NAME = 'Treadmill'
APP_NAME = 'treadmill-upload'
DOMAIN = 'https://treadmill.156.155.98.24.sslip.io'
PORT = '8080'

# Files embedded into the image. Paths are relative to the repo root.
EMBED = {
    'app.py': 'server/app.py',
    'fit_builder.py': 'server/fit_builder.py',
    'fitlib.py': 'tools/fitlib.py',
}


def api(method, path, **kwargs):
    token = os.environ.get('COOLIFY_WORKER7_TOKEN')

    if not token:
        raise SystemExit('COOLIFY_WORKER7_TOKEN is not set')

    r = requests.request(
        method, BASE + path,
        headers={'Authorization': 'Bearer ' + token}, timeout=60, **kwargs)

    if r.status_code >= 400:
        raise SystemExit('%s %s -> %d: %s' % (method, path, r.status_code, r.text[:500]))

    return r.json() if r.text else {}


def make_dockerfile():
    lines = [
        'FROM python:3.12-slim',
        'RUN pip install --no-cache-dir flask gunicorn garminconnect',
        'WORKDIR /app',
        'RUN mkdir -p /data',
    ]

    for name, rel in EMBED.items():
        path = os.path.join(ROOT, rel)

        with open(path, 'rb') as fh:
            b64 = base64.b64encode(fh.read()).decode('ascii')

        lines.append(
            'RUN python -c "import base64;'
            "open('%s','wb').write(base64.b64decode('%s'))\"" % (name, b64))

    lines += [
        'EXPOSE 8080',
        'CMD ["gunicorn","-b","0.0.0.0:8080","-w","2","--timeout","120","app:app"]',
    ]

    return '\n'.join(lines)


def garmin_tokens_b64():
    """Token JSON, base64-wrapped: Coolify injects env vars into the
    Dockerfile as ARG lines, where raw JSON breaks the syntax."""
    sys.path.insert(0, HERE)
    from treadmill_sync import connect

    client = connect()

    return base64.b64encode(client.client.dumps().encode('utf-8')).decode('ascii')


def load_state():
    if os.path.exists(STATE_FILE):
        with open(STATE_FILE) as fh:
            return json.load(fh)

    return {}


def save_state(state):
    with open(STATE_FILE, 'w') as fh:
        json.dump(state, fh, indent=2)


def find_project():
    for p in api('GET', '/projects'):
        if p['name'] == PROJECT_NAME:
            return p['uuid']

    p = api('POST', '/projects', json={'name': PROJECT_NAME,
                                       'description': 'FR965 treadmill run uploader'})

    return p['uuid']


def find_app():
    for a in api('GET', '/applications'):
        if a.get('name') == APP_NAME:
            return a['uuid']

    return None


def set_env(app_uuid, key, value):
    body = {'key': key, 'value': value, 'is_preview': False, 'is_literal': True}

    try:
        api('POST', '/applications/%s/envs' % app_uuid, json=body)
    except SystemExit as e:
        if 'already' not in str(e).lower():
            raise

        api('PATCH', '/applications/%s/envs' % app_uuid, json=body)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--status', action='store_true')
    args = ap.parse_args()

    state = load_state()
    app_uuid = state.get('app_uuid') or find_app()

    if args.status:
        if not app_uuid:
            print('not deployed')
            return

        a = api('GET', '/applications/%s' % app_uuid)
        print('app    :', a.get('name'), app_uuid)
        print('status :', a.get('status'))
        print('fqdn   :', a.get('fqdn'))
        return

    dockerfile = make_dockerfile()
    print('dockerfile: %d bytes, %d embedded files' % (len(dockerfile), len(EMBED)))

    if app_uuid is None:
        project_uuid = find_project()
        print('project    : %s' % project_uuid)

        created = api('POST', '/applications/dockerfile', json={
            'project_uuid': project_uuid,
            'server_uuid': SERVER_UUID,
            'environment_name': 'production',
            'name': APP_NAME,
            'dockerfile': base64.b64encode(dockerfile.encode()).decode('ascii'),
            'domains': DOMAIN,
            'ports_exposes': PORT,
            'instant_deploy': False,
            'force_domain_override': True,
        })
        app_uuid = created['uuid']
        state['app_uuid'] = app_uuid
        save_state(state)
        print('created app: %s' % app_uuid)
        api('PATCH', '/applications/%s' % app_uuid, json={'ports_exposes': PORT})
    else:
        # PATCH rejects the dockerfile field, so a code update means recreating
        # the application. Env vars and domain are re-applied below either way.
        print('recreating app: %s' % app_uuid)

        try:
            api('DELETE', '/applications/%s' % app_uuid)
        except SystemExit as e:
            if '404' not in str(e):
                raise

        for _ in range(30):
            time.sleep(2)

            if find_app() is None:
                break

        project_uuid = find_project()
        created = api('POST', '/applications/dockerfile', json={
            'project_uuid': project_uuid,
            'server_uuid': SERVER_UUID,
            'environment_name': 'production',
            'name': APP_NAME,
            'dockerfile': base64.b64encode(dockerfile.encode()).decode('ascii'),
            'domains': DOMAIN,
            'ports_exposes': PORT,
            'instant_deploy': False,
            'force_domain_override': True,
        })
        app_uuid = created['uuid']
        state['app_uuid'] = app_uuid
        save_state(state)
        api('PATCH', '/applications/%s' % app_uuid, json={'ports_exposes': PORT})

    shared = state.get('shared_token')

    if not shared:
        shared = secrets.token_hex(16)
        state['shared_token'] = shared
        save_state(state)

    print('setting env vars (values not shown)')
    set_env(app_uuid, 'TREADMILL_TOKEN', shared)
    set_env(app_uuid, 'GARMINTOKENS_B64', garmin_tokens_b64())
    set_env(app_uuid, 'TZ_OFFSET_SECONDS', '7200')
    set_env(app_uuid, 'DATA_DIR', '/data')

    print('deploying...')
    api('POST', '/deploy?uuid=%s' % app_uuid)

    for _ in range(60):
        time.sleep(10)
        a = api('GET', '/applications/%s' % app_uuid)
        status = a.get('status', '?')
        print('  status: %s' % status)

        if status.startswith('running'):
            break

    url = DOMAIN + '/health'
    print('smoke test: %s' % url)

    try:
        r = requests.get(url, timeout=30)
        print('  %d %s' % (r.status_code, r.text[:200]))
    except Exception as exc:
        print('  health check failed: %s' % exc)

    print('done. shared token is in %s (also baked into the watch app).' % STATE_FILE)


if __name__ == '__main__':
    main()
