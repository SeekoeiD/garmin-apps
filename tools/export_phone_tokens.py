"""Export the Garmin OAuth tokens for pasting into the Android companion app.

Writes the token JSON to phone_tokens.json next to this script (gitignored).
Open the file, copy its whole content, and paste it into the companion app's
"Garmin tokens" field. Delete the file afterwards.

The refresh token ROTATES: after the phone starts using these tokens, the
copy on this PC will eventually stop refreshing. That is fine - the phone
then owns the tokens. Re-run garmin_login.py here if PC-side tools are
needed again later.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'phone_tokens.json')


def main():
    sys.path.insert(0, HERE)
    from treadmill_sync import connect

    client = connect()

    with open(OUT, 'w') as fh:
        fh.write(client.client.dumps())

    print('wrote %s' % OUT)
    print('Copy its content into the companion app, then delete the file.')


if __name__ == '__main__':
    main()
