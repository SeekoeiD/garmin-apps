package za.co.dt.edgemusiccontrol

/**
 * Reads the watch's wire format out of whatever the Connect IQ SDK hands over.
 *
 * The SDK deserialises Monkey C values into plain Java containers, and which numeric type comes out
 * depends on how the value was serialised — the same field can arrive as Integer on one run and
 * Double on the next. Nothing here assumes a type; anything unreadable is dropped rather than
 * throwing, because a malformed part must not take the service down.
 *
 * Free of Android imports so it can be unit tested on the JVM.
 */
object TreadmillMessage {

    const val TYPE_RUN = "tl_run"
    const val TYPE_RESULT = "tl_result"

    private const val MAX_SCAN_DEPTH = 4

    /** Find the run-part map inside a payload, whatever it is nested in. */
    fun find(payload: List<Any>?): Map<*, *>? {
        if (payload == null) return null

        for (item in payload) {
            val found = scan(item, 0)

            if (found != null) return found
        }

        return null
    }

    fun number(value: Any?): Double? {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
    }

    fun integer(value: Any?): Int? = number(value)?.toInt()

    fun list(value: Any?): List<Any?>? {
        return when (value) {
            is List<*> -> value
            is Array<*> -> value.toList()
            is Iterable<*> -> value.toList()
            else -> null
        }
    }

    /** A list of [second, magnitude] change-points; entries that make no sense are skipped. */
    fun pairs(value: Any?): List<Pair<Int, Int>> {
        val outer = list(value) ?: return emptyList()
        val out = ArrayList<Pair<Int, Int>>(outer.size)

        for (item in outer) {
            val inner = list(item) ?: continue

            if (inner.size < 2) continue

            val at = integer(inner[0]) ?: continue
            val magnitude = integer(inner[1]) ?: continue

            out.add(Pair(at, magnitude))
        }

        return out
    }

    /** Consecutive per-second heart rates; an unreadable entry becomes 0, meaning "no reading". */
    fun heartRate(value: Any?): IntArray {
        val items = list(value) ?: return IntArray(0)

        return IntArray(items.size) { integer(items[it]) ?: 0 }
    }

    /** Part 0 of a run, or null if it is missing anything the builder cannot do without. */
    fun meta(key: Long, parts: Int, message: Map<*, *>): RunBuffer.Meta? {
        val duration = integer(message["dur"]) ?: return null

        if (duration <= 0) return null

        return RunBuffer.Meta(
            key = key,
            // A run with no heart-rate parts is still a run; a missing count means "just part 0".
            parts = if (parts > 0) parts else 1,
            start = number(message["start"])?.toLong() ?: key,
            altitude = number(message["alt"]),
            duration = duration,
            speedPoints = pairs(message["sp"]),
            inclinePoints = pairs(message["inc"])
        )
    }

    fun success(key: Long, distance: Int, ascent: Int): Map<String, Any> {
        return mapOf("t" to TYPE_RESULT, "k" to key, "ok" to true, "dist" to distance, "asc" to ascent)
    }

    fun failure(key: Long, reason: String): Map<String, Any> {
        return mapOf("t" to TYPE_RESULT, "k" to key, "ok" to false, "err" to reason.take(48))
    }

    private fun scan(item: Any?, depth: Int): Map<*, *>? {
        if (item == null || depth > MAX_SCAN_DEPTH) return null

        if (item is Map<*, *>) {
            if (item["t"]?.toString() == TYPE_RUN) return item

            for (value in item.values) {
                val nested = scan(value, depth + 1)

                if (nested != null) return nested
            }

            return null
        }

        val nested = list(item) ?: return null

        for (value in nested) {
            val found = scan(value, depth + 1)

            if (found != null) return found
        }

        return null
    }
}
