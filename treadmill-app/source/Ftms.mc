using Toybox.Lang;

//! FTMS (Fitness Machine Service) constants and packet decoding.
//!
//! Everything here is per Bluetooth SIG "Fitness Machine Service" v1.0,
//! section 4.9 (Treadmill Data characteristic). The treadmill is assumed to
//! speak stock FTMS because that is what Zwift consumes; the decoder is
//! deliberately defensive so an unexpected flag layout truncates the parse
//! instead of throwing.
module Ftms {

    const SERVICE_UUID   = "00001826-0000-1000-8000-00805F9B34FB";
    const TREADMILL_DATA = "00002ACD-0000-1000-8000-00805F9B34FB";
    const MACHINE_FEATURE = "00002ACC-0000-1000-8000-00805F9B34FB";

    // Treadmill Data flags (uint16, little endian, first two bytes).
    const F_MORE_DATA      = 0x0001; // bit clear => Instantaneous Speed present
    const F_AVG_SPEED      = 0x0002;
    const F_TOTAL_DISTANCE = 0x0004;
    const F_INCLINATION    = 0x0008;
    const F_ELEVATION_GAIN = 0x0010;
    const F_INST_PACE      = 0x0020;
    const F_AVG_PACE       = 0x0040;
    const F_ENERGY         = 0x0080;
    const F_HEART_RATE     = 0x0100;
    const F_MET            = 0x0200;
    const F_ELAPSED_TIME   = 0x0400;
    const F_REMAINING_TIME = 0x0800;
    const F_FORCE_POWER    = 0x1000;

    //! Decode a Treadmill Data notification into a dictionary of symbols.
    //!
    //! Only keys that were actually present in the packet are set, so callers
    //! must check with hasKey() rather than assuming a full payload. A packet
    //! that runs out of bytes mid-field returns everything decoded so far.
    function parseTreadmillData(data as Lang.ByteArray or Null) as Lang.Dictionary {
        var out = {};

        if (data == null || data.size() < 2) {
            return out;
        }

        var flags = u16(data, 0);

        if (flags == null) {
            return out;
        }

        out[:flags] = flags;
        var i = 2;

        // Instantaneous speed, uint16, 0.01 km/h. Present when More Data is 0.
        if ((flags & F_MORE_DATA) == 0) {
            var v = u16(data, i);

            if (v == null) { return out; }

            out[:speedMps] = v * 0.01 / 3.6;
            i += 2;
        }

        if ((flags & F_AVG_SPEED) != 0) {
            var v = u16(data, i);

            if (v == null) { return out; }

            out[:avgSpeedMps] = v * 0.01 / 3.6;
            i += 2;
        }

        // Total distance, uint24, metres.
        if ((flags & F_TOTAL_DISTANCE) != 0) {
            var v = u24(data, i);

            if (v == null) { return out; }

            out[:distanceM] = v;
            i += 3;
        }

        // Inclination (sint16, 0.1 %) then ramp angle (sint16, 0.1 deg).
        if ((flags & F_INCLINATION) != 0) {
            var inc = s16(data, i);
            var ramp = s16(data, i + 2);

            if (inc == null) { return out; }

            out[:inclinePct] = inc * 0.1;

            if (ramp != null) {
                out[:rampDeg] = ramp * 0.1;
            }

            i += 4;
        }

        // Positive then negative elevation gain, uint16, 0.1 m.
        if ((flags & F_ELEVATION_GAIN) != 0) {
            var pos = u16(data, i);
            var neg = u16(data, i + 2);

            if (pos == null) { return out; }

            out[:elevGainM] = pos * 0.1;

            if (neg != null) {
                out[:elevLossM] = neg * 0.1;
            }

            i += 4;
        }

        if ((flags & F_INST_PACE) != 0) {
            i += 1;
        }

        if ((flags & F_AVG_PACE) != 0) {
            i += 1;
        }

        // Total energy (uint16 kcal), per hour (uint16), per minute (uint8).
        if ((flags & F_ENERGY) != 0) {
            var kcal = u16(data, i);

            if (kcal != null && kcal != 0xFFFF) {
                out[:kcal] = kcal;
            }

            i += 5;
        }

        if ((flags & F_HEART_RATE) != 0) {
            var hr = u8(data, i);

            if (hr != null && hr > 0) {
                out[:heartRate] = hr;
            }

            i += 1;
        }

        if ((flags & F_MET) != 0) {
            i += 1;
        }

        if ((flags & F_ELAPSED_TIME) != 0) {
            var t = u16(data, i);

            if (t != null) {
                out[:elapsedSec] = t;
            }

            i += 2;
        }

        return out;
    }

    function u8(d as Lang.ByteArray, i as Lang.Number) as Lang.Number or Null {
        if (i < 0 || i + 1 > d.size()) { return null; }

        return d[i] & 0xFF;
    }

    function u16(d as Lang.ByteArray, i as Lang.Number) as Lang.Number or Null {
        if (i < 0 || i + 2 > d.size()) { return null; }

        return d.decodeNumber(
            Lang.NUMBER_FORMAT_UINT16,
            {:offset => i, :endianness => Lang.ENDIAN_LITTLE}
        );
    }

    function s16(d as Lang.ByteArray, i as Lang.Number) as Lang.Number or Null {
        if (i < 0 || i + 2 > d.size()) { return null; }

        return d.decodeNumber(
            Lang.NUMBER_FORMAT_SINT16,
            {:offset => i, :endianness => Lang.ENDIAN_LITTLE}
        );
    }

    function u24(d as Lang.ByteArray, i as Lang.Number) as Lang.Number or Null {
        if (i < 0 || i + 3 > d.size()) { return null; }

        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8) | ((d[i + 2] & 0xFF) << 16);
    }

    //! Hex dump used by the debug page, so an unexpected packet layout can be
    //! read straight off the watch instead of guessed at.
    function toHex(d as Lang.ByteArray or Null) as Lang.String {
        if (d == null) {
            return "-";
        }

        var s = "";
        var n = d.size();

        if (n > 20) { n = 20; }

        for (var i = 0; i < n; i += 1) {
            var b = d[i] & 0xFF;
            s += (b < 16 ? "0" : "") + b.format("%X");
        }

        return s;
    }
}
