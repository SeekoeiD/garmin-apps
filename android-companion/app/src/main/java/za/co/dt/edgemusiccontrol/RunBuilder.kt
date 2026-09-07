package za.co.dt.edgemusiccontrol

import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Turns the watch's speed/incline change-points into 1 Hz samples and those samples into a Garmin
 * FIT activity file.
 *
 * A faithful port of server/fit_builder.py from the garmin-treadmill project. The watch reports only
 * speed, incline and heart rate; distance, altitude and climb totals are integrated here, because
 * Connect IQ's own recording ignores :nativeNum overrides and every consumer ignores what it writes.
 *
 * Deliberately free of Android imports so it runs unchanged in a JVM unit test.
 */
object RunBuilder {

    const val GARMIN_EPOCH = 631065600L

    const val DEFAULT_ALTITUDE = 100.0

    /**
     * The treadmill never leaves South Africa and the watch does not send its UTC offset, so the
     * local timestamp written into the activity message is fixed at SAST (UTC+2).
     */
    const val TZ_OFFSET_SECONDS = 7200

    private const val MESG_FILE_ID = 0
    private const val MESG_SESSION = 18
    private const val MESG_LAP = 19
    private const val MESG_RECORD = 20
    private const val MESG_EVENT = 21
    private const val MESG_ACTIVITY = 34

    private const val MANUFACTURER_DEVELOPMENT = 255
    private const val PRODUCT = 1
    private const val SERIAL_NUMBER = 12345678

    // Header size, protocol 2.0, profile version 21184, data size placeholder, ".FIT" tag, CRC slot.
    // FitEncoder.build() rewrites the data size and both CRCs.
    private val HEADER = byteArrayOf(
        14, 0x20, 0xC0.toByte(), 0x52, 0, 0, 0, 0,
        '.'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte(), 0, 0
    )

    /** One second of treadmill output. A null or zero heart rate means "not measured". */
    data class Sample(val speed: Double, val incline: Double, val hr: Int?)

    /** One derived record: running distance and altitude alongside the reported values. */
    data class Point(
        val distance: Double,
        val speed: Double,
        val altitude: Double,
        val incline: Double,
        val hr: Int?
    )

    data class Totals(
        val records: Int,
        val seconds: Double,
        val distance: Double,
        val ascent: Double,
        val descent: Double,
        val maxSpeed: Double,
        val avgSpeed: Double,
        val avgHr: Int?,
        val maxHr: Int?,
        val startAltitude: Double,
        val endAltitude: Double
    )

    /**
     * Expand the wire format's change-points into one sample per second.
     *
     * A change-point holds from its second until the next one; before the first change-point the
     * value is zero. Heart rate is already per-second, and runs short if the watch never got a
     * reading for the tail of the run.
     */
    fun expand(
        duration: Int,
        speedPoints: List<Pair<Int, Int>>,
        inclinePoints: List<Pair<Int, Int>>,
        hr: IntArray
    ): List<Sample> {
        val speeds = speedPoints.sortedBy { it.first }
        val inclines = inclinePoints.sortedBy { it.first }
        val samples = ArrayList<Sample>(max(0, duration))

        var speedIndex = 0
        var inclineIndex = 0
        var speed = 0.0
        var incline = 0.0

        for (second in 0 until duration) {
            while (speedIndex < speeds.size && speeds[speedIndex].first <= second) {
                speed = speeds[speedIndex].second / 100.0
                speedIndex += 1
            }

            while (inclineIndex < inclines.size && inclines[inclineIndex].first <= second) {
                incline = inclines[inclineIndex].second / 10.0
                inclineIndex += 1
            }

            val beat = if (second < hr.size) hr[second] else 0

            samples.add(Sample(speed, incline, if (beat == 0) null else beat))
        }

        return samples
    }

    /** Integrate the samples into per-record points plus whole-run totals. */
    fun derive(
        samples: List<Sample>,
        startAltitude: Double = DEFAULT_ALTITUDE,
        dt: Double = 1.0
    ): Pair<List<Point>, Totals> {
        val points = ArrayList<Point>(samples.size)

        var distance = 0.0
        var altitude = startAltitude
        var ascent = 0.0
        var descent = 0.0
        var maxSpeed = 0.0
        var hrSum = 0
        var hrCount = 0
        var maxHr = 0

        for (sample in samples) {
            val speed = max(0.0, sample.speed)
            val incline = sample.incline
            val hr = if (sample.hr == null || sample.hr == 0) null else sample.hr
            val grade = incline / 100.0

            // Treadmill incline is rise over run, so the reported belt speed is the hypotenuse.
            val rise = speed * grade / sqrt(1.0 + grade * grade) * dt

            distance += speed * dt
            altitude += rise

            if (rise > 0.0) {
                ascent += rise
            } else {
                descent -= rise
            }

            maxSpeed = max(maxSpeed, speed)

            if (hr != null) {
                hrSum += hr
                hrCount += 1
                maxHr = max(maxHr, hr)
            }

            points.add(Point(distance, speed, altitude, incline, hr))
        }

        val seconds = points.size * dt

        val totals = Totals(
            records = points.size,
            seconds = seconds,
            distance = distance,
            ascent = ascent,
            descent = descent,
            maxSpeed = maxSpeed,
            avgSpeed = if (seconds != 0.0) distance / seconds else 0.0,
            avgHr = if (hrCount > 0) round(hrSum.toDouble() / hrCount).toInt() else null,
            maxHr = if (maxHr != 0) maxHr else null,
            startAltitude = startAltitude,
            endAltitude = altitude
        )

        return Pair(points, totals)
    }

    /** Return the FIT bytes plus the totals for the run described by the samples. */
    fun buildFit(
        samples: List<Sample>,
        startUnix: Long,
        startAltitude: Double = DEFAULT_ALTITUDE,
        tzOffset: Int = TZ_OFFSET_SECONDS,
        dt: Double = 1.0
    ): Pair<ByteArray, Totals> {
        require(samples.isNotEmpty()) { "no samples" }

        val (points, totals) = derive(samples, startAltitude, dt)
        val startTs = startUnix - GARMIN_EPOCH
        val endTs = startTs + round(totals.seconds).toLong()

        val messages = ArrayList<FitEncoder.Message>(points.size + 6)

        messages.add(fileIdMessage(startTs))
        messages.add(timerEvent(startTs, 0))

        for ((index, point) in points.withIndex()) {
            messages.add(recordMessage(startTs + round((index + 1) * dt).toLong(), point))
        }

        messages.add(timerEvent(endTs, 4))
        messages.add(lapMessage(startTs, endTs, totals))
        messages.add(sessionMessage(startTs, endTs, totals))
        messages.add(activityMessage(endTs, totals, tzOffset))

        return Pair(FitEncoder.build(HEADER, messages), totals)
    }

    private fun fileIdMessage(startTs: Long): FitEncoder.Message {
        val m = FitEncoder.Message(MESG_FILE_ID)

        m.set(0, FitEncoder.ENUM, 4)                              // type: activity
        m.set(1, FitEncoder.UINT16, MANUFACTURER_DEVELOPMENT)
        m.set(2, FitEncoder.UINT16, PRODUCT)
        m.set(3, FitEncoder.UINT32Z, SERIAL_NUMBER)
        m.set(4, FitEncoder.UINT32, startTs.toDouble())

        return m
    }

    private fun timerEvent(ts: Long, eventType: Int): FitEncoder.Message {
        val m = FitEncoder.Message(MESG_EVENT)

        m.set(253, FitEncoder.UINT32, ts.toDouble())
        m.set(0, FitEncoder.ENUM, 0)                              // event: timer
        m.set(1, FitEncoder.ENUM, eventType)                      // 0 start, 4 stop_all
        m.set(4, FitEncoder.UINT8, 0)

        return m
    }

    private fun recordMessage(ts: Long, point: Point): FitEncoder.Message {
        val m = FitEncoder.Message(MESG_RECORD)

        m.set(253, FitEncoder.UINT32, ts.toDouble())
        m.set(5, FitEncoder.UINT32, point.distance * 100.0)
        m.set(6, FitEncoder.UINT16, point.speed * 1000.0)
        m.set(73, FitEncoder.UINT32, point.speed * 1000.0)        // enhanced_speed
        m.set(2, FitEncoder.UINT16, (point.altitude + 500.0) * 5.0)
        m.set(78, FitEncoder.UINT32, (point.altitude + 500.0) * 5.0)   // enhanced_altitude
        m.set(9, FitEncoder.SINT16, point.incline * 100.0)
        m.set(3, FitEncoder.UINT8, point.hr)

        return m
    }

    private fun lapMessage(startTs: Long, endTs: Long, totals: Totals): FitEncoder.Message {
        val m = FitEncoder.Message(MESG_LAP)

        m.set(253, FitEncoder.UINT32, endTs.toDouble())
        m.set(254, FitEncoder.UINT16, 0)
        m.set(2, FitEncoder.UINT32, startTs.toDouble())
        m.set(0, FitEncoder.ENUM, 9)                              // event: lap
        m.set(1, FitEncoder.ENUM, 1)                              // event_type: stop
        m.set(7, FitEncoder.UINT32, totals.seconds * 1000.0)
        m.set(8, FitEncoder.UINT32, totals.seconds * 1000.0)
        m.set(9, FitEncoder.UINT32, totals.distance * 100.0)
        m.set(13, FitEncoder.UINT16, totals.avgSpeed * 1000.0)
        m.set(14, FitEncoder.UINT16, totals.maxSpeed * 1000.0)
        m.set(15, FitEncoder.UINT8, totals.avgHr)
        m.set(16, FitEncoder.UINT8, totals.maxHr)
        m.set(21, FitEncoder.UINT16, totals.ascent)
        m.set(22, FitEncoder.UINT16, totals.descent)
        m.set(110, FitEncoder.UINT32, totals.avgSpeed * 1000.0)
        m.set(111, FitEncoder.UINT32, totals.maxSpeed * 1000.0)

        return m
    }

    private fun sessionMessage(startTs: Long, endTs: Long, totals: Totals): FitEncoder.Message {
        val m = FitEncoder.Message(MESG_SESSION)

        m.set(253, FitEncoder.UINT32, endTs.toDouble())
        m.set(254, FitEncoder.UINT16, 0)
        m.set(2, FitEncoder.UINT32, startTs.toDouble())
        m.set(0, FitEncoder.ENUM, 8)                              // event: session
        m.set(1, FitEncoder.ENUM, 1)                              // event_type: stop
        m.set(5, FitEncoder.ENUM, 1)                              // sport: running
        // virtual_activity, not treadmill: Strava zeroes the elevation total of
        // treadmill-tagged runs but keeps it for a Virtual Run.
        m.set(6, FitEncoder.ENUM, 58)                             // sub_sport: virtual_activity
        m.set(7, FitEncoder.UINT32, totals.seconds * 1000.0)
        m.set(8, FitEncoder.UINT32, totals.seconds * 1000.0)
        m.set(9, FitEncoder.UINT32, totals.distance * 100.0)
        m.set(14, FitEncoder.UINT16, totals.avgSpeed * 1000.0)
        m.set(15, FitEncoder.UINT16, totals.maxSpeed * 1000.0)
        m.set(16, FitEncoder.UINT8, totals.avgHr)
        m.set(17, FitEncoder.UINT8, totals.maxHr)
        m.set(22, FitEncoder.UINT16, totals.ascent)
        m.set(23, FitEncoder.UINT16, totals.descent)
        m.set(26, FitEncoder.UINT16, 1)                           // num_laps
        m.set(124, FitEncoder.UINT32, totals.avgSpeed * 1000.0)
        m.set(125, FitEncoder.UINT32, totals.maxSpeed * 1000.0)

        return m
    }

    private fun activityMessage(endTs: Long, totals: Totals, tzOffset: Int): FitEncoder.Message {
        val m = FitEncoder.Message(MESG_ACTIVITY)

        m.set(253, FitEncoder.UINT32, endTs.toDouble())
        m.set(0, FitEncoder.UINT32, totals.seconds * 1000.0)
        m.set(1, FitEncoder.UINT16, 1)                            // num_sessions
        m.set(2, FitEncoder.ENUM, 0)                              // type: manual
        m.set(3, FitEncoder.ENUM, 26)                             // event: activity
        m.set(4, FitEncoder.ENUM, 1)                              // event_type: stop
        m.set(5, FitEncoder.UINT32, (endTs + tzOffset).toDouble())             // local_timestamp

        return m
    }
}
