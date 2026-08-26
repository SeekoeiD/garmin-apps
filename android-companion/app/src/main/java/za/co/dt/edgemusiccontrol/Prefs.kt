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
    private const val PREFIX_UPLOADED = "uploaded_"

    /**
     * Garmin Connect OAuth material, pasted in by the user and never leaving this file. Nothing here
     * may be logged or committed: the repository is public.
     */
    data class GarminTokens(val accessToken: String, val refreshToken: String, val clientId: String)

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

    fun lastRunStatus(context: Context): String? {
        return prefs(context).getString(KEY_LAST_RUN, null)
    }

    fun setLastRunStatus(context: Context, status: String) {
        prefs(context).edit().putString(KEY_LAST_RUN, status).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
