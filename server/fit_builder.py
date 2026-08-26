"""Turn 1 Hz treadmill samples into a Garmin FIT activity file.

The watch only reports speed, incline and heart rate; distance, altitude and
climb totals are integrated here so nothing depends on Connect IQ's recording
(:nativeNum overrides are ignored by every consumer, which is why the watch no
longer writes a FIT at all).

No Flask, no network: importable and testable on its own.
"""
import math
import os
import struct
import sys
from collections import OrderedDict

try:
    from fitlib import Message, build
except ImportError:
    sys.path.insert(0, os.path.join(
        os.path.dirname(os.path.abspath(__file__)), '..', 'tools'))
    from fitlib import Message, build

GARMIN_EPOCH = 631065600

ENUM = 0x00
UINT8 = 0x02
SINT16 = 0x83
UINT16 = 0x84
UINT32 = 0x86
UINT32Z = 0x8C

MESG_FILE_ID = 0
MESG_RECORD = 20
MESG_EVENT = 21
MESG_LAP = 19
MESG_SESSION = 18
MESG_ACTIVITY = 34

DEFAULT_ALTITUDE = 100.0
DEFAULT_TZ_OFFSET = 7200
MANUFACTURER_DEVELOPMENT = 255
PRODUCT = 1
SERIAL_NUMBER = 12345678

# size, protocol 2.0, profile version, data size placeholder, tag, CRC slot.
# build() rewrites the data size and both CRCs.
HEADER = (bytes([14, 0x20]) + struct.pack('<H', 21184) + b'\x00' * 4
          + b'.FIT' + b'\x00' * 2)


def garmin_ts(unix_seconds):
    return int(unix_seconds) - GARMIN_EPOCH


def message(gnum):
    return Message(gnum, OrderedDict(), OrderedDict())


def clean_sample(raw):
    """One [speed_mps, incline_pct, hr] sample, tolerant of nulls and shorts."""
    values = list(raw) + [0, 0, 0]
    speed = float(values[0] or 0.0)
    incline = float(values[1] or 0.0)
    hr = values[2]

    if hr in (None, 0):
        hr = None
    else:
        hr = int(hr)

    return max(0.0, speed), incline, hr


def derive(samples, start_altitude=DEFAULT_ALTITUDE, dt=1.0):
    """Integrate the samples into per-record points plus whole-run totals."""
    points = []
    distance = 0.0
    altitude = float(start_altitude)
    ascent = 0.0
    descent = 0.0
    max_speed = 0.0
    hr_sum = 0
    hr_count = 0
    max_hr = 0

    for raw in samples:
        speed, incline, hr = clean_sample(raw)
        grade = incline / 100.0
        rise = speed * grade / math.sqrt(1.0 + grade * grade) * dt
        distance += speed * dt
        altitude += rise

        if rise > 0.0:
            ascent += rise
        else:
            descent -= rise

        max_speed = max(max_speed, speed)

        if hr is not None:
            hr_sum += hr
            hr_count += 1
            max_hr = max(max_hr, hr)

        points.append((distance, speed, altitude, incline, hr))

    seconds = len(points) * dt

    totals = {
        'records': len(points),
        'seconds': seconds,
        'distance': distance,
        'ascent': ascent,
        'descent': descent,
        'max_speed': max_speed,
        'avg_speed': distance / seconds if seconds else 0.0,
        'avg_hr': int(round(hr_sum / hr_count)) if hr_count else None,
        'max_hr': max_hr or None,
        'start_altitude': float(start_altitude),
        'end_altitude': altitude,
    }

    return points, totals


def file_id_message(start_ts):
    m = message(MESG_FILE_ID)
    m.set(0, ENUM, 4)
    m.set(1, UINT16, MANUFACTURER_DEVELOPMENT)
    m.set(2, UINT16, PRODUCT)
    m.set(3, UINT32Z, SERIAL_NUMBER)
    m.set(4, UINT32, start_ts)

    return m


def timer_event(ts, event_type):
    m = message(MESG_EVENT)
    m.set(253, UINT32, ts)
    m.set(0, ENUM, 0)
    m.set(1, ENUM, event_type)
    m.set(4, UINT8, 0)

    return m


def record_message(ts, point):
    distance, speed, altitude, incline, hr = point
    m = message(MESG_RECORD)
    m.set(253, UINT32, ts)
    m.set(5, UINT32, distance * 100.0)
    m.set(6, UINT16, speed * 1000.0)
    m.set(73, UINT32, speed * 1000.0)
    m.set(2, UINT16, (altitude + 500.0) * 5.0)
    m.set(78, UINT32, (altitude + 500.0) * 5.0)
    m.set(9, SINT16, incline * 100.0)
    m.set(3, UINT8, hr)

    return m


def lap_message(start_ts, end_ts, totals):
    m = message(MESG_LAP)
    m.set(253, UINT32, end_ts)
    m.set(254, UINT16, 0)
    m.set(2, UINT32, start_ts)
    m.set(0, ENUM, 9)
    m.set(1, ENUM, 1)
    m.set(7, UINT32, totals['seconds'] * 1000.0)
    m.set(8, UINT32, totals['seconds'] * 1000.0)
    m.set(9, UINT32, totals['distance'] * 100.0)
    m.set(13, UINT16, totals['avg_speed'] * 1000.0)
    m.set(14, UINT16, totals['max_speed'] * 1000.0)
    m.set(15, UINT8, totals['avg_hr'])
    m.set(16, UINT8, totals['max_hr'])
    m.set(21, UINT16, totals['ascent'])
    m.set(22, UINT16, totals['descent'])
    m.set(110, UINT32, totals['avg_speed'] * 1000.0)
    m.set(111, UINT32, totals['max_speed'] * 1000.0)

    return m


def session_message(start_ts, end_ts, totals):
    m = message(MESG_SESSION)
    m.set(253, UINT32, end_ts)
    m.set(254, UINT16, 0)
    m.set(2, UINT32, start_ts)
    m.set(0, ENUM, 8)
    m.set(1, ENUM, 1)
    m.set(5, ENUM, 1)
    m.set(6, ENUM, 1)
    m.set(7, UINT32, totals['seconds'] * 1000.0)
    m.set(8, UINT32, totals['seconds'] * 1000.0)
    m.set(9, UINT32, totals['distance'] * 100.0)
    m.set(14, UINT16, totals['avg_speed'] * 1000.0)
    m.set(15, UINT16, totals['max_speed'] * 1000.0)
    m.set(16, UINT8, totals['avg_hr'])
    m.set(17, UINT8, totals['max_hr'])
    m.set(22, UINT16, totals['ascent'])
    m.set(23, UINT16, totals['descent'])
    m.set(26, UINT16, 1)
    m.set(124, UINT32, totals['avg_speed'] * 1000.0)
    m.set(125, UINT32, totals['max_speed'] * 1000.0)

    return m


def activity_message(end_ts, totals, tz_offset):
    m = message(MESG_ACTIVITY)
    m.set(253, UINT32, end_ts)
    m.set(0, UINT32, totals['seconds'] * 1000.0)
    m.set(1, UINT16, 1)
    m.set(2, ENUM, 0)
    m.set(3, ENUM, 26)
    m.set(4, ENUM, 1)
    m.set(5, UINT32, end_ts + tz_offset)

    return m


def tz_offset_seconds():
    try:
        return int(os.environ.get('TZ_OFFSET_SECONDS', DEFAULT_TZ_OFFSET))
    except ValueError:
        return DEFAULT_TZ_OFFSET


def build_fit(samples, start_unix, start_altitude=DEFAULT_ALTITUDE,
              tz_offset=None, dt=1.0):
    """Return (fit_bytes, totals) for the run described by samples."""
    if not samples:
        raise ValueError('no samples')

    if tz_offset is None:
        tz_offset = tz_offset_seconds()

    points, totals = derive(samples, start_altitude, dt)
    start_ts = garmin_ts(start_unix)
    end_ts = start_ts + int(round(totals['seconds']))

    messages = [file_id_message(start_ts), timer_event(start_ts, 0)]

    for i, point in enumerate(points):
        messages.append(record_message(start_ts + int(round((i + 1) * dt)), point))

    messages.append(timer_event(end_ts, 4))
    messages.append(lap_message(start_ts, end_ts, totals))
    messages.append(session_message(start_ts, end_ts, totals))
    messages.append(activity_message(end_ts, totals, tz_offset))

    return build(HEADER, messages), totals
