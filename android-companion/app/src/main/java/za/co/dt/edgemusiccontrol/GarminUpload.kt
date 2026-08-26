package za.co.dt.edgemusiccontrol

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Pushes a built FIT file into Garmin Connect using the mobile app's own upload endpoint.
 *
 * There is no public API for this, so the request has to look like Garmin Connect Mobile: the two
 * user-agent headers below are load-bearing, and the service rejects the upload without them.
 *
 * Everything here blocks; callers run it off the main thread.
 */
object GarminUpload {

    private const val UPLOAD_URL = "https://connectapi.garmin.com/upload-service/upload"
    private const val TOKEN_URL = "https://diauth.garmin.com/di-oauth2-service/oauth/token"

    private const val USER_AGENT = "GCM-Android-5.23"
    private const val GARMIN_USER_AGENT =
        "com.garmin.android.apps.connectmobile/5.23; ; Google/sdk_gphone64_arm64/google; " +
            "Android/33; Dalvik/2.1.0"

    private const val BOUNDARY = "----EdgeMusicControlBoundary7f1c9a"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val TAG = "GarminUpload"

    private class Response(val status: Int, val body: String)

    /**
     * Returns null when Garmin accepted the file, otherwise a short message fit for the watch's
     * screen. Never returns token material, and never logs it.
     */
    fun upload(context: Context, fileName: String, fit: ByteArray): String? {
        var tokens = Prefs.garminTokens(context) ?: return "no tokens"

        var response = postFit(tokens.accessToken, fileName, fit)

        // 401/403 means the access token aged out. Refresh once, retry once, then give up.
        if (response.status == 401 || response.status == 403) {
            tokens = refresh(context, tokens) ?: return "auth expired"

            response = postFit(tokens.accessToken, fileName, fit)
        }

        return interpret(response)
    }

    private fun postFit(accessToken: String, fileName: String, fit: ByteArray): Response {
        val body = multipart(fileName, fit)

        return runCatching {
            val connection = open(UPLOAD_URL)

            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
            connection.setFixedLengthStreamingMode(body.size)

            connection.outputStream.use { it.write(body) }

            read(connection)
        }.getOrElse { error ->
            Log.w(TAG, "Upload failed", error)

            Response(0, error.javaClass.simpleName)
        }
    }

    /**
     * Garmin rotates the refresh token, so the replacement is written back before this returns —
     * dropping it would strand the account until the user exports tokens from their PC again.
     */
    private fun refresh(context: Context, tokens: Prefs.GarminTokens): Prefs.GarminTokens? {
        val basic = Base64.encodeToString(
            (tokens.clientId + ":").toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

        val form = "grant_type=refresh_token" +
            "&client_id=" + encode(tokens.clientId) +
            "&refresh_token=" + encode(tokens.refreshToken)

        val response = runCatching {
            val connection = open(TOKEN_URL)

            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Basic $basic")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("Accept", "application/json")

            val bytes = form.toByteArray(Charsets.UTF_8)

            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            read(connection)
        }.getOrElse { error ->
            Log.w(TAG, "Token refresh failed", error)

            return null
        }

        if (response.status !in 200..299) {
            Log.w(TAG, "Token refresh returned ${response.status}")

            return null
        }

        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: return null
        val accessToken = json.optString("access_token")

        if (accessToken.isEmpty()) return null

        val rotated = json.optString("refresh_token")

        val updated = tokens.copy(
            accessToken = accessToken,
            refreshToken = if (rotated.isEmpty()) tokens.refreshToken else rotated
        )

        Prefs.setGarminTokens(context, updated)

        return updated
    }

    /**
     * A 2xx is not the whole story: the service reports a rejected file (a duplicate, most often)
     * inside detailedImportResult.failures while still answering 200.
     */
    private fun interpret(response: Response): String? {
        if (response.status !in 200..299) {
            val detail = failureMessage(response.body)

            if (response.status == 0) return "network: ${response.body}"

            return if (detail != null) "http ${response.status}: $detail" else "http ${response.status}"
        }

        return failureMessage(response.body)
    }

    private fun failureMessage(body: String): String? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val result = json.optJSONObject("detailedImportResult") ?: return null
        val failures = result.optJSONArray("failures") ?: return null

        if (failures.length() == 0) return null

        val first = failures.optJSONObject(0) ?: return "rejected"
        val messages = first.optJSONArray("messages")

        if (messages != null && messages.length() > 0) {
            val content = messages.optJSONObject(0)?.optString("content").orEmpty()

            if (content.isNotEmpty()) return content
        }

        return "rejected"
    }

    private fun multipart(fileName: String, fit: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(fit.size + 256)

        val head = "--$BOUNDARY\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"

        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(fit)
        out.write("\r\n--$BOUNDARY--\r\n".toByteArray(Charsets.UTF_8))

        return out.toByteArray()
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection

        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("X-Garmin-User-Agent", GARMIN_USER_AGENT)

        return connection
    }

    private fun read(connection: HttpURLConnection): Response {
        val status = connection.responseCode

        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()

        connection.disconnect()

        return Response(status, body)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
