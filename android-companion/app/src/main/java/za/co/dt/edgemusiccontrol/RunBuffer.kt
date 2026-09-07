package za.co.dt.edgemusiccontrol

import java.io.File

/**
 * Holds the parts of a treadmill run on disk until every part has arrived.
 *
 * The watch sends a run as one metadata part plus N sample parts, each carrying per-second heart
 * rates and (since the watch started sending them) per-second speeds, retrying any part it is not
 * sure landed. Buffering in memory would lose a half-transferred run whenever Android reclaims the
 * process, which for a 45-minute run is a real risk, so each part hits the filesystem as it lands.
 *
 * The format is deliberately flat text rather than JSON: it is trivially parseable, has no Android
 * dependency, and stays readable when something needs debugging off a pulled directory.
 */
class RunBuffer(private val root: File) {

    companion object {

        private const val META_FILE = "meta.txt"
        private const val HR_PREFIX = "hr"
        private const val SPEED_PREFIX = "v"
        private const val PART_SUFFIX = ".txt"

        fun encodeMeta(meta: Meta): String {
            val builder = StringBuilder()

            builder.append("k=").append(meta.key).append('\n')
            builder.append("n=").append(meta.parts).append('\n')
            builder.append("start=").append(meta.start).append('\n')
            builder.append("alt=").append(meta.altitude?.toString() ?: "-").append('\n')
            builder.append("dur=").append(meta.duration).append('\n')
            builder.append("sp=").append(encodePairs(meta.speedPoints)).append('\n')
            builder.append("inc=").append(encodePairs(meta.inclinePoints)).append('\n')

            return builder.toString()
        }

        fun decodeMeta(text: String): Meta? {
            val values = HashMap<String, String>()

            for (line in text.lineSequence()) {
                val split = line.indexOf('=')

                if (split > 0) values[line.substring(0, split)] = line.substring(split + 1)
            }

            val key = values["k"]?.toLongOrNull() ?: return null
            val parts = values["n"]?.toIntOrNull() ?: return null
            val start = values["start"]?.toLongOrNull() ?: return null
            val duration = values["dur"]?.toIntOrNull() ?: return null

            return Meta(
                key = key,
                parts = parts,
                start = start,
                altitude = values["alt"]?.toDoubleOrNull(),
                duration = duration,
                speedPoints = decodePairs(values["sp"]),
                inclinePoints = decodePairs(values["inc"])
            )
        }

        private fun encodePairs(pairs: List<Pair<Int, Int>>): String {
            return pairs.joinToString(",") { "${it.first}:${it.second}" }
        }

        private fun decodePairs(raw: String?): List<Pair<Int, Int>> {
            if (raw.isNullOrBlank()) return emptyList()

            val out = ArrayList<Pair<Int, Int>>()

            for (item in raw.split(',')) {
                val split = item.indexOf(':')

                if (split <= 0) continue

                val at = item.substring(0, split).trim().toIntOrNull() ?: continue
                val value = item.substring(split + 1).trim().toIntOrNull() ?: continue

                out.add(Pair(at, value))
            }

            return out
        }
    }

    /** Part 0 of a run: everything except the per-second streams. */
    data class Meta(
        val key: Long,
        val parts: Int,
        val start: Long,
        val altitude: Double?,
        val duration: Int,
        val speedPoints: List<Pair<Int, Int>>,
        val inclinePoints: List<Pair<Int, Int>>
    )

    fun saveMeta(meta: Meta) {
        write(File(directory(meta.key), META_FILE), encodeMeta(meta))
    }

    fun saveHeartRate(key: Long, index: Int, values: IntArray) {
        write(File(directory(key), HR_PREFIX + index + PART_SUFFIX), values.joinToString(","))
    }

    /** The part's per-second speeds as v100, stored alongside its heart rates. */
    fun saveSpeed(key: Long, index: Int, values: IntArray) {
        write(File(directory(key), SPEED_PREFIX + index + PART_SUFFIX), values.joinToString(","))
    }

    fun meta(key: Long): Meta? {
        val file = File(directory(key), META_FILE)

        if (!file.isFile) return null

        return runCatching { decodeMeta(file.readText()) }.getOrNull()
    }

    fun heartRate(key: Long, index: Int): IntArray? {
        return values(File(directory(key), HR_PREFIX + index + PART_SUFFIX))
    }

    /** Null when the part never carried speeds, which is every part of an older run. */
    fun speed(key: Long, index: Int): IntArray? {
        return values(File(directory(key), SPEED_PREFIX + index + PART_SUFFIX))
    }

    /** True once part 0 and every heart-rate part it promised are on disk. */
    fun isComplete(key: Long): Boolean {
        val meta = meta(key) ?: return false

        for (index in 1 until meta.parts) {
            if (heartRate(key, index) == null) return false
        }

        return true
    }

    /**
     * Stitch the buffered parts into 1 Hz samples, or null if anything is still missing.
     *
     * Parts are concatenated in index order rather than placed at a fixed stride, so a watch that
     * changes its part size mid-run still lines up.
     *
     * Speed comes from the per-second series the parts carry, which records what the treadmill was
     * actually doing between change-points. A run whose parts carry no speeds, or whose speeds stop
     * short of the duration, falls back to expanding part 0's change-points — that is how every run
     * worked before the watch started sending the series, and how a half-old buffered run still
     * assembles. Incline always comes from the change-points; the watch sends no series for it.
     */
    fun assemble(key: Long): Pair<Meta, List<RunBuilder.Sample>>? {
        val meta = meta(key) ?: return null
        val beats = ArrayList<Int>(meta.duration)
        val speeds = ArrayList<Int>(meta.duration)

        var speedSeries = true

        for (index in 1 until meta.parts) {
            val part = heartRate(key, index) ?: return null

            for (beat in part) {
                beats.add(beat)
            }

            val speedPart = speed(key, index)

            if (speedPart == null) {
                speedSeries = false
            } else {
                for (value in speedPart) {
                    speeds.add(value)
                }
            }
        }

        val samples = RunBuilder.expand(
            meta.duration,
            meta.speedPoints,
            meta.inclinePoints,
            beats.toIntArray()
        )

        if (!speedSeries || speeds.size < meta.duration) return Pair(meta, samples)

        // Longer than the run means the last part overshot the duration; the tail is not a sample.
        val measured = samples.mapIndexed { second, sample ->
            sample.copy(speed = speeds[second] / 100.0)
        }

        return Pair(meta, measured)
    }

    fun discard(key: Long) {
        val directory = directory(key)

        directory.listFiles()?.forEach { runCatching { it.delete() } }

        runCatching { directory.delete() }
    }

    /** One part's stored series, or null if that file was never written. */
    private fun values(file: File): IntArray? {
        if (!file.isFile) return null

        val text = runCatching { file.readText() }.getOrNull() ?: return null

        if (text.isBlank()) return IntArray(0)

        return text.split(',').map { it.trim().toIntOrNull() ?: 0 }.toIntArray()
    }

    private fun directory(key: Long): File {
        val directory = File(root, key.toString())

        if (!directory.isDirectory) directory.mkdirs()

        return directory
    }

    /** Write via a temporary file so a kill mid-write cannot leave a half-written part behind. */
    private fun write(target: File, text: String) {
        val temporary = File(target.parentFile, target.name + ".tmp")

        temporary.writeText(text)

        if (!temporary.renameTo(target)) {
            target.writeText(text)

            temporary.delete()
        }
    }
}
