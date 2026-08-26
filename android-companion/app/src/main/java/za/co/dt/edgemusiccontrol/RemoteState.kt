package za.co.dt.edgemusiccontrol

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide snapshot of what the service is doing, so MainActivity can render a live status line
 * without binding to the service.
 */
object RemoteState {

    fun interface Listener {

        fun onRemoteStateChanged()
    }

    @Volatile
    var serviceRunning: Boolean = false

    @Volatile
    var sdkReady: Boolean = false

    @Volatile
    var sdkError: String? = null

    @Volatile
    var deviceName: String? = null

    @Volatile
    var deviceConnected: Boolean = false

    @Volatile
    var knownDeviceCount: Int = 0

    @Volatile
    var lastCommand: String? = null

    @Volatile
    var lastCommandAt: Long = 0L

    @Volatile
    var lastReport: String? = null

    /** Outcome of the most recent treadmill run upload, mirrored from Prefs so it survives a restart. */
    @Volatile
    var lastRunStatus: String? = null

    private val listeners = CopyOnWriteArrayList<Listener>()

    private val mainHandler = Handler(Looper.getMainLooper())

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun notifyChanged() {
        mainHandler.post {
            for (listener in listeners) {
                listener.onRemoteStateChanged()
            }
        }
    }

    fun reset() {
        serviceRunning = false
        sdkReady = false
        sdkError = null
        deviceName = null
        deviceConnected = false
        knownDeviceCount = 0

        notifyChanged()
    }
}
