"""Sanity-check a corrected FIT: CRCs, sizes, and the native fields we injected.

    python fitverify.py file.fit
"""
import struct
import sys

from fitlib import fit_crc, parse


def main(path):
    buf = open(path, 'rb').read()
    hdr_size = buf[0]
    data_size = struct.unpack('<I', buf[4:8])[0]
    hdr_stored = struct.unpack('<H', buf[12:14])[0]
    hdr_calc = fit_crc(buf[:12])
    file_stored = struct.unpack('<H', buf[-2:])[0]
    file_calc = fit_crc(buf[:-2])
    total = hdr_size + data_size + 2

    ok = lambda a, b: 'OK' if a == b else 'MISMATCH'

    print('header CRC : stored %04X calc %04X  %s' % (hdr_stored, hdr_calc, ok(hdr_stored, hdr_calc)))
    print('file   CRC : stored %04X calc %04X  %s' % (file_stored, file_calc, ok(file_stored, file_calc)))
    print('size       : %d expected, %d actual  %s' % (total, len(buf), ok(total, len(buf))))

    header, msgs = parse(buf)
    print('parsed %d messages' % len(msgs))

    recs = [m for m in msgs if m.gnum == 20]
    print('\nrecords: %d' % len(recs))

    for idx in (0, len(recs) // 2, len(recs) - 1):
        m = recs[idx]
        alt, spd, dist, grade = m.get(78), m.get(73), m.get(5), m.get(9)
        print('  rec[%2d] alt=%7.1fm speed=%5.2fm/s dist=%7.1fm grade=%5.1f%% hr=%s dev_left=%s' % (
            idx,
            (alt / 5.0 - 500) if alt is not None else -1,
            (spd / 1000.0) if spd is not None else -1,
            (dist / 100.0) if dist is not None else -1,
            (grade / 100.0) if grade is not None else -1,
            m.get(3), sorted(m.devs.keys())))

    ses = [m for m in msgs if m.gnum == 18][0]
    print('\nsession:')
    print('  total_distance = %.1f m' % (ses.get(9) / 100.0))
    print('  total_ascent   = %s m' % ses.get(22))
    print('  total_descent  = %s m' % ses.get(23))
    print('  avg_speed      = %.2f km/h' % (ses.get(14) / 1000.0 * 3.6))
    print('  max_speed      = %.2f km/h' % (ses.get(15) / 1000.0 * 3.6))
    print('  avg_hr         = %s   sport/sub = %s/%s' % (ses.get(16), ses.get(5), ses.get(6)))
    print('  dev fields     = %s' % sorted(ses.devs.keys()))

    lap = [m for m in msgs if m.gnum == 19][0]
    print('\nlap: dist=%.1fm ascent=%sm dev_left=%s'
          % (lap.get(9) / 100.0, lap.get(21), sorted(lap.devs.keys())))

    fds = [m for m in msgs if m.gnum == 206]
    print('\nremaining developer fields: %s'
          % [m.fields[3][1].split(b'\x00')[0].decode() for m in fds])


if __name__ == '__main__':
    main(sys.argv[1])
