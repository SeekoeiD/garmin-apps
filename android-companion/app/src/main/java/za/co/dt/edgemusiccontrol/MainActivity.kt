package za.co.dt.edgemusiccontrol

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity(), RemoteState.Listener {

    companion object {

        private const val REQUEST_NOTIFICATIONS = 1
    }

    private lateinit var notificationStatus: TextView
    private lateinit var notificationButton: Button
    private lateinit var batteryStatus: TextView
    private lateinit var batteryButton: Button
    private lateinit var serviceStatus: TextView
    private lateinit var serviceButton: Button
    private lateinit var tokenField: EditText
    private lateinit var tokenButton: Button
    private lateinit var tokenStatus: TextView
    private lateinit var treadmillStatus: TextView
    private lateinit var liveStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        notificationStatus = findViewById(R.id.notificationStatus)
        notificationButton = findViewById(R.id.notificationButton)
        batteryStatus = findViewById(R.id.batteryStatus)
        batteryButton = findViewById(R.id.batteryButton)
        serviceStatus = findViewById(R.id.serviceStatus)
        serviceButton = findViewById(R.id.serviceButton)
        tokenField = findViewById(R.id.tokenField)
        tokenButton = findViewById(R.id.tokenButton)
        tokenStatus = findViewById(R.id.tokenStatus)
        treadmillStatus = findViewById(R.id.treadmillStatus)
        liveStatus = findViewById(R.id.liveStatus)

        notificationButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        batteryButton.setOnClickListener { requestBatteryExemption() }

        serviceButton.setOnClickListener { toggleService() }

        tokenButton.setOnClickListener { saveTokens() }

        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()

        RemoteState.addListener(this)

        render()
    }

    override fun onPause() {
        RemoteState.removeListener(this)

        super.onPause()
    }

    override fun onRemoteStateChanged() {
        render()
    }

    private fun toggleService() {
        if (RemoteState.serviceRunning) {
            Prefs.setAutoStart(this, false)

            EdgeRemoteService.stop(this)
        } else {
            Prefs.setAutoStart(this, true)

            EdgeRemoteService.start(this)
        }

        // The service flips RemoteState asynchronously; repaint shortly after either way.
        serviceButton.postDelayed({ render() }, 500L)
    }

    /**
     * The pasted JSON is handed straight to Prefs and the field is cleared: the token text never
     * makes it into a log, a saved instance state, or anywhere else it could leak.
     */
    private fun saveTokens() {
        val raw = tokenField.text.toString()

        if (raw.isBlank()) return

        val saved = Prefs.saveGarminTokensJson(this, raw)

        if (saved) {
            tokenField.setText("")
        } else {
            Toast.makeText(this, R.string.tokens_rejected, Toast.LENGTH_LONG).show()
        }

        render()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) return

        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    @Suppress("BatteryLife")
    private fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))

        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun render() {
        val notificationAccess = hasNotificationAccess()

        notificationStatus.text = getString(
            if (notificationAccess) R.string.granted else R.string.not_granted
        )

        notificationButton.isEnabled = !notificationAccess

        val exempt = isBatteryExempt()

        batteryStatus.text = getString(if (exempt) R.string.exempted else R.string.not_exempted)

        batteryButton.isEnabled = !exempt

        serviceStatus.text = getString(
            if (RemoteState.serviceRunning) R.string.running else R.string.stopped
        )

        serviceButton.setText(
            if (RemoteState.serviceRunning) R.string.stop_service else R.string.start_service
        )

        tokenStatus.setText(
            if (Prefs.hasGarminTokens(this)) R.string.tokens_saved else R.string.tokens_missing
        )

        val lastRun = RemoteState.lastRunStatus ?: Prefs.lastRunStatus(this)

        treadmillStatus.text = if (lastRun == null) {
            getString(R.string.no_runs_yet)
        } else {
            "Last upload: $lastRun"
        }

        liveStatus.text = buildStatusText()
    }

    private fun buildStatusText(): String {
        val lines = StringBuilder()

        val sdk = when {
            RemoteState.sdkError != null -> "error: ${RemoteState.sdkError}"
            RemoteState.sdkReady -> "ready"
            RemoteState.serviceRunning -> "connecting…"
            else -> "not started"
        }

        lines.append("Connect IQ SDK : ").append(sdk).append('\n')

        val device = RemoteState.deviceName ?: "none found"
        val link = if (RemoteState.deviceConnected) "connected" else "not connected"

        lines.append("Garmin device  : ").append(device).append('\n')
        lines.append("Device link    : ").append(link)
            .append(" (").append(RemoteState.knownDeviceCount).append(" known)").append('\n')

        val command = RemoteState.lastCommand

        if (command == null) {
            lines.append("Last command   : none yet").append('\n')
        } else {
            val when_ = DateUtils.getRelativeTimeSpanString(
                RemoteState.lastCommandAt,
                System.currentTimeMillis(),
                DateUtils.SECOND_IN_MILLIS
            )

            lines.append("Last command   : ").append(command).append(" (").append(when_).append(")")
                .append('\n')
        }

        lines.append("Last reported  : ").append(RemoteState.lastReport ?: "nothing yet")

        return lines.toString()
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false

        val expected = packageName + "/" + MusicNotificationListener::class.java.name

        return enabled.split(':').any { it == expected || it.startsWith("$packageName/") }
    }

    private fun isBatteryExempt(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager

        return power.isIgnoringBatteryOptimizations(packageName)
    }
}
