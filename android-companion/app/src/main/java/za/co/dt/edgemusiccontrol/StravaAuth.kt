package za.co.dt.edgemusiccontrol

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The Strava end of the OAuth dance: the authorisation URL the in-app WebView loads, the code
 * exchange that finishes it, and the refresh the uploader needs before a token lapses.
 *
 * Strava's mobile authorisation endpoint redirects to the callback registered on the user's own API
 * application — http://localhost/exchange_token, which nothing listens on. That is deliberate: the
 * WebView intercepts the redirect and reads the code straight out of the URL instead of loading it.
 *
 * Strava rotates the refresh token on every refresh, so a new pair is written back through Prefs
 * before it is used. Nothing here logs token material.
 *
 * Everything blocks; callers run it off the main thread.
 */
object StravaAuth {

    const val REDIRECT_URI = "http://localhost/exchange_token"

    private const val AUTHORIZE_URL = "https://www.strava.com/oauth/mobile/authorize"
    private const val TOKEN_URL = "https://www.strava.com/oauth/token"
    private const val SCOPE = "activity:write,read"

    // Refresh a little early, so an upload never starts on a token that dies mid-request.
    private const val REFRESH_MARGIN_SECONDS = 300L

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val TAG = "StravaAuth"

    /** A parsed /oauth/token response: the tokens, plus the athlete's name where Strava named one. */
    data class Grant(val tokens: Prefs.StravaTokens, val athlete: String?)

    private class Response(val status: Int, val body: String)

    fun authorizeUrl(clientId: String): String {
        return AUTHORIZE_URL +
            "?client_id=" + encode(clientId) +
            "&redirect_uri=" + encode(REDIRECT_URI) +
            "&response_type=code" +
            "&approval_prompt=auto" +
            "&scope=" + encode(SCOPE)
    }

    /**
     * Whether an access token expiring at [expiresAt] (epoch seconds) should be replaced now rather
     * than used.
     */
    fun needsRefresh(expiresAt: Long, nowSeconds: Long): Boolean {
        return expiresAt - nowSeconds <= REFRESH_MARGIN_SECONDS
    }

    /** Reads a token or refresh response. Null when it is not one, or is missing a token. */
    fun parseGrant(body: String): Grant? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null

        val accessToken = json.optString("access_token")
        val refreshToken = json.optString("refresh_token")

        if (accessToken.isEmpty() || refreshToken.isEmpty()) return null

        val tokens = Prefs.StravaTokens(accessToken, refreshToken, json.optLong("expires_at", 0L))

        val athlete = json.optJSONObject("athlete") ?: return Grant(tokens, null)

        val name = (athlete.optString("firstname") + " " + athlete.optString("lastname")).trim()

        return Grant(tokens, name.ifEmpty { null })
    }

    /**
     * Trades an authorisation code for tokens and stores them. Returns null on success, otherwise a
     * short message fit for a toast.
     */
    fun exchange(context: Context, app: Prefs.StravaApp, code: String): String? {
        val form = "client_id=" + encode(app.clientId) +
            "&client_secret=" + encode(app.clientSecret) +
            "&code=" + encode(code) +
            "&grant_type=authorization_code"

        val response = post(form)

        if (response.status !in 200..299) {
            Log.w(TAG, "Code exchange returned ${response.status}")

            return if (response.status == 0) "network error" else "HTTP ${response.status}"
        }

        val grant = parseGrant(response.body) ?: return "unreadable reply"

        Prefs.setStravaTokens(context, grant.tokens)

        if (grant.athlete != null) Prefs.setStravaAthlete(context, grant.athlete)

        return null
    }

    /**
     * An access token good to use right now, refreshing first when the stored one is at or near its
     * expiry. Null when Strava is not connected, or when a needed refresh failed.
     */
    fun accessToken(context: Context, forceRefresh: Boolean = false): String? {
        val tokens = Prefs.stravaTokens(context) ?: return null
        val now = System.currentTimeMillis() / 1000L

        if (!forceRefresh && !needsRefresh(tokens.expiresAt, now)) return tokens.accessToken

        val app = Prefs.stravaApp(context)

        if (app == null) {
            Log.w(TAG, "Cannot refresh: the Strava API application details are missing")

            return null
        }

        val refreshed = refresh(context, app, tokens)

        if (refreshed != null) return refreshed.accessToken

        // A refresh can fail for reasons that have nothing to do with the token — no network, most
        // likely. One that has not actually lapsed yet is still worth a try.
        if (!forceRefresh && tokens.expiresAt > now) return tokens.accessToken

        return null
    }

    /**
     * The replacement pair is written back before this returns: Strava invalidates the old refresh
     * token immediately, so dropping the new one would strand the connection.
     */
    private fun refresh(
        context: Context,
        app: Prefs.StravaApp,
        tokens: Prefs.StravaTokens
    ): Prefs.StravaTokens? {
        val form = "client_id=" + encode(app.clientId) +
            "&client_secret=" + encode(app.clientSecret) +
            "&grant_type=refresh_token" +
            "&refresh_token=" + encode(tokens.refreshToken)

        val response = post(form)

        if (response.status !in 200..299) {
            Log.w(TAG, "Token refresh returned ${response.status}")

            return null
        }

        val grant = parseGrant(response.body) ?: return null

        Prefs.setStravaTokens(context, grant.tokens)

        return grant.tokens
    }

    private fun post(form: String): Response {
        return runCatching {
            val connection = URL(TOKEN_URL).openConnection() as HttpURLConnection

            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("Accept", "application/json")

            val bytes = form.toByteArray(Charsets.UTF_8)

            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            val status = connection.responseCode

            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()

            connection.disconnect()

            Response(status, body)
        }.getOrElse { error ->
            Log.w(TAG, "Token request failed", error)

            Response(0, error.javaClass.simpleName)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
