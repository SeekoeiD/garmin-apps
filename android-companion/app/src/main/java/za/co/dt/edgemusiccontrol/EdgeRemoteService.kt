package za.co.dt.edgemusiccontrol

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Long-lived host for the Connect IQ registration. It has to be a foreground service: the SDK
 * delivers messages only to a live in-process listener, so if this process dies the Edge's button
 * presses go nowhere.
 */
class EdgeRemoteService : Service() {

    companion object {

        const val ACTION_STOP = "za.co.dt.edgemusiccontrol.action.STOP"

        private const val CHANNEL_ID = "edge_remote"
        private const val NOTIFICATION_ID = 0x0830
        private const val STATUS_MIN_INTERVAL_MS = 2_000L
        private const val TAG = "EdgeRemoteService"

        fun start(context: Context) {
            val intent = Intent(context, EdgeRemoteService::class.java)

            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, EdgeRemoteService::class.java).setAction(ACTION_STOP)

            context.startService(intent)
        }
    }

    private lateinit var mediaControl: MediaControl

    private lateinit var garminLink: GarminLink

    private val handler = Handler(Looper.getMainLooper())

    private var lastStatusSentAt = 0L

    private var trailingSendQueued = false

    private val trailingSend = Runnable {
        trailingSendQueued = false

        sendStatus(force = true)
    }

    override fun onCreate() {
        super.onCreate()

        createChannel()
        startInForeground()

        mediaControl = MediaControl(this)

        mediaControl.onPlaybackChanged = {
            sendStatus(force = false)

            RemoteState.notifyChanged()
        }

        garminLink = GarminLink(this)

        garminLink.onCommand = { command -> handleCommand(command) }

        garminLink.onLinkChanged = {
            updateNotification()

            RemoteState.notifyChanged()
        }

        mediaControl.startWatching()
        garminLink.start()

        RemoteState.serviceRunning = true

        RemoteState.notifyChanged()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()

            return START_NOT_STICKY
        }

        garminLink.refreshDevices()

        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(trailingSend)

        mediaControl.stopWatching()
        garminLink.stop()

        RemoteState.reset()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleCommand(command: String) {
        val handled = mediaControl.execute(command)

        Log.i(TAG, "Command $command handled=$handled")

        RemoteState.lastCommand = command
        RemoteState.lastCommandAt = System.currentTimeMillis()

        // Volume and transport changes settle asynchronously in the media app; give it a beat
        // before sampling the state we report back.
        handler.postDelayed({ sendStatus(force = true) }, 250L)

        updateNotification()

        RemoteState.notifyChanged()
    }

    /**
     * Unforced sends are rate-limited to one per STATUS_MIN_INTERVAL_MS, with a trailing send so the
     * Edge still ends up with the final state after a burst of metadata changes.
     */
    private fun sendStatus(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastStatusSentAt

        if (!force && elapsed < STATUS_MIN_INTERVAL_MS) {
            if (!trailingSendQueued) {
                trailingSendQueued = true

                handler.postDelayed(trailingSend, STATUS_MIN_INTERVAL_MS - elapsed)
            }

            return
        }

        if (force && trailingSendQueued) {
            trailingSendQueued = false

            handler.removeCallbacks(trailingSend)
        }

        lastStatusSentAt = now

        val payload = mediaControl.snapshot()

        RemoteState.lastReport = "${payload["state"]} · ${payload["track"]} · ${payload["vol"]}%"

        garminLink.send(payload)
    }

    private fun startInForeground() {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), foregroundType())
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)

        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    /**
     * connectedDevice is the honest type, but on API 34 it is only permitted while BLUETOOTH_CONNECT
     * is actually granted — declaring it is not enough, and starting without the grant throws
     * SecurityException. specialUse has no such prerequisite, so it is the fallback.
     */
    private fun foregroundType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0

        val bluetoothGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

        return if (bluetoothGranted) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(notificationText())
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun notificationText(): String {
        val error = RemoteState.sdkError

        if (error != null) return "Connect IQ: $error"

        if (!RemoteState.sdkReady) return "Connecting to Garmin Connect…"

        val name = RemoteState.deviceName ?: "no device"

        return if (RemoteState.deviceConnected) "$name connected" else "$name not connected"
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_MIN
        )

        channel.setShowBadge(false)

        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
