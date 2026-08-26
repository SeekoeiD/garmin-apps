package za.co.dt.edgemusiccontrol

import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Minimal FIT encoding: base types, CRC-16, definition messages and local-type allocation.
 *
 * A faithful port of tools/fitlib.py from the garmin-treadmill project, which is what produced the
 * reference file the unit tests compare against byte for byte. Anything that changes here changes
 * the bytes Garmin Connect sees, so keep the arithmetic identical to the Python — in particular the
 * rounding: Python's round() breaks ties to even, and kotlin.math.round (Math.rint) does the same.
 *
 * Developer fields are parsed but never written: this app only ever builds native activity files.
 */
object FitEncoder {

    const val ENUM = 0x00
    const val SINT8 = 0x01
    const val UINT8 = 0x02
    const val SINT16 = 0x83
    const val UINT16 = 0x84
    const val SINT32 = 0x85
    const val UINT32 = 0x86
    const val STRING = 0x07
    const val FLOAT32 = 0x88
    const val FLOAT64 = 0x89
    const val UINT8Z = 0x0A
    const val UINT16Z = 0x8B
    const val UINT32Z = 0x8C
    const val BYTE = 0x0D
    const val SINT64 = 0x8E
    const val UINT64 = 0x8F
    const val UINT64Z = 0x90

    /** The FIT spec allows local message types 0..15; past that a slot has to be redefined. */
    private const val MAX_LOCAL_TYPES = 16

    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
    )

    /** Element size in bytes, signedness, whether it is IEEE float, and the "no value" encoding. */
    private class BaseType(
        val size: Int,
        val signed: Boolean,
        val isFloat: Boolean,
        val invalid: Long?
    )

    private val BASE_TYPES = mapOf(
        ENUM to BaseType(1, false, false, 0xFFL),
        SINT8 to BaseType(1, true, false, 0x7FL),
        UINT8 to BaseType(1, false, false, 0xFFL),
        SINT16 to BaseType(2, true, false, 0x7FFFL),
        UINT16 to BaseType(2, false, false, 0xFFFFL),
        SINT32 to BaseType(4, true, false, 0x7FFFFFFFL),
        UINT32 to BaseType(4, false, false, 0xFFFFFFFFL),
        STRING to BaseType(1, false, false, 0x00L),
        FLOAT32 to BaseType(4, false, true, null),
        FLOAT64 to BaseType(8, false, true, null),
        UINT8Z to BaseType(1, false, false, 0x00L),
        UINT16Z to BaseType(2, false, false, 0x0000L),
        UINT32Z to BaseType(4, false, false, 0x00000000L),
        BYTE to BaseType(1, false, false, 0xFFL),
        SINT64 to BaseType(8, true, false, null),
        UINT64 to BaseType(8, false, false, null),
        UINT64Z to BaseType(8, false, false, null)
    )

    /** One field of a message: the raw little-endian bytes plus the base type they were packed as. */
    class Field(val baseType: Int, val raw: ByteArray)

    /** One data message. Field insertion order is the on-disk order, so keep it stable. */
    class Message(val globalNum: Int) {

        val fields = LinkedHashMap<Int, Field>()

        fun set(fieldNum: Int, baseType: Int, value: Double?) {
            fields[fieldNum] = Field(baseType, encodeScalar(baseType, value))
        }

        // No Long overload: an integer literal would then be ambiguous. Timestamps are passed as
        // Double, which is lossless well past any date a FIT file can express.
        fun set(fieldNum: Int, baseType: Int, value: Int?) {
            set(fieldNum, baseType, value?.toDouble())
        }

        /** Messages sharing a signature share a local message type, so they share one definition. */
        fun signature(): String {
            val builder = StringBuilder()

            builder.append(globalNum)

            for ((fieldNum, field) in fields) {
                builder.append('|').append(fieldNum)
                    .append(':').append(field.baseType)
                    .append(':').append(field.raw.size)
            }

            return builder.toString()
        }

        fun get(fieldNum: Int): Double? {
            val field = fields[fieldNum] ?: return null

            return decodeScalar(field.baseType, field.raw)
        }
    }

    fun crc(data: ByteArray, seed: Int = 0): Int {
        var crc = seed

        for (byte in data) {
            val value = byte.toInt() and 0xFF

            var tmp = CRC_TABLE[crc and 0xF]
            crc = ((crc shr 4) and 0x0FFF) xor tmp xor CRC_TABLE[value and 0xF]

            tmp = CRC_TABLE[crc and 0xF]
            crc = ((crc shr 4) and 0x0FFF) xor tmp xor CRC_TABLE[(value shr 4) and 0xF]
        }

        return crc
    }

    fun encodeScalar(baseType: Int, value: Double?): ByteArray {
        val type = BASE_TYPES[baseType] ?: BASE_TYPES.getValue(BYTE)

        if (value == null) {
            val invalid = type.invalid ?: return ByteArray(type.size)

            return packLE(invalid, type.size)
        }

        if (type.isFloat) {
            if (type.size == 4) {
                return packLE(java.lang.Float.floatToRawIntBits(value.toFloat()).toLong() and
                    0xFFFFFFFFL, 4)
            }

            return packLE(java.lang.Double.doubleToRawLongBits(value), 8)
        }

        // Python: v = int(round(value)) then clamped into the type's range. round() ties to even,
        // and so does kotlin.math.round; clamping happens in Double space so 64-bit types cannot
        // overflow on the way in.
        val rounded = round(value)
        val bits = type.size * 8
        val low = if (type.signed) -Math.pow(2.0, (bits - 1).toDouble()) else 0.0
        val high = if (type.signed) {
            Math.pow(2.0, (bits - 1).toDouble()) - 1.0
        } else {
            Math.pow(2.0, bits.toDouble()) - 1.0
        }

        return packLE(min(high, max(low, rounded)).toLong(), type.size)
    }

    fun decodeScalar(baseType: Int, raw: ByteArray): Double? {
        val type = BASE_TYPES[baseType] ?: BASE_TYPES.getValue(BYTE)

        if (baseType == STRING || raw.size < type.size) return null

        if (type.isFloat) {
            if (type.size == 4) {
                return java.lang.Float.intBitsToFloat(unpackLE(raw, 4).toInt()).toDouble()
            }

            return java.lang.Double.longBitsToDouble(unpackLE(raw, 8))
        }

        var value = unpackLE(raw, type.size)

        if (type.signed && type.size < 8) {
            val sign = 1L shl (type.size * 8 - 1)

            if (value and sign != 0L) value -= (1L shl (type.size * 8))
        }

        if (type.invalid != null && value == type.invalid) return null

        return value.toDouble()
    }

    /**
     * Re-emit a FIT file, allocating local message types as layouts change and evicting the least
     * recently used slot once all 16 are taken.
     */
    fun build(header: ByteArray, messages: List<Message>): ByteArray {
        val out = ByteArrayOutputStream()
        val assigned = HashMap<String, Int>()
        val owner = HashMap<Int, String>()
        val order = ArrayList<Int>()

        for (message in messages) {
            val signature = message.signature()
            var local = assigned[signature]

            if (local == null) {
                local = if (assigned.size < MAX_LOCAL_TYPES) {
                    assigned.size
                } else {
                    val evicted = order[0]

                    assigned.remove(owner[evicted])

                    evicted
                }

                assigned[signature] = local
                owner[local] = signature

                out.write(encodeDefinition(local, message))
            }

            order.remove(local)
            order.add(local)

            out.write(local)

            for (field in message.fields.values) {
                out.write(field.raw)
            }
        }

        val body = out.toByteArray()
        val newHeader = header.copyOf()

        writeLE(newHeader, 4, body.size.toLong(), 4)

        if (newHeader.size >= 14) {
            writeLE(newHeader, 12, crc(newHeader.copyOfRange(0, 12)).toLong() and 0xFFFFL, 2)
        }

        val file = ByteArrayOutputStream()

        file.write(newHeader)
        file.write(body)

        val blob = file.toByteArray()

        return blob + packLE(crc(blob).toLong() and 0xFFFFL, 2)
    }

    fun encodeDefinition(local: Int, message: Message): ByteArray {
        val out = ByteArrayOutputStream()

        out.write(0x40 or local)
        out.write(0)                                   // reserved
        out.write(0)                                   // little endian
        out.write(packLE(message.globalNum.toLong(), 2))
        out.write(message.fields.size)

        for ((fieldNum, field) in message.fields) {
            out.write(fieldNum)
            out.write(field.raw.size)
            out.write(field.baseType)
        }

        return out.toByteArray()
    }

    /**
     * Parse a FIT file into its data messages. Only used by the tests to read a built file back;
     * compressed-timestamp headers and big-endian messages are rejected rather than supported.
     */
    fun parse(buf: ByteArray): List<Message> {
        val headerSize = buf[0].toInt() and 0xFF
        val dataSize = unpackLE(buf.copyOfRange(4, 8), 4).toInt()

        if (String(buf, 8, 4, Charsets.US_ASCII) != ".FIT") throw IllegalArgumentException("not a FIT file")

        var pos = headerSize
        val end = headerSize + dataSize
        val definitions = HashMap<Int, Triple<Int, List<IntArray>, List<IntArray>>>()
        val messages = ArrayList<Message>()

        while (pos < end) {
            val head = buf[pos].toInt() and 0xFF
            pos += 1

            if (head and 0x80 != 0) {
                throw IllegalArgumentException("compressed timestamp headers are not supported")
            }

            val local = head and 0x0F
            val isDefinition = head and 0x40 != 0
            val hasDev = head and 0x20 != 0

            if (isDefinition) {
                pos += 1
                val architecture = buf[pos].toInt() and 0xFF
                pos += 1

                if (architecture == 1) throw IllegalArgumentException("big-endian is not supported")

                val globalNum = unpackLE(buf.copyOfRange(pos, pos + 2), 2).toInt()
                pos += 2

                val fieldCount = buf[pos].toInt() and 0xFF
                pos += 1

                val fields = ArrayList<IntArray>()

                for (i in 0 until fieldCount) {
                    fields.add(
                        intArrayOf(
                            buf[pos].toInt() and 0xFF,
                            buf[pos + 1].toInt() and 0xFF,
                            buf[pos + 2].toInt() and 0xFF
                        )
                    )
                    pos += 3
                }

                val devs = ArrayList<IntArray>()

                if (hasDev) {
                    val devCount = buf[pos].toInt() and 0xFF
                    pos += 1

                    for (i in 0 until devCount) {
                        devs.add(
                            intArrayOf(
                                buf[pos].toInt() and 0xFF,
                                buf[pos + 1].toInt() and 0xFF,
                                buf[pos + 2].toInt() and 0xFF
                            )
                        )
                        pos += 3
                    }
                }

                definitions[local] = Triple(globalNum, fields, devs)

                continue
            }

            val definition = definitions[local]
                ?: throw IllegalArgumentException("data message for undefined local type $local")

            val message = Message(definition.first)

            for (field in definition.second) {
                message.fields[field[0]] = Field(field[2], buf.copyOfRange(pos, pos + field[1]))
                pos += field[1]
            }

            for (dev in definition.third) {
                pos += dev[1]
            }

            messages.add(message)
        }

        return messages
    }

    private fun packLE(value: Long, size: Int): ByteArray {
        val out = ByteArray(size)

        writeLE(out, 0, value, size)

        return out
    }

    private fun writeLE(target: ByteArray, offset: Int, value: Long, size: Int) {
        for (i in 0 until size) {
            target[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
    }

    private fun unpackLE(raw: ByteArray, size: Int): Long {
        var value = 0L

        for (i in 0 until size) {
            value = value or ((raw[i].toLong() and 0xFF) shl (8 * i))
        }

        return value
    }
}
