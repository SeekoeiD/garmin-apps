"""Minimal FIT read/write support.

Only what is needed to round-trip a Garmin activity file and edit a few
fields: parse every message into raw field bytes, let the caller change them,
then re-emit a valid file. Messages the caller does not touch are copied
through byte for byte.
"""
import struct
from collections import OrderedDict

# base type id -> (name, element size, struct code, invalid value)
BASE_TYPES = {
    0x00: ('enum', 1, 'B', 0xFF), 0x01: ('sint8', 1, 'b', 0x7F),
    0x02: ('uint8', 1, 'B', 0xFF), 0x83: ('sint16', 2, 'h', 0x7FFF),
    0x84: ('uint16', 2, 'H', 0xFFFF), 0x85: ('sint32', 4, 'i', 0x7FFFFFFF),
    0x86: ('uint32', 4, 'I', 0xFFFFFFFF), 0x07: ('string', 1, 's', 0x00),
    0x88: ('float32', 4, 'f', None), 0x89: ('float64', 8, 'd', None),
    0x0A: ('uint8z', 1, 'B', 0x00), 0x8B: ('uint16z', 2, 'H', 0x0000),
    0x8C: ('uint32z', 4, 'I', 0x00000000), 0x0D: ('byte', 1, 'B', 0xFF),
    0x8E: ('sint64', 8, 'q', None), 0x8F: ('uint64', 8, 'Q', None),
    0x90: ('uint64z', 8, 'Q', None),
}

CRC_TABLE = [0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
             0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400]

MESG_RECORD = 20
MESG_SESSION = 18
MESG_LAP = 19
MESG_FIELD_DESCRIPTION = 206


def fit_crc(data, crc=0):
    for b in data:
        tmp = CRC_TABLE[crc & 0xF]
        crc = ((crc >> 4) & 0x0FFF) ^ tmp ^ CRC_TABLE[b & 0xF]
        tmp = CRC_TABLE[crc & 0xF]
        crc = ((crc >> 4) & 0x0FFF) ^ tmp ^ CRC_TABLE[(b >> 4) & 0xF]

    return crc


class Message(object):
    """One data message: raw field bytes keyed by field number."""

    def __init__(self, gnum, fields, devs):
        self.gnum = gnum
        self.fields = fields      # fnum -> [base_type, raw bytes]
        self.devs = devs          # dnum -> [dev_index, raw bytes]

    def signature(self):
        f = tuple((n, v[0], len(v[1])) for n, v in self.fields.items())
        d = tuple((n, v[0], len(v[1])) for n, v in self.devs.items())

        return (self.gnum, f, d)

    def get(self, fnum):
        """Decode a scalar field, or None if absent/invalid."""
        if fnum not in self.fields:
            return None

        base, raw = self.fields[fnum]

        return decode_scalar(base, raw)

    def get_dev(self, dnum):
        if dnum not in self.devs:
            return None

        _, raw = self.devs[dnum]

        # Developer fields in this app are float32 or uint16.
        if len(raw) == 4:
            return struct.unpack('<f', raw)[0]

        if len(raw) == 2:
            v = struct.unpack('<H', raw)[0]

            return None if v == 0xFFFF else v

        return None

    def set(self, fnum, base_type, value):
        """Set a native field, adding it to the message if not present."""
        self.fields[fnum] = [base_type, encode_scalar(base_type, value)]


def decode_scalar(base, raw):
    name, esize, fmt, invalid = BASE_TYPES.get(base, ('byte', 1, 'B', 0xFF))

    if name == 'string' or len(raw) < esize:
        return None

    v = struct.unpack('<' + fmt, raw[:esize])[0]

    if invalid is not None and v == invalid:
        return None

    return v


def encode_scalar(base, value):
    name, esize, fmt, invalid = BASE_TYPES[base]

    if value is None:
        if invalid is None:
            return b'\x00' * esize

        return struct.pack('<' + fmt, invalid)

    if fmt in 'fd':
        return struct.pack('<' + fmt, float(value))

    v = int(round(value))
    lo, hi = type_range(fmt)
    v = max(lo, min(hi, v))

    return struct.pack('<' + fmt, v)


def type_range(fmt):
    signed = fmt.islower()
    bits = {'b': 8, 'B': 8, 'h': 16, 'H': 16, 'i': 32, 'I': 32,
            'q': 64, 'Q': 64}[fmt]

    if signed:
        return (-(1 << (bits - 1)), (1 << (bits - 1)) - 1)

    return (0, (1 << bits) - 1)


def parse(buf):
    """Return (header_bytes, [Message]) for a FIT file."""
    hdr_size = buf[0]
    data_size = struct.unpack('<I', buf[4:8])[0]

    if buf[8:12] != b'.FIT':
        raise ValueError('not a FIT file')

    pos = hdr_size
    end = hdr_size + data_size
    defs = {}
    messages = []

    while pos < end:
        h = buf[pos]
        pos += 1

        if h & 0x80:
            raise ValueError('compressed timestamp headers are not supported')

        local = h & 0x0F
        is_def = bool(h & 0x40)
        has_dev = bool(h & 0x20)

        if is_def:
            pos += 1
            arch = buf[pos]; pos += 1

            if arch == 1:
                raise ValueError('big-endian messages are not supported')

            gnum = struct.unpack('<H', buf[pos:pos + 2])[0]; pos += 2
            nfields = buf[pos]; pos += 1
            fields = []

            for _ in range(nfields):
                fields.append((buf[pos], buf[pos + 1], buf[pos + 2]))
                pos += 3

            devs = []

            if has_dev:
                ndev = buf[pos]; pos += 1

                for _ in range(ndev):
                    devs.append((buf[pos], buf[pos + 1], buf[pos + 2]))
                    pos += 3

            defs[local] = (gnum, fields, devs)
            continue

        if local not in defs:
            raise ValueError('data message for undefined local type %d' % local)

        gnum, fields, devs = defs[local]
        fvals = OrderedDict()

        for fnum, fsize, ftype in fields:
            fvals[fnum] = [ftype, buf[pos:pos + fsize]]
            pos += fsize

        dvals = OrderedDict()

        for dnum, dsize, didx in devs:
            dvals[dnum] = [didx, buf[pos:pos + dsize]]
            pos += dsize

        messages.append(Message(gnum, fvals, dvals))

    return buf[:hdr_size], messages


def build(header, messages):
    """Re-emit a FIT file, allocating local message types as layouts change."""
    out = bytearray()
    assigned = {}          # signature -> local id
    owner = {}             # local id -> signature
    order = []             # local ids, least recently used first

    for m in messages:
        sig = m.signature()
        local = assigned.get(sig)

        if local is None:
            if len(assigned) < 16:
                local = len(assigned)
            else:
                # Evict the least recently used slot and redefine it.
                local = order[0]
                del assigned[owner[local]]

            assigned[sig] = local
            owner[local] = sig
            out += encode_definition(local, m)

        if local in order:
            order.remove(local)

        order.append(local)

        header_byte = local | (0x20 if m.devs else 0x00)
        out.append(header_byte)

        for _, raw in m.fields.values():
            out += raw

        for _, raw in m.devs.values():
            out += raw

    new_header = bytearray(header)
    struct.pack_into('<I', new_header, 4, len(out))

    if len(new_header) >= 14:
        struct.pack_into('<H', new_header, 12, fit_crc(bytes(new_header[:12])))

    body = bytes(new_header) + bytes(out)

    return body + struct.pack('<H', fit_crc(body))


def encode_definition(local, m):
    out = bytearray()
    out.append(0x40 | local | (0x20 if m.devs else 0x00))
    out.append(0)                                   # reserved
    out.append(0)                                   # little endian
    out += struct.pack('<H', m.gnum)
    out.append(len(m.fields))

    for fnum, (base, raw) in m.fields.items():
        out += bytes([fnum, len(raw), base])

    if m.devs:
        out.append(len(m.devs))

        for dnum, (didx, raw) in m.devs.items():
            out += bytes([dnum, len(raw), didx])

    return bytes(out)
