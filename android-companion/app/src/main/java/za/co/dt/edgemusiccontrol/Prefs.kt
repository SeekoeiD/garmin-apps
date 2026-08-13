package za.co.dt.edgemusiccontrol

import android.content.Context

object Prefs {

    private const val FILE = "edge_music_control"
    private const val KEY_AUTOSTART = "autostart"

    /** Whether the user last left the service running, and so whether to restore it on boot. */
    fun autoStart(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTOSTART, false)
    }

    fun setAutoStart(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOSTART, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
