package za.co.dt.edgemusiccontrol

import android.content.Context
import org.json.JSONObject

object Prefs {

    private const val FILE = "edge_music_control"
    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_DI_TOKEN = "di_token"
    private const val KEY_DI_REFRESH = "di_refresh_token"
    private const val KEY_DI_CLIENT = "di_client_id"
    private const val KEY_LAST_RUN = "last_run_status"
    private const val KEY_STRAVA_CLIENT = "strava_client_id"
    private const val KEY_STRAVA_SECRET = "strava_client_secret"
    private const val KEY_STRAVA_TOKEN = "strava_access_token"
    private const val KEY_STRAVA_REFRESH = "strava_refresh_token"
    private const val KEY_STRAVA_EXPIRES = "strava_expires_at"
    private const val KEY_STRAVA_ATHLETE = "strava_athlete"
    private const val PREFIX_UPLOADED = "uploaded_"
    private const val PREFIX_STRAVA_UPLOADED = "strava_uploaded_"

    /**
     * Garmin Connect OAuth material, pasted in by the user and never leaving this file. Nothing here
     * may be logged or committed: the repository is public.
     */
    data class GarminTokens(val accessToken: String, val refreshToken: String, val clientId: String)

    /**
     * The user's own Strava API application. Not a login of any kind, but the secret is still a
     * secret: same rule as the Garmin material above.
     */
    data class StravaApp(val clientId: String, val clientSecret: String)

    /** Strava OAuth tokens, with the access token's expiry in epoch seconds. */
    data class StravaTokens(val accessToken: String, val refreshToken: String, val expiresAt: Long)

    /** Whether the user last left the service running, and so whether to restore it on boot. */
    fun autoStart(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTOSTART, false)
    }

    fun setAutoStart(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOSTART, enabled).apply()
    }

    fun garminTokens(context: Context): GarminTokens? {
        val store = prefs(context)

        val accessToken = store.getString(KEY_DI_TOKEN, null) ?: return null
        val refreshToken = store.getString(KEY_DI_REFRESH, null) ?: return null
        val clientId = store.getString(KEY_DI_CLIENT, null) ?: return null

        if (accessToken.isEmpty() || refreshToken.isEmpty() || clientId.isEmpty()) return null

        return GarminTokens(accessToken, refreshToken, clientId)
    }

    /**
     * Persist immediately, with commit() rather than apply(): Garmin rotates the refresh token on
     * every refresh, and losing the new one strands the account until the user re-exports from a PC.
     */
    fun setGarminTokens(context: Context, tokens: GarminTokens) {
        prefs(context).edit()
            .putString(KEY_DI_TOKEN, tokens.accessToken)
            .putString(KEY_DI_REFRESH, tokens.refreshToken)
            .putString(KEY_DI_CLIENT, tokens.clientId)
            .commit()
    }

    /** Accepts the {"di_token", "di_refresh_token", "di_client_id"} JSON the user exports. */
    fun saveGarminTokensJson(context: Context, raw: String): Boolean {
        val json = runCatching { JSONObject(raw.trim()) }.getOrNull() ?: return false

        val accessToken = json.optString(KEY_DI_TOKEN)
        val refreshToken = json.optString(KEY_DI_REFRESH)
        val clientId = json.optString(KEY_DI_CLIENT)

        if (accessToken.isEmpty() || refreshToken.isEmpty() || clientId.isEmpty()) return false

        setGarminTokens(context, GarminTokens(accessToken, refreshToken, clientId))

        return true
    }

    fun hasGarminTokens(context: Context): Boolean = garminTokens(context) != null

    fun stravaApp(context: Context): StravaApp? {
        val store = prefs(context)

        val clientId = store.getString(KEY_STRAVA_CLIENT, null) ?: return null
        val clientSecret = store.getString(KEY_STRAVA_SECRET, null) ?: return null

        if (clientId.isEmpty() || clientSecret.isEmpty()) return null

        return StravaApp(clientId, clientSecret)
    }

    fun setStravaApp(context: Context, app: StravaApp) {
        prefs(context).edit()
            .putString(KEY_STRAVA_CLIENT, app.clientId)
            .putString(KEY_STRAVA_SECRET, app.clientSecret)
            .commit()
    }

    fun stravaClientId(context: Context): String? {
        return prefs(context).getString(KEY_STRAVA_CLIENT, null)
    }

    fun stravaTokens(context: Context): StravaTokens? {
        val store = prefs(context)

        val accessToken = store.getString(KEY_STRAVA_TOKEN, null) ?: return null
        val refreshToken = store.getString(KEY_STRAVA_REFRESH, null) ?: return null

        if (accessToken.isEmpty() || refreshToken.isEmpty()) return null

        return StravaTokens(accessToken, refreshToken, store.getLong(KEY_STRAVA_EXPIRES, 0L))
    }

    /** commit(), for the same reason as the Garmin tokens: Strava rotates the refresh token too. */
    fun setStravaTokens(context: Context, tokens: StravaTokens) {
        prefs(context).edit()
            .putString(KEY_STRAVA_TOKEN, tokens.accessToken)
            .putString(KEY_STRAVA_REFRESH, tokens.refreshToken)
            .putLong(KEY_STRAVA_EXPIRES, tokens.expiresAt)
            .commit()
    }

    fun stravaAthlete(context: Context): String? {
        return prefs(context).getString(KEY_STRAVA_ATHLETE, null)
    }

    fun setStravaAthlete(context: Context, name: String) {
        prefs(context).edit().putString(KEY_STRAVA_ATHLETE, name).commit()
    }

    fun hasStravaTokens(context: Context): Boolean = stravaTokens(context) != null

    /** Forgets the connection but keeps the API application, so reconnecting is one tap. */
    fun clearStravaTokens(context: Context) {
        prefs(context).edit()
            .remove(KEY_STRAVA_TOKEN)
            .remove(KEY_STRAVA_REFRESH)
            .remove(KEY_STRAVA_EXPIRES)
            .remove(KEY_STRAVA_ATHLETE)
            .commit()
    }

    /** Distance and ascent in whole metres for a run already accepted by Garmin, if there is one. */
    fun uploadedRun(context: Context, key: Long): Pair<Int, Int>? {
        val stored = prefs(context).getString(PREFIX_UPLOADED + key, null) ?: return null
        val split = stored.indexOf(':')

        if (split <= 0) return null

        val distance = stored.substring(0, split).toIntOrNull() ?: return null
        val ascent = stored.substring(split + 1).toIntOrNull() ?: return null

        return Pair(distance, ascent)
    }

    fun setUploadedRun(context: Context, key: Long, distance: Int, ascent: Int) {
        prefs(context).edit().putString(PREFIX_UPLOADED + key, "$distance:$ascent").commit()
    }

    /**
     * Strava's activity id for a run it has already taken, or zero where it accepted the file
     * without naming one. Null means Strava has not had this run.
     */
    fun stravaUploadedRun(context: Context, key: Long): Long? {
        val stored = prefs(context).getString(PREFIX_STRAVA_UPLOADED + key, null) ?: return null

        return stored.toLongOrNull()
    }

    fun setStravaUploadedRun(context: Context, key: Long, activityId: Long) {
        prefs(context).edit()
            .putString(PREFIX_STRAVA_UPLOADED + key, activityId.toString())
            .commit()
    }

    fun lastRunStatus(context: Context): String? {
        return prefs(context).getString(KEY_LAST_RUN, null)
    }

    fun setLastRunStatus(context: Context, status: String) {
        prefs(context).edit().putString(KEY_LAST_RUN, status).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
