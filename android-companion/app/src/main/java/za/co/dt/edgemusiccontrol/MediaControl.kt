package za.co.dt.edgemusiccontrol

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent

/**
 * Drives whatever app currently owns a media session, and reports back what it is playing.
 * All of this hinges on Notification Access having been granted; without it getActiveSessions()
 * throws and we degrade to blind media key events.
 */
class MediaControl(private val context: Context) {

    companion object {

        const val CMD_PLAY_PAUSE = "playpause"
        const val CMD_NEXT = "next"
        const val CMD_PREV = "prev"
        const val CMD_VOL_UP = "volup"
        const val CMD_VOL_DOWN = "voldown"

        val COMMANDS = setOf(CMD_PLAY_PAUSE, CMD_NEXT, CMD_PREV, CMD_VOL_UP, CMD_VOL_DOWN)

        private const val TAG = "MediaControl"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val sessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    private val listenerComponent = ComponentName(context, MusicNotificationListener::class.java)

    private val handler = Handler(Looper.getMainLooper())

    // Keyed by session token: MediaController instances are not stable across getActiveSessions()
    // calls, but their tokens compare by value.
    private val watched = mutableMapOf<MediaSession.Token, WatchedSession>()

    private class WatchedSession(
        val controller: MediaController,
        val callback: MediaController.Callback
    )

    /** Fired when the active session's metadata or playback state changes. */
    var onPlaybackChanged: (() -> Unit)? = null

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            rebindCallbacks(controllers ?: emptyList())

            onPlaybackChanged?.invoke()
        }

    fun startWatching() {
        try {
            sessionManager.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                listenerComponent,
                handler
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification access; cannot watch media sessions", e)

            return
        }

        rebindCallbacks(activeControllers())
    }

    fun stopWatching() {
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove session listener", e)
        }

        rebindCallbacks(emptyList())
    }

    fun hasNotificationAccess(): Boolean {
        return try {
            sessionManager.getActiveSessions(listenerComponent)

            true
        } catch (e: SecurityException) {
            false
        }
    }

    /** Returns true if the command was recognised and dispatched. */
    fun execute(command: String): Boolean {
        return when (command) {
            CMD_VOL_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
            CMD_VOL_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
            CMD_PLAY_PAUSE, CMD_NEXT, CMD_PREV -> transport(command)
            else -> false
        }
    }

    fun snapshot(): Map<String, Any> {
        val controller = bestController()
        val metadata = controller?.metadata

        val state = if (controller != null && isActive(controller.playbackState?.state)) {
            "playing"
        } else {
            "paused"
        }

        return mapOf(
            "state" to state,
            "track" to (metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""),
            "artist" to (metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: ""),
            "vol" to volumePercent()
        )
    }

    fun volumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        if (max <= 0) return 0

        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        return current * 100 / max
    }

    private fun transport(command: String): Boolean {
        val controller = bestController()

        if (controller == null) {
            return dispatchKey(
                when (command) {
                    CMD_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                    CMD_PREV -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                }
            )
        }

        return try {
            when (command) {
                CMD_NEXT -> controller.transportControls.skipToNext()
                CMD_PREV -> controller.transportControls.skipToPrevious()
                else -> if (isActive(controller.playbackState?.state)) {
                    controller.transportControls.pause()
                } else {
                    controller.transportControls.play()
                }
            }

            true
        } catch (e: Exception) {
            Log.w(TAG, "Transport control failed on ${controller.packageName}", e)

            false
        }
    }

    private fun adjustVolume(direction: Int): Boolean {
        return try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)

            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Volume adjustment refused (Do Not Disturb?)", e)

            false
        }
    }

    private fun dispatchKey(keyCode: Int): Boolean {
        return try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))

            true
        } catch (e: Exception) {
            Log.w(TAG, "Media key dispatch failed", e)

            false
        }
    }

    private fun activeControllers(): List<MediaController> {
        return try {
            sessionManager.getActiveSessions(listenerComponent)
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /**
     * The framework returns sessions in priority order, so index 0 is the sensible default — but an
     * app that is actually producing sound outranks it.
     */
    private fun bestController(): MediaController? {
        val controllers = activeControllers()

        return controllers.firstOrNull { isActive(it.playbackState?.state) }
            ?: controllers.firstOrNull()
    }

    private fun isActive(state: Int?): Boolean {
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    }

    private fun rebindCallbacks(controllers: List<MediaController>) {
        val live = controllers.map { it.sessionToken }.toSet()

        val stale = watched.filterKeys { it !in live }

        for ((token, session) in stale) {
            runCatching { session.controller.unregisterCallback(session.callback) }

            watched.remove(token)
        }

        for (controller in controllers) {
            if (watched.containsKey(controller.sessionToken)) continue

            val callback = object : MediaController.Callback() {

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    onPlaybackChanged?.invoke()
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    onPlaybackChanged?.invoke()
                }

                override fun onSessionDestroyed() {
                    onPlaybackChanged?.invoke()
                }
            }

            runCatching { controller.registerCallback(callback, handler) }
                .onSuccess { watched[controller.sessionToken] = WatchedSession(controller, callback) }
        }
    }
}
