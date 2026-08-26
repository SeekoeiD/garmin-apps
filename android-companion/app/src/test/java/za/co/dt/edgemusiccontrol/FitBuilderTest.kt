package za.co.dt.edgemusiccontrol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.round

/**
 * The FIT writer is a port of the Python that produced testdata_reference.fit, so the contract is
 * byte-for-byte equality against that file. Everything else here guards the arithmetic that feeds it.
 */
class FitBuilderTest {

    companion object {

        private const val REFERENCE_START_UNIX = 1787200000L
        private const val REFERENCE_ALTITUDE = 1480.0
        private const val REFERENCE_TZ_OFFSET = 7200

        private const val MESG_SESSION = 18
        private const val MESG_RECORD = 20
    }

    /**
     * The deterministic input described by testdata_reference.json:
     * speed=2.5 if i<150 else 3.0; incline=4.0 if i<100 else 10.0; hr=95+i//10; 300 samples.
     */
    private fun referenceSamples(): List<RunBuilder.Sample> {
        return (0 until 300).map { i ->
            RunBuilder.Sample(
                speed = if (i < 150) 2.5 else 3.0,
                incline = if (i < 100) 4.0 else 10.0,
                hr = 95 + i / 10
            )
        }
    }

    private fun resource(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)

        assertNotNull("missing test resource $name", stream)

        return stream!!.readBytes()
    }

    /** testdata_reference.json is tiny and flat, so a regex beats pulling in a JSON dependency. */
    private fun referenceTotal(key: String): Double {
        val json = String(resource("testdata_reference.json"), Charsets.UTF_8)
        val match = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)").find(json)

        assertNotNull("no $key in testdata_reference.json", match)

        return match!!.groupValues[1].toDouble()
    }

    @Test
    fun buildsTheReferenceFileByteForByte() {
        val expected = resource("testdata_reference.fit")

        val (actual, _) = RunBuilder.buildFit(
            referenceSamples(),
            REFERENCE_START_UNIX,
            REFERENCE_ALTITUDE,
            REFERENCE_TZ_OFFSET
        )

        assertEquals("file length", expected.size, actual.size)

        val firstDifference = expected.indices.firstOrNull { expected[it] != actual[it] }

        assertEquals("first differing byte offset", null, firstDifference)
        assertArrayEquals(expected, actual)
    }

    /** Guards the test above: a comparison that cannot fail is not a comparison. */
    @Test
    fun aDifferentInputDoesNotProduceTheReferenceFile() {
        val expected = resource("testdata_reference.fit")

        assertEquals(7567, expected.size)

        val (shifted, _) = RunBuilder.buildFit(
            referenceSamples(),
            REFERENCE_START_UNIX,
            REFERENCE_ALTITUDE,
            REFERENCE_TZ_OFFSET + 1
        )

        assertEquals(expected.size, shifted.size)
        assertFalse("a one-second timezone shift must change the bytes", expected.contentEquals(shifted))
    }

    @Test
    fun crcMatchesTheCrcsStoredInTheReferenceFile() {
        val file = resource("testdata_reference.fit")

        val storedHeaderCrc = (file[12].toInt() and 0xFF) or ((file[13].toInt() and 0xFF) shl 8)
        val storedFileCrc = (file[file.size - 2].toInt() and 0xFF) or
            ((file[file.size - 1].toInt() and 0xFF) shl 8)

        assertEquals(storedHeaderCrc, FitEncoder.crc(file.copyOfRange(0, 12)))
        assertEquals(storedFileCrc, FitEncoder.crc(file.copyOfRange(0, file.size - 2)))
    }

    @Test
    fun totalsMatchTheReferenceTotals() {
        val (_, totals) = RunBuilder.derive(referenceSamples(), REFERENCE_ALTITUDE)

        assertEquals(referenceTotal("records").toInt(), totals.records)
        assertEquals(referenceTotal("seconds"), totals.seconds, 1e-9)
        assertEquals(referenceTotal("distance"), totals.distance, 1e-9)
        assertEquals(referenceTotal("ascent"), totals.ascent, 1e-4)
        assertEquals(referenceTotal("descent"), totals.descent, 1e-9)
        assertEquals(referenceTotal("max_speed"), totals.maxSpeed, 1e-9)
        assertEquals(referenceTotal("avg_speed"), totals.avgSpeed, 1e-9)
        assertEquals(referenceTotal("avg_hr").toInt(), totals.avgHr)
        assertEquals(referenceTotal("max_hr").toInt(), totals.maxHr)
        assertEquals(referenceTotal("end_altitude"), totals.endAltitude, 1e-4)
    }

    @Test
    fun expandsChangePointsIntoOneSamplePerSecond() {
        val samples = RunBuilder.expand(
            duration = 6,
            speedPoints = listOf(0 to 250, 3 to 300),
            inclinePoints = listOf(0 to 40, 4 to 100),
            hr = intArrayOf(120, 121, 0, 123)
        )

        assertEquals(6, samples.size)
        assertEquals(listOf(2.5, 2.5, 2.5, 3.0, 3.0, 3.0), samples.map { it.speed })
        assertEquals(listOf(4.0, 4.0, 4.0, 4.0, 10.0, 10.0), samples.map { it.incline })

        // 0 means "no reading", and the heart rate list may be shorter than the run.
        assertEquals(listOf(120, 121, null, 123, null, null), samples.map { it.hr })
    }

    @Test
    fun expandHoldsZeroBeforeTheFirstChangePoint() {
        val samples = RunBuilder.expand(4, listOf(2 to 300), listOf(2 to 15), IntArray(0))

        assertEquals(listOf(0.0, 0.0, 3.0, 3.0), samples.map { it.speed })
        assertEquals(listOf(0.0, 0.0, 1.5, 1.5), samples.map { it.incline })
        assertNull(samples[0].hr)
    }

    /** End to end on a synthetic run: build, parse the bytes back, and check what Garmin would read. */
    @Test
    fun roundTripsASyntheticRunThroughTheParser() {
        val samples = RunBuilder.expand(
            duration = 120,
            speedPoints = listOf(0 to 200, 60 to 280),
            inclinePoints = listOf(0 to 0, 60 to 50),
            hr = IntArray(120) { 100 + it / 20 }
        )

        val startUnix = 1787200000L
        val (fit, totals) = RunBuilder.buildFit(samples, startUnix, 50.0)

        assertEquals(storedCrc(fit), FitEncoder.crc(fit.copyOfRange(0, fit.size - 2)))

        val messages = FitEncoder.parse(fit)
        val records = messages.filter { it.globalNum == MESG_RECORD }
        val session = messages.first { it.globalNum == MESG_SESSION }

        assertEquals(120, records.size)
        assertEquals(120, totals.records)

        val startTs = startUnix - RunBuilder.GARMIN_EPOCH

        assertEquals((startTs + 1).toDouble(), records.first().get(253))
        assertEquals((startTs + 120).toDouble(), records.last().get(253))

        assertEquals(round(totals.distance * 100.0), session.get(9))
        assertEquals(round(totals.seconds * 1000.0), session.get(7))
        assertEquals(round(totals.maxSpeed * 1000.0), session.get(15))
        assertEquals(round(totals.ascent), session.get(22))
        assertEquals(1.0, session.get(5))                          // sport: running
        assertEquals(1.0, session.get(6))                          // sub_sport: treadmill

        // 60 s at 2 m/s flat, then 60 s at 2.8 m/s on 5 %, whose belt speed is the hypotenuse.
        assertTrue(abs(totals.distance - (60 * 2.0 + 60 * 2.8)) < 1e-9)
        assertTrue(totals.ascent > 8.0 && totals.ascent < 8.5)
        assertEquals(0.0, totals.descent, 1e-12)
    }

    @Test
    fun buildFitRejectsAnEmptyRun() {
        val failed = runCatching { RunBuilder.buildFit(emptyList(), REFERENCE_START_UNIX) }

        assertTrue(failed.isFailure)
    }

    private fun storedCrc(file: ByteArray): Int {
        return (file[file.size - 2].toInt() and 0xFF) or ((file[file.size - 1].toInt() and 0xFF) shl 8)
    }
}
