"""Find treadmill activities Garmin recorded with no distance, rewrite their
FIT into real native fields, and replace them.

Garmin Connect ignores the native_field_num override the watch app writes, so
a Treadmill Link activity arrives with distance, speed and elevation all zero.
This downloads the original FIT, moves the developer-field values into native
fields, replaces the activity, and lets Garmin sync the corrected version on to
Strava by itself.

    python treadmill_sync.py                # dry run - reports, changes nothing
    python treadmill_sync.py --apply        # actually replace activities
    python treadmill_sync.py --file x.fit   # just fix a local file, no login

Safety:
  * only activities whose FIT actually carries this app's developer fields are
    touched - anything else is skipped, whatever its name or distance
  * both the original and the corrected FIT are archived locally before the
    remote activity is deleted, so a failed upload is always recoverable
  * --apply is required for any destructive step
"""
import argparse
import io
import json
import os
import sys
import zipfile
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fitfix
from fitlib import MESG_FIELD_DESCRIPTION, parse

HERE = os.path.dirname(os.path.abspath(__file__))
TOKEN_DIR = os.path.join(HERE, 'tokens')
ARCHIVE_DIR = os.path.join(HERE, 'archive')
STATE_FILE = os.path.join(HERE, 'processed.json')

# A FIT must contain these developer field names to be considered ours.
SIGNATURE = {'incline', 'altitude', 'speed', 'distance'}


def log(msg):
    print('[%s] %s' % (datetime.now().strftime('%H:%M:%S'), msg), flush=True)


def dev_field_names(buf):
    try:
        _, messages = parse(buf)
    except Exception as exc:
        log('  cannot parse FIT: %s' % exc)
        return set()

    names = set()

    for m in messages:
        if m.gnum != MESG_FIELD_DESCRIPTION:
            continue

        name = fitfix.field_name(m)

        if name:
            names.add(name)

    return names


def load_state():
    if not os.path.exists(STATE_FILE):
        return {'processed': []}

    with open(STATE_FILE) as fh:
        return json.load(fh)


def save_state(state):
    with open(STATE_FILE, 'w') as fh:
        json.dump(state, fh, indent=2)


def extract_fit(blob):
    """download_activity(ORIGINAL) hands back a zip containing the .fit."""
    if blob[8:12] == b'.FIT':
        return blob

    with zipfile.ZipFile(io.BytesIO(blob)) as zf:
        names = [n for n in zf.namelist() if n.lower().endswith('.fit')]

        if not names:
            raise ValueError('no .fit inside the downloaded archive')

        return zf.read(names[0])


def connect():
    from garminconnect import Garmin

    if not os.path.isdir(TOKEN_DIR):
        raise SystemExit('No saved tokens. Run garmin_login.py first.')

    client = Garmin()
    client.login(tokenstore=TOKEN_DIR)

    return client


def candidates(client, limit):
    """Treadmill runs with no distance - the signature of the broken upload."""
    out = []

    for act in client.get_activities(0, limit):
        type_key = (act.get('activityType') or {}).get('typeKey', '')

        if 'treadmill' not in type_key and 'running' not in type_key:
            continue

        if (act.get('distance') or 0) > 1.0:
            continue

        out.append(act)

    return out


def process(client, act, state, apply_changes):
    activity_id = str(act['activityId'])
    name = act.get('activityName') or '(unnamed)'
    started = act.get('startTimeLocal', '?')

    log('activity %s  %s  %s' % (activity_id, started, name))

    if activity_id in state['processed']:
        log('  already processed, skipping')
        return False

    blob = client.download_activity(
        activity_id, dl_fmt=client.ActivityDownloadFormat.ORIGINAL)
    original = extract_fit(blob)

    names = dev_field_names(original)

    if not SIGNATURE.issubset(names):
        log('  not a Treadmill Link file (developer fields: %s) - skipping'
            % (sorted(names) or 'none'))
        return False

    os.makedirs(ARCHIVE_DIR, exist_ok=True)
    src = os.path.join(ARCHIVE_DIR, '%s-original.fit' % activity_id)
    dst = os.path.join(ARCHIVE_DIR, '%s-corrected.fit' % activity_id)

    with open(src, 'wb') as fh:
        fh.write(original)

    fitfix.main(src, dst)

    if not apply_changes:
        log('  dry run - would delete %s and upload %s' % (activity_id, dst))
        return False

    log('  deleting original activity %s' % activity_id)
    client.delete_activity(activity_id)

    log('  uploading corrected file')
    result = client.upload_activity(dst)
    log('  upload result: %s' % result)

    state['processed'].append(activity_id)
    save_state(state)

    return True


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--apply', action='store_true',
                    help='actually replace activities (default is a dry run)')
    ap.add_argument('--limit', type=int, default=10,
                    help='how many recent activities to examine')
    ap.add_argument('--file', help='fix a local FIT and exit, no Garmin login')
    args = ap.parse_args()

    if args.file:
        out = os.path.splitext(args.file)[0] + '-corrected.fit'
        fitfix.main(args.file, out)
        return 0

    state = load_state()
    client = connect()
    log('logged in as %s' % client.get_full_name())

    found = candidates(client, args.limit)
    log('%d candidate activity(ies)' % len(found))

    changed = 0

    for act in found:
        try:
            if process(client, act, state, args.apply):
                changed += 1
        except Exception as exc:
            log('  FAILED: %s' % exc)

    log('done, %d activity(ies) replaced' % changed)

    if not args.apply and found:
        log('this was a dry run - re-run with --apply to make the changes')

    return 0


if __name__ == '__main__':
    sys.exit(main())
