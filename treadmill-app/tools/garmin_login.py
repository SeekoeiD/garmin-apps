"""One-time Garmin Connect login. Run this yourself; the sync job then runs
unattended off the saved tokens and never needs the password again.

    setx GARMIN_EMAIL you@example.com
    $env:GARMIN_PASSWORD = Read-Host -AsSecureString ...   (see README)
    python garmin_login.py

Credentials are read from the environment and never written to disk or logged.
Only the resulting OAuth tokens are stored, under tokens/ next to this script.
"""
import os
import sys

from garminconnect import Garmin

TOKEN_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'tokens')


def main():
    email = os.environ.get('GARMIN_EMAIL')
    password = os.environ.get('GARMIN_PASSWORD')

    if not email or not password:
        raise SystemExit(
            'Set GARMIN_EMAIL and GARMIN_PASSWORD in the environment first.\n'
            'They are read once here and never stored.')

    os.makedirs(TOKEN_DIR, exist_ok=True)

    print('logging in as %s ...' % email)

    # Passing tokenstore does both halves: it would load existing tokens if any
    # were there, and persists the new ones after a credential login. There is
    # no separate dump step in this version of the library.
    client = Garmin(email=email, password=password, prompt_mfa=prompt_mfa)
    client.login(tokenstore=TOKEN_DIR)

    print('tokens saved to %s' % TOKEN_DIR)
    print('logged in as: %s' % client.get_full_name())
    print('\nYou can now clear GARMIN_PASSWORD from your environment.')


def prompt_mfa():
    return input('Garmin sent an MFA code - enter it: ').strip()


if __name__ == '__main__':
    sys.exit(main())
