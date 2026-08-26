package za.co.dt.edgemusiccontrol

import android.content.Context
import android.util.Log
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice

/**
 * Owns the Connect IQ Mobile SDK registration. Messages are relayed by the Garmin Connect Mobile
 * app, which the SDK binds to; if it is absent, initialisation reports GCM_NOT_INSTALLED and the
 * link never comes up.
 *
 * Note that Connect IQ cannot cold-start this process: onMessageReceived only fires into a live
 * in-process registration, which is why this lives inside a foreground service.
 */
class GarminLink(private val context: Context) {

    companion object {

        const val APP_UUID = "36e2af37-6039-4d5e-995f-8ebf715014f2"

        /** The Forerunner 965 treadmill app, which posts a finished run at save time. */
        const val TREADMILL_APP_UUID = "3a6a2645883f4e378e28c984fde94e0d"

        private const val TAG = "GarminLink"
    }

    private val connectIQ = ConnectIQ.getInstance(context, ConnectIQ.IQConnectType.WIRELESS)

    private val app = IQApp(APP_UUID)

    private val treadmillApp = IQApp(TREADMILL_APP_UUID)

    private val devices = mutableListOf<IQDevice>()

    private var initialized = false

    var onCommand: ((String) -> Unit)? = null

    /** A raw payload from the treadmill app, with the device to answer on. */
    var onTreadmillMessage: ((IQDevice, List<Any>) -> Unit)? = null

    var onLinkChanged: (() -> Unit)? = null

    private val sdkListener = object : ConnectIQ.ConnectIQListener {

        override fun onSdkReady() {
            initialized = true

            RemoteState.sdkReady = true
            RemoteState.sdkError = null

            refreshDevices()
        }

        override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
            initialized = false

            RemoteState.sdkReady = false
            RemoteState.sdkError = status?.name ?: "UNKNOWN"

            Log.w(TAG, "Connect IQ init failed: ${RemoteState.sdkError}")

            onLinkChanged?.invoke()
        }

        override fun onSdkShutDown() {
            initialized = false

            RemoteState.sdkReady = false

            onLinkChanged?.invoke()
        }
    }

    private val deviceListener = ConnectIQ.IQDeviceEventListener { device, status ->
        Log.i(TAG, "Device ${device?.friendlyName} -> $status")

        device?.status = status

        publishDeviceState()
    }

    private val appListener = ConnectIQ.IQApplicationEventListener { _, _, payload, status ->
        if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
            Log.w(TAG, "Incoming message status $status")

            return@IQApplicationEventListener
        }

        val command = extractCommand(payload)

        if (command == null) {
            Log.w(TAG, "Unrecognised payload: $payload")

            return@IQApplicationEventListener
        }

        onCommand?.invoke(command)
    }

    /**
     * The treadmill app's payload is structured data rather than a single command, so it is passed
     * through untouched for TreadmillReceiver to pick apart.
     */
    private val treadmillListener = ConnectIQ.IQApplicationEventListener { device, _, payload, status ->
        if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
            Log.w(TAG, "Incoming treadmill message status $status")

            return@IQApplicationEventListener
        }

        if (device == null || payload == null) return@IQApplicationEventListener

        onTreadmillMessage?.invoke(device, payload)
    }

    fun start() {
        // false: never let the SDK raise its "install Garmin Connect" dialog, since a service
        // cannot legally start an activity from the background. The UI reports the error instead.
        connectIQ.initialize(context, false, sdkListener)
    }

    fun stop() {
        runCatching { connectIQ.unregisterAllForEvents() }
        runCatching { connectIQ.shutdown(context) }

        devices.clear()

        initialized = false
    }

    fun refreshDevices() {
        if (!initialized) return

        val found = ArrayList<IQDevice>()

        try {
            connectIQ.knownDevices?.let { found.addAll(it) }
        } catch (e: Exception) {
            Log.w(TAG, "getKnownDevices failed", e)
        }

        if (found.isEmpty()) {
            try {
                connectIQ.connectedDevices?.let { found.addAll(it) }
            } catch (e: Exception) {
                Log.w(TAG, "getConnectedDevices failed", e)
            }
        }

        devices.clear()
        devices.addAll(found)

        for (device in devices) {
            device.status = runCatching { connectIQ.getDeviceStatus(device) }.getOrNull()
                ?: IQDevice.IQDeviceStatus.UNKNOWN

            runCatching { connectIQ.registerForDeviceEvents(device, deviceListener) }
                .onFailure { Log.w(TAG, "registerForDeviceEvents failed", it) }

            runCatching { connectIQ.registerForAppEvents(device, app, appListener) }
                .onFailure { Log.w(TAG, "registerForAppEvents failed", it) }

            // Registered on every known device, not just the Edge: the Forerunner shows up in the
            // same list, and registering for an app a device does not have is harmless.
            runCatching { connectIQ.registerForAppEvents(device, treadmillApp, treadmillListener) }
                .onFailure { Log.w(TAG, "registerForAppEvents (treadmill) failed", it) }
        }

        publishDeviceState()
    }

    fun send(payload: Map<String, Any>) {
        if (!initialized) return

        val connected = devices.filter { it.status == IQDevice.IQDeviceStatus.CONNECTED }

        val targets: List<IQDevice> = if (connected.isEmpty()) devices.toList() else connected

        for (device in targets) {
            runCatching {
                connectIQ.sendMessage(device, app, payload) { _, _, status ->
                    if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
                        Log.w(TAG, "Send to ${device.friendlyName} returned $status")
                    }
                }
            }.onFailure { Log.w(TAG, "sendMessage failed", it) }
        }
    }

    /** Reply to the watch that sent a run, rather than broadcasting to every known device. */
    fun sendTreadmill(device: IQDevice, payload: Map<String, Any>) {
        if (!initialized) return

        runCatching {
            connectIQ.sendMessage(device, treadmillApp, payload) { _, _, status ->
                if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
                    Log.w(TAG, "Treadmill reply to ${device.friendlyName} returned $status")
                }
            }
        }.onFailure { Log.w(TAG, "sendMessage (treadmill) failed", it) }
    }

    private fun publishDeviceState() {
        val connected = devices.firstOrNull { it.status == IQDevice.IQDeviceStatus.CONNECTED }

        RemoteState.knownDeviceCount = devices.size
        RemoteState.deviceConnected = connected != null
        RemoteState.deviceName = (connected ?: devices.firstOrNull())?.friendlyName

        onLinkChanged?.invoke()
    }

    /**
     * The Edge sends {"cmd": "..."}, but the SDK hands it over as a List<Any> whose shape depends on
     * how Monkey C serialised it — a bare map, a map nested in a list, or a plain string. Walk it.
     */
    private fun extractCommand(payload: List<Any>?): String? {
        if (payload == null) return null

        for (item in payload) {
            val command = scan(item, 0)

            if (command != null) return command
        }

        return null
    }

    private fun scan(item: Any?, depth: Int): String? {
        if (item == null || depth > 4) return null

        when (item) {
            is Map<*, *> -> {
                for ((key, value) in item) {
                    if (key?.toString()?.equals("cmd", ignoreCase = true) == true) {
                        return normalise(value?.toString())
                    }
                }

                for (value in item.values) {
                    val nested = scan(value, depth + 1)

                    if (nested != null) return nested
                }
            }

            is Iterable<*> -> {
                for (value in item) {
                    val nested = scan(value, depth + 1)

                    if (nested != null) return nested
                }
            }

            is String -> return normalise(item)
        }

        return null
    }

    private fun normalise(raw: String?): String? {
        val candidate = raw?.trim()?.lowercase() ?: return null

        return if (candidate in MediaControl.COMMANDS) candidate else null
    }
}
