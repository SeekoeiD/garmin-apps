package za.co.dt.edgemusiccontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The buffer is what stands between a dropped process and a lost run, so it gets real files. */
class RunBufferTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun buffer() = RunBuffer(folder.newFolder())

    private fun meta(key: Long, parts: Int, duration: Int) = RunBuffer.Meta(
        key = key,
        parts = parts,
        start = 1787200000L,
        altitude = 1480.0,
        duration = duration,
        speedPoints = listOf(0 to 250, 150 to 300),
        inclinePoints = listOf(0 to 40, 100 to 100)
    )

    @Test
    fun metaSurvivesARoundTrip() {
        val original = meta(1787200000L, 3, 300)
        val decoded = RunBuffer.decodeMeta(RunBuffer.encodeMeta(original))

        assertEquals(original, decoded)
    }

    @Test
    fun metaRoundTripsAnUnknownAltitude() {
        val original = meta(1L, 1, 10).copy(altitude = null)

        assertNull(RunBuffer.decodeMeta(RunBuffer.encodeMeta(original))!!.altitude)
    }

    @Test
    fun metaRoundTripsARunWithNoChangePoints() {
        val original = meta(1L, 1, 10).copy(speedPoints = emptyList(), inclinePoints = emptyList())

        assertEquals(original, RunBuffer.decodeMeta(RunBuffer.encodeMeta(original)))
    }

    @Test
    fun rejectsGarbageInsteadOfThrowing() {
        assertNull(RunBuffer.decodeMeta(""))
        assertNull(RunBuffer.decodeMeta("k=notanumber\nn=2\nstart=1\ndur=3\n"))
        assertNull(RunBuffer.decodeMeta("n=2\nstart=1\ndur=3\n"))
    }

    @Test
    fun isIncompleteUntilEveryPartHasLanded() {
        val buffer = buffer()

        assertFalse(buffer.isComplete(7L))

        buffer.saveMeta(meta(7L, 3, 4))

        assertFalse(buffer.isComplete(7L))

        buffer.saveHeartRate(7L, 1, intArrayOf(120, 121))

        assertFalse(buffer.isComplete(7L))

        buffer.saveHeartRate(7L, 2, intArrayOf(122, 123))

        assertTrue(buffer.isComplete(7L))
    }

    @Test
    fun assemblesPartsInIndexOrder() {
        val buffer = buffer()

        // Deliberately out of order, and a repeat: the watch retries whatever it is unsure of.
        buffer.saveHeartRate(7L, 2, intArrayOf(122, 123))
        buffer.saveMeta(meta(7L, 3, 4))
        buffer.saveHeartRate(7L, 1, intArrayOf(0, 0))
        buffer.saveHeartRate(7L, 1, intArrayOf(120, 121))

        val assembled = buffer.assemble(7L)

        assertNotNull(assembled)

        val (readBack, samples) = assembled!!

        assertEquals(4, samples.size)
        assertEquals(listOf(120, 121, 122, 123), samples.map { it.hr })
        assertEquals(listOf(2.5, 2.5, 2.5, 2.5), samples.map { it.speed })
        assertEquals(1480.0, readBack.altitude!!, 1e-9)
    }

    /** What the treadmill actually did second by second beats interpolating between change-points. */
    @Test
    fun perSecondSpeedReplacesTheChangePoints() {
        val buffer = buffer()

        buffer.saveMeta(meta(7L, 3, 4))
        buffer.saveHeartRate(7L, 1, intArrayOf(120, 121))
        buffer.saveSpeed(7L, 1, intArrayOf(200, 210))
        buffer.saveHeartRate(7L, 2, intArrayOf(122, 123))
        buffer.saveSpeed(7L, 2, intArrayOf(220, 230))

        val (_, samples) = buffer.assemble(7L)!!

        assertEquals(listOf(2.0, 2.1, 2.2, 2.3), samples.map { it.speed })
        assertEquals(listOf(120, 121, 122, 123), samples.map { it.hr })

        // Incline has no series of its own, so it still comes from part 0.
        assertEquals(listOf(4.0, 4.0, 4.0, 4.0), samples.map { it.incline })
    }

    /** The old wire format, and every run already sitting on disk when the app is updated. */
    @Test
    fun heartRateOnlyPartsFallBackToTheChangePoints() {
        val buffer = buffer()

        buffer.saveMeta(meta(7L, 2, 4))
        buffer.saveHeartRate(7L, 1, intArrayOf(120, 121, 122, 123))

        val (_, samples) = buffer.assemble(7L)!!

        assertEquals(listOf(2.5, 2.5, 2.5, 2.5), samples.map { it.speed })
        assertEquals(listOf(120, 121, 122, 123), samples.map { it.hr })
    }

    /** Half a series is worse than none: one part without speeds throws the whole run out of step. */
    @Test
    fun oneSpeedlessPartFallsBackToTheChangePoints() {
        val buffer = buffer()

        buffer.saveMeta(meta(7L, 3, 4))
        buffer.saveHeartRate(7L, 1, intArrayOf(120, 121))
        buffer.saveSpeed(7L, 1, intArrayOf(200, 210))
        buffer.saveHeartRate(7L, 2, intArrayOf(122, 123))

        val (_, samples) = buffer.assemble(7L)!!

        assertEquals(listOf(2.5, 2.5, 2.5, 2.5), samples.map { it.speed })
    }

    @Test
    fun aSpeedSeriesThatStopsShortFallsBackToTheChangePoints() {
        val buffer = buffer()

        buffer.saveMeta(meta(7L, 2, 4))
        buffer.saveHeartRate(7L, 1, intArrayOf(120, 121, 122, 123))
        buffer.saveSpeed(7L, 1, intArrayOf(200, 210, 220))

        val (_, samples) = buffer.assemble(7L)!!

        assertEquals(listOf(2.5, 2.5, 2.5, 2.5), samples.map { it.speed })
    }

    @Test
    fun aSpeedSeriesLongerThanTheRunIsTruncated() {
        val buffer = buffer()

        buffer.saveMeta(meta(7L, 2, 3))
        buffer.saveHeartRate(7L, 1, intArrayOf(120, 121, 122, 123))
        buffer.saveSpeed(7L, 1, intArrayOf(200, 210, 220, 230))

        val (_, samples) = buffer.assemble(7L)!!

        assertEquals(listOf(2.0, 2.1, 2.2), samples.map { it.speed })
    }

    /** The watch retries a part it is unsure of, speeds and all. */
    @Test
    fun aResentPartOverwritesItsSpeeds() {
        val buffer = buffer()

        buffer.saveMeta(meta(7L, 2, 2))
        buffer.saveHeartRate(7L, 1, intArrayOf(0, 0))
        buffer.saveSpeed(7L, 1, intArrayOf(0, 0))
        buffer.saveHeartRate(7L, 1, intArrayOf(120, 121))
        buffer.saveSpeed(7L, 1, intArrayOf(200, 210))

        val (_, samples) = buffer.assemble(7L)!!

        assertEquals(listOf(2.0, 2.1), samples.map { it.speed })
        assertEquals(listOf(120, 121), samples.map { it.hr })
    }

    @Test
    fun assemblesNothingWhileAPartIsStillMissing() {
        val buffer = buffer()

        buffer.saveMeta(meta(7L, 3, 4))
        buffer.saveHeartRate(7L, 2, intArrayOf(122, 123))

        assertNull(buffer.assemble(7L))
        assertNull(buffer.assemble(999L))
    }

    @Test
    fun aRunWithNoHeartRatePartsStillAssembles() {
        val buffer = buffer()

        buffer.saveMeta(meta(7L, 1, 3))

        val (_, samples) = buffer.assemble(7L)!!

        assertEquals(3, samples.size)
        assertEquals(listOf(null, null, null), samples.map { it.hr })
    }

    @Test
    fun discardRemovesTheRun() {
        val buffer = buffer()

        buffer.saveMeta(meta(7L, 2, 2))
        buffer.saveHeartRate(7L, 1, intArrayOf(120, 121))

        assertTrue(buffer.isComplete(7L))

        buffer.discard(7L)

        assertNull(buffer.meta(7L))
        assertFalse(buffer.isComplete(7L))
    }

    /** A whole run, exactly as it arrives: parts to disk, stitched back, built into a FIT file. */
    @Test
    fun bufferedPartsRebuildIntoAFitFile() {
        val buffer = buffer()

        buffer.saveMeta(meta(1787200000L, 2, 300))
        buffer.saveHeartRate(1787200000L, 1, IntArray(300) { 95 + it / 10 })

        val (readBack, samples) = buffer.assemble(1787200000L)!!
        val (fit, totals) = RunBuilder.buildFit(samples, readBack.start, readBack.altitude!!)

        assertEquals(300, totals.records)
        assertEquals(825.0, totals.distance, 1e-9)
        assertEquals(110, totals.avgHr)

        // The same input as the reference file, so it must be the reference file.
        val expected = javaClass.classLoader!!.getResourceAsStream("testdata_reference.fit")!!.readBytes()

        assertTrue(expected.contentEquals(fit))
    }
}
