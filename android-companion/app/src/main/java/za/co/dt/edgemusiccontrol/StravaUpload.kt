package za.co.dt.edgemusiccontrol

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads a built FIT file straight to Strava, ahead of the Garmin upload.
 *
 * Strava zeroes the elevation total of any activity flagged as done on a trainer, and both
 * Garmin's push and Strava itself flag anything that arrives without GPS. So this upload has
 * to be the one Strava keeps - Garmin's later push then arrives as a duplicate and is dropped
 * - and the trainer flag is cleared explicitly once the activity exists.
 *
 * Strava does not process an upload inline — the POST returns an upload id and the result has to be
 * polled for. Everything here blocks, including the polling; callers run it off the main thread.
 */
object StravaUpload {

    /**
     * The outcome of one upload. A success carries Strava's activity id, or zero where Strava took
     * the file without naming one.
     */
    class Result private constructor(val activityId: Long, val error: String?) {

        val ok: Boolean get() = error == null

        companion object {

            fun success(activityId: Long): Result = Result(activityId, null)

            fun failure(message: String): Result = Result(0L, message)
        }
    }

    private const val UPLOADS_URL = "https://www.strava.com/api/v3/uploads"
    private const val ACTIVITIES_URL = "https://www.strava.com/api/v3/activities"

    private const val BOUNDARY = "----EdgeMusicControlStrava3d02b8"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000

    private const val POLL_INTERVAL_MS = 2_000L
    // The watch waits 90 s for its reply and the Garmin upload still has to run after this, so
    // the poll gives up well short of that. Strava normally settles in a few seconds.
    private const val POLL_TIMEOUT_MS = 30_000L

    // Shown on the activity. Nothing else on Strava marks the run as indoor once the trainer
    // flag is cleared, and the flag has to go or the elevation total does.
    private const val DESCRIPTION = "Treadmill"

    private const val TAG = "StravaUpload"

    // "treadmill_1787200000.fit duplicate of activity 12345678901"
    private val DUPLICATE = Regex("""duplicate of activity (\d+)""")

    private class Response(val status: Int, val body: String)

    /** Uploads one run. [key] is the run's start epoch, which names the file and the external id. */
    fun upload(context: Context, key: Long, fit: ByteArray): Result {
        val externalId = "treadmill_$key"

        var accessToken = StravaAuth.accessToken(context) ?: return Result.failure("no tokens")

        var response = postFit(accessToken, externalId, fit)

        // A 401 means the token died earlier than its stated expiry. Refresh once, retry once.
        if (response.status == 401) {
            accessToken = StravaAuth.accessToken(context, forceRefresh = true)
                ?: return Result.failure("auth expired")

            response = postFit(accessToken, externalId, fit)
        }

        if (response.status == 0) return Result.failure("network: ${response.body}")

        if (response.status !in 200..299) return Result.failure(httpError(response))

        // Strava usually answers the POST with nothing but an id and status "Your activity is still
        // being processed.", but it can reject the file outright right here.
        val settled = readStatus(response.body)

        if (settled != null) return settled

        val id = uploadId(response.body) ?: return Result.failure("no upload id")

        val result = poll(accessToken, id)

        if (result.ok && result.activityId != 0L) describe(accessToken, result.activityId)

        return result
    }

    /**
     * Clear the trainer flag on the finished activity and say what it was.
     *
     * Strava sets the flag itself when a file arrives without GPS, and a trainer activity loses its
     * elevation total - the whole reason the run is uploaded here rather than left to Garmin's
     * push. Unlike the upload form, this endpoint takes a real JSON boolean, so false means false.
     * Clearing it leaves the run looking like any outdoor one, hence the description: it is the
     * only place Strava offers to say where the climb actually came from.
     *
     * A failure is logged and swallowed: the activity is already on Strava either way.
     */
    private fun describe(accessToken: String, activityId: Long) {
        runCatching {
            val connection = open("$ACTIVITIES_URL/$activityId", accessToken)
            val body = """{"trainer":false,"description":"$DESCRIPTION"}""".toByteArray(Charsets.UTF_8)

            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            val response = read(connection)

            if (response.status !in 200..299) {
                Log.w(TAG, "Could not describe the activity: http ${response.status}")
            }
        }.onFailure { Log.w(TAG, "Could not describe the activity", it) }
    }

    /** The upload id from a create response, if it named one. */
    fun uploadId(body: String): Long? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val id = json.optLong("id", 0L)

        if (id != 0L) return id

        // The id also comes back as a string in id_str on some responses.
        return json.optString("id_str").toLongOrNull()
    }

    /**
     * Reads an upload response, from the POST or a poll. Null means Strava is still working on it.
     *
     * A file Strava already holds fails with "duplicate of activity N", which is exactly what a
     * re-sent run should produce: the activity is there, so count it as done.
     */
    fun readStatus(body: String): Result? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null

        val activityId = json.optLong("activity_id", 0L)

        if (activityId != 0L) return Result.success(activityId)

        if (json.isNull("error")) return null

        val error = json.optString("error")

        if (error.isEmpty()) return null

        val duplicate = DUPLICATE.find(error)

        if (duplicate != null) return Result.success(duplicate.groupValues[1].toLong())

        return Result.failure(error)
    }

    /** The multipart body Strava's upload endpoint expects. */
    fun multipart(fileName: String, externalId: String, fit: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(fit.size + 512)

        out.write(field("data_type", "fit"))
        out.write(field("sport_type", "Run"))

        // No trainer field at all. Sending trainer=0 produced a trainer activity - the form value
        // is read for presence, not truth, so "0" marks it just as surely as "1" would. A web
        // upload omits the field and lands with the flag clear, which is what we want; the flag is
        // then set explicitly through the activity endpoint, where it is a real boolean.
        out.write(field("external_id", externalId))

        val head = "--$BOUNDARY\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"

        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(fit)
        out.write("\r\n--$BOUNDARY--\r\n".toByteArray(Charsets.UTF_8))

        return out.toByteArray()
    }

    private fun poll(accessToken: String, id: Long): Result {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()

                return Result.failure("interrupted")
            }

            val response = get("$UPLOADS_URL/$id", accessToken)

            if (response.status == 0) return Result.failure("network: ${response.body}")

            if (response.status !in 200..299) return Result.failure(httpError(response))

            val status = readStatus(response.body)

            if (status != null) return status
        }

        return Result.failure("still processing after ${POLL_TIMEOUT_MS / 1000} s")
    }

    private fun postFit(accessToken: String, externalId: String, fit: ByteArray): Response {
        val body = multipart("$externalId.fit", externalId, fit)

        return runCatching {
            val connection = open(UPLOADS_URL, accessToken)

            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
            connection.setFixedLengthStreamingMode(body.size)

            connection.outputStream.use { it.write(body) }

            read(connection)
        }.getOrElse { error ->
            Log.w(TAG, "Upload failed", error)

            Response(0, error.javaClass.simpleName)
        }
    }

    private fun get(url: String, accessToken: String): Response {
        return runCatching {
            val connection = open(url, accessToken)

            connection.requestMethod = "GET"

            read(connection)
        }.getOrElse { error ->
            Log.w(TAG, "Upload poll failed", error)

            Response(0, error.javaClass.simpleName)
        }
    }

    private fun httpError(response: Response): String {
        val message = runCatching { JSONObject(response.body).optString("message") }.getOrNull()

        if (message.isNullOrEmpty()) return "http ${response.status}"

        return "http ${response.status}: $message"
    }

    private fun field(name: String, value: String): ByteArray {
        val part = "--$BOUNDARY\r\n" +
            "Content-Disposition: form-data; name=\"$name\"\r\n\r\n" +
            value + "\r\n"

        return part.toByteArray(Charsets.UTF_8)
    }

    private fun open(url: String, accessToken: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection

        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.setRequestProperty("Accept", "application/json")

        return connection
    }

    private fun read(connection: HttpURLConnection): Response {
        val status = connection.responseCode

        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()

        connection.disconnect()

        return Response(status, body)
    }
}
