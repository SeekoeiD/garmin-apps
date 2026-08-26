"""Read-only check of the most recent activities in Garmin Connect.

Used to confirm a corrected upload actually landed with real numbers.

    python check_latest.py [count]
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from treadmill_sync import connect


def main(count):
    client = connect()
    print('logged in as %s\n' % client.get_full_name())

    for act in client.get_activities(0, count):
        type_key = (act.get('activityType') or {}).get('typeKey', '?')
        print('%s  %s' % (act.get('startTimeLocal', '?'), act.get('activityName', '')))
        print('  id            : %s   type: %s' % (act.get('activityId'), type_key))
        print('  distance      : %s m' % fmt(act.get('distance')))
        print('  elevation gain: %s m' % fmt(act.get('elevationGain')))
        print('  avg / max spd : %s / %s m/s'
              % (fmt(act.get('averageSpeed')), fmt(act.get('maxSpeed'))))
        print('  duration      : %s s   avg HR: %s'
              % (fmt(act.get('duration')), fmt(act.get('averageHR'))))
        print()


def fmt(v):
    if v is None:
        return '-'

    if isinstance(v, float):
        return '%.2f' % v

    return str(v)


if __name__ == '__main__':
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 3)
