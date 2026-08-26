"""Rewrite a Treadmill Link FIT so Garmin Connect and Strava can read it.

The watch app records everything correctly, but as developer fields with a
native_field_num override - and neither Garmin Connect nor Strava honours that
override. This moves the values into genuine native FIT fields:

    record  : distance, speed, enhanced_speed, altitude, enhanced_altitude, grade
    lap     : total_distance, total_ascent, total_descent, avg/max speed
    session : total_distance, total_ascent, total_descent, avg/max speed

The developer fields that had a native mapping are dropped afterwards, so the
data appears once rather than twice. The unmapped ones (incline,
treadmill_ascent) are kept, since nothing native carries them.

    python fitfix.py in.fit out.fit
"""
import sys

from fitlib import (MESG_FIELD_DESCRIPTION, MESG_LAP, MESG_RECORD, MESG_SESSION,
                    build, parse)

UINT16, UINT32, SINT16 = 0x84, 0x86, 0x83

# field_description field numbers
FD_FIELD_NUM = 1
FD_NAME = 3
FD_NATIVE_FIELD_NUM = 15

ALT_OFFSET, ALT_SCALE = 500.0, 5.0
SPEED_SCALE = 1000.0
DIST_SCALE = 100.0
GRADE_SCALE = 100.0


def field_name(msg):
    if FD_NAME not in msg.fields:
        return None

    return msg.fields[FD_NAME][1].split(b'\x00')[0].decode('utf-8', 'replace')


def main(src, dst):
    with open(src, 'rb') as fh:
        source = fh.read()

    header, messages = parse(source)

    # Learn the developer field layout from the file rather than assuming it.
    by_name = {}
    mapped_dev_nums = set()

    for m in messages:
        if m.gnum != MESG_FIELD_DESCRIPTION:
            continue

        name = field_name(m)
        num = m.get(FD_FIELD_NUM)

        if name is None or num is None:
            continue

        by_name[name] = num

        if m.get(FD_NATIVE_FIELD_NUM) is not None:
            mapped_dev_nums.add(num)

    print('developer fields   : %s' % by_name)
    print('had native mapping : %s' % sorted(mapped_dev_nums))

    need = ('altitude', 'speed', 'distance', 'grade')
    missing = [n for n in need if n not in by_name]

    if missing:
        raise SystemExit('file is missing developer fields: %s' % missing)

    d_alt = by_name['altitude']
    d_speed = by_name['speed']
    d_dist = by_name['distance']
    d_grade = by_name['grade']

    total_distance = 0.0
    max_speed = 0.0
    speed_sum = 0.0
    speed_n = 0
    records = 0

    for m in messages:
        if m.gnum != MESG_RECORD:
            continue

        alt = m.get_dev(d_alt)
        speed = m.get_dev(d_speed)
        dist = m.get_dev(d_dist)
        grade = m.get_dev(d_grade)
        records += 1

        if alt is not None:
            raw = (alt + ALT_OFFSET) * ALT_SCALE
            m.set(2, UINT16, raw)
            m.set(78, UINT32, raw)

        if speed is not None:
            m.set(6, UINT16, speed * SPEED_SCALE)
            m.set(73, UINT32, speed * SPEED_SCALE)
            max_speed = max(max_speed, speed)
            speed_sum += speed
            speed_n += 1

        if dist is not None:
            m.set(5, UINT32, dist * DIST_SCALE)
            total_distance = max(total_distance, dist)

        if grade is not None:
            m.set(9, SINT16, grade * GRADE_SCALE)

    avg_speed = (speed_sum / speed_n) if speed_n else 0.0

    ascent = pick(messages, MESG_SESSION, by_name.get('total_ascent'))
    descent = pick(messages, MESG_SESSION, by_name.get('total_descent'))

    if ascent is None:
        ascent = pick(messages, MESG_SESSION, by_name.get('treadmill_ascent')) or 0

    if descent is None:
        descent = 0

    print('records            : %d' % records)
    print('total distance     : %.1f m' % total_distance)
    print('avg / max speed    : %.3f / %.3f m/s' % (avg_speed, max_speed))
    print('ascent / descent   : %d / %d m' % (ascent, descent))

    for m in messages:
        if m.gnum == MESG_SESSION:
            m.set(9, UINT32, total_distance * DIST_SCALE)
            m.set(14, UINT16, avg_speed * SPEED_SCALE)
            m.set(15, UINT16, max_speed * SPEED_SCALE)
            m.set(22, UINT16, ascent)
            m.set(23, UINT16, descent)
            m.set(124, UINT32, avg_speed * SPEED_SCALE)
            m.set(125, UINT32, max_speed * SPEED_SCALE)
        elif m.gnum == MESG_LAP:
            m.set(9, UINT32, total_distance * DIST_SCALE)
            m.set(13, UINT16, avg_speed * SPEED_SCALE)
            m.set(14, UINT16, max_speed * SPEED_SCALE)
            m.set(21, UINT16, ascent)
            m.set(22, UINT16, descent)
            m.set(110, UINT32, avg_speed * SPEED_SCALE)
            m.set(111, UINT32, max_speed * SPEED_SCALE)

    # Drop the developer fields whose data is now native, and their
    # descriptions, so Garmin Connect does not draw every trace twice.
    kept = []

    for m in messages:
        if m.gnum == MESG_FIELD_DESCRIPTION and m.get(FD_FIELD_NUM) in mapped_dev_nums:
            continue

        for num in list(m.devs.keys()):
            if num in mapped_dev_nums:
                del m.devs[num]

        kept.append(m)

    out = build(header, kept)

    with open(dst, 'wb') as fh:
        fh.write(out)

    print('wrote %s (%d bytes, was %d)' % (dst, len(out), len(source)))


def pick(messages, gnum, dev_num):
    """First developer value for dev_num on any message of type gnum."""
    if dev_num is None:
        return None

    for m in messages:
        if m.gnum == gnum:
            v = m.get_dev(dev_num)

            if v is not None:
                return int(v)

    return None


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)

    main(sys.argv[1], sys.argv[2])
