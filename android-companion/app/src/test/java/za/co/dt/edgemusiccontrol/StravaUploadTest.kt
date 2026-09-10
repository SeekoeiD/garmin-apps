package za.co.dt.edgemusiccontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upload is one POST and then a poll, and both halves are easy to get subtly wrong: a missing
 * multipart field costs the activity its trainer=0, and a misread poll either hangs or throws away
 * an activity Strava did accept. Both are checked here against the bodies Strava really sends.
 */
class StravaUploadTest {

    // A FIT header's first bytes, including a zero and a byte that is not valid UTF-8 on its own.
    private val fit = byteArrayOf(0x0E, 0x20, 0x00, 0x00, 0xFF.toByte(), 0x2E, 0x46, 0x49, 0x54)

    private fun body(): String {
        return String(
            StravaUpload.multipart("treadmill_1787200000.fit", "treadmill_1787200000", fit),
            Charsets.ISO_8859_1
        )
    }

    @Test
    fun multipartCarriesEveryFieldTheUploadNeeds() {
        val text = body()

        assertTrue(text.contains("name=\"data_type\"\r\n\r\nfit\r\n"))
        assertTrue(text.contains("name=\"sport_type\"\r\n\r\nRun\r\n"))
        assertTrue(text.contains("name=\"external_id\"\r\n\r\ntreadmill_1787200000\r\n"))
    }

    /**
     * A real run uploaded with trainer=0 came back flagged as a trainer activity anyway: the form
     * value is read for presence, not truth. Omitting the field is what a web upload does, and a
     * web upload lands with the flag clear.
     */
    @Test
    fun multipartSendsNoTrainerField() {
        assertFalse(body().contains("name=\"trainer\""))
    }

    @Test
    fun multipartCarriesTheFileItselfUnaltered() {
        val text = body()

        val head = "Content-Disposition: form-data; name=\"file\"; " +
            "filename=\"treadmill_1787200000.fit\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"

        val at = text.indexOf(head)

        assertTrue(at > 0)

        val start = at + head.length

        assertEquals(String(fit, Charsets.ISO_8859_1), text.substring(start, start + fit.size))
    }

    @Test
    fun multipartEndsWithTheClosingBoundary() {
        val text = body()
        val boundary = text.substring(0, text.indexOf("\r\n"))

        assertTrue(text.endsWith("\r\n$boundary--\r\n"))
    }

    @Test
    fun readsTheUploadIdFromTheCreateResponse() {
        val response = """
            {"id":1234567890,"id_str":"1234567890","external_id":"treadmill_1787200000",
             "error":null,"status":"Your activity is still being processed.","activity_id":null}
        """.trimIndent()

        assertEquals(1234567890L, StravaUpload.uploadId(response))
    }

    @Test
    fun aStillProcessingUploadIsNotYetAnOutcome() {
        val response = """
            {"id":1234567890,"error":null,
             "status":"Your activity is still being processed.","activity_id":null}
        """.trimIndent()

        assertNull(StravaUpload.readStatus(response))
    }

    @Test
    fun anAcceptedUploadReportsItsActivity() {
        val response = """
            {"id":1234567890,"error":null,"status":"Your activity is ready.",
             "activity_id":9876543210}
        """.trimIndent()

        val result = StravaUpload.readStatus(response)

        assertNotNull(result)
        assertTrue(result!!.ok)
        assertEquals(9876543210L, result.activityId)
    }

    /** A re-sent run: Strava calls it an error, but the activity is there, so the run is done. */
    @Test
    fun aDuplicateCountsAsAlreadyUploaded() {
        val response = """
            {"id":1234567890,"error":"treadmill_1787200000.fit duplicate of activity 9876543210",
             "status":"There was an error processing your activity.","activity_id":null}
        """.trimIndent()

        val result = StravaUpload.readStatus(response)

        assertNotNull(result)
        assertTrue(result!!.ok)
        assertEquals(9876543210L, result.activityId)
    }

    @Test
    fun aRejectedUploadReportsWhy() {
        val response = """
            {"id":1234567890,"error":"treadmill_1787200000.fit is not a valid file type",
             "status":"There was an error processing your activity.","activity_id":null}
        """.trimIndent()

        val result = StravaUpload.readStatus(response)

        assertNotNull(result)
        assertFalse(result!!.ok)
        assertEquals(0L, result.activityId)
        assertTrue(result.error!!.contains("not a valid file type"))
    }

    @Test
    fun nonsenseIsNotMistakenForAnOutcome() {
        assertNull(StravaUpload.readStatus("<html>502 Bad Gateway</html>"))
        assertNull(StravaUpload.uploadId("<html>502 Bad Gateway</html>"))
    }
}
