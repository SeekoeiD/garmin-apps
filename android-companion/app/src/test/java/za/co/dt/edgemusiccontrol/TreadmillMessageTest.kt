package za.co.dt.edgemusiccontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Connect IQ SDK deserialises device messages into plain Java containers and is inconsistent
 * about which numeric type it picks, so these tests feed the parser the shapes it will really see:
 * java.util.HashMap, java.util.ArrayList, Integer and Double side by side.
 */
class TreadmillMessageTest {

    private fun part0(): HashMap<String, Any> {
        val message = HashMap<String, Any>()

        message["t"] = "tl_run"
        message["k"] = 1787200000              // Integer, as Monkey C usually sends it
        message["i"] = 0
        message["n"] = 3
        message["start"] = 1787200000.0        // Double, as it sometimes arrives instead
        message["alt"] = 1480.0
        message["dur"] = 300
        message["sp"] = arrayListOf(arrayListOf(0, 250), arrayListOf(150, 300))
        message["inc"] = arrayListOf(arrayListOf(0, 40), arrayListOf(100, 100))

        return message
    }

    @Test
    fun readsAPartRegardlessOfNumericType() {
        val meta = TreadmillMessage.meta(1787200000L, 3, part0())

        assertNotNull(meta)
        assertEquals(1787200000L, meta!!.start)
        assertEquals(3, meta.parts)
        assertEquals(300, meta.duration)
        assertEquals(1480.0, meta.altitude!!, 1e-9)
        assertEquals(listOf(0 to 250, 150 to 300), meta.speedPoints)
        assertEquals(listOf(0 to 40, 100 to 100), meta.inclinePoints)
    }

    @Test
    fun treatsAMissingAltitudeAsUnknown() {
        val message = part0()

        message.remove("alt")

        assertNull(TreadmillMessage.meta(1787200000L, 3, message)!!.altitude)
    }

    @Test
    fun rejectsAPartWithoutAUsableDuration() {
        val message = part0()

        message["dur"] = 0

        assertNull(TreadmillMessage.meta(1787200000L, 3, message))

        message.remove("dur")

        assertNull(TreadmillMessage.meta(1787200000L, 3, message))
    }

    @Test
    fun fallsBackToTheKeyWhenTheStartIsMissing() {
        val message = part0()

        message.remove("start")

        assertEquals(1787200000L, TreadmillMessage.meta(1787200000L, 3, message)!!.start)
    }

    @Test
    fun treatsAPartCountOfZeroAsMetadataOnly() {
        assertEquals(1, TreadmillMessage.meta(1L, 0, part0())!!.parts)
    }

    @Test
    fun skipsMalformedChangePoints() {
        val raw = arrayListOf<Any?>(
            arrayListOf(0, 250),
            arrayListOf(10),                   // too short
            "nonsense",
            arrayListOf("30", 300.0),          // stringly typed, still readable
            arrayListOf(40, null)
        )

        assertEquals(listOf(0 to 250, 30 to 300), TreadmillMessage.pairs(raw))
    }

    @Test
    fun readsHeartRateWithUnknownsAsZero() {
        val raw = arrayListOf<Any?>(120, 121.0, null, 0, "123")

        assertTrue(intArrayOf(120, 121, 0, 0, 123).contentEquals(TreadmillMessage.heartRate(raw)))
        assertEquals(0, TreadmillMessage.heartRate(null).size)
        assertEquals(0, TreadmillMessage.heartRate("not a list").size)
    }

    @Test
    fun findsTheRunMapNestedInsideThePayload() {
        val message = part0()
        val payload = listOf<Any>(arrayListOf(hashMapOf("wrapper" to arrayListOf(message))))

        assertEquals(message, TreadmillMessage.find(payload))
    }

    @Test
    fun ignoresPayloadsThatAreNotRunParts() {
        assertNull(TreadmillMessage.find(null))
        assertNull(TreadmillMessage.find(listOf(hashMapOf("cmd" to "playpause"))))
        assertNull(TreadmillMessage.find(listOf("playpause")))
    }

    @Test
    fun buildsRepliesInTheShapeTheWatchExpects() {
        val ok = TreadmillMessage.success(42L, 825, 67)

        assertEquals("tl_result", ok["t"])
        assertEquals(42L, ok["k"])
        assertEquals(true, ok["ok"])
        assertEquals(825, ok["dist"])
        assertEquals(67, ok["asc"])

        val bad = TreadmillMessage.failure(42L, "x".repeat(200))

        assertEquals(false, bad["ok"])

        // The watch has a small screen and a small message budget; long errors are truncated.
        assertEquals(48, (bad["err"] as String).length)
    }
}
