package za.co.dt.edgemusiccontrol

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.round

/**
 * Receives a recorded treadmill run from the Forerunner app, rebuilds it as a FIT file and uploads
 * it to Garmin Connect.
 *
 * The watch sends the run in parts at save time and retries anything it is unsure of, so every entry
 * point here has to tolerate repeats: a part that arrives twice overwrites itself harmlessly, and a
 * whole run re-sent after a successful upload is answered from the stored result rather than
 * uploaded again. Garmin would reject the duplicate anyway, but the watch deserves a real answer.
 *
 * The Connect IQ SDK hands device messages over as plain Java containers, so nothing about the
 * incoming types can be assumed — numbers arrive as Integer or Double depending on how Monkey C
 * serialised them.
 */
class TreadmillReceiver(private val context: Context) {

    companion object {

        const val CHANNEL_ID = "treadmill_uploads"

        private const val NOTIFICATION_ID = 0x0965
        private const val TAG = "TreadmillReceiver"
    }

    private val buffer = RunBuffer(File(context.filesDir, "treadmill"))

    // One worker, so parts of the same run are handled in arrival order and two runs can never be
    // uploaded concurrently. Disk and network work both happen here, never on the main thread.
    private val worker = Executors.newSingleThreadExecutor()

    private val handler = Handler(Looper.getMainLooper())

    /** Handle one incoming Connect IQ payload. The reply goes back on the main thread. */
    fun onMessage(payload: List<Any>?, reply: (Map<String, Any>) -> Unit) {
        val message = TreadmillMessage.find(payload)

        if (message == null) {
            Log.w(TAG, "Ignoring payload that is not a ${TreadmillMessage.TYPE_RUN} part")

            return
        }

        val key = TreadmillMessage.number(message["k"])?.toLong()

        if (key == null) {
            Log.w(TAG, "Run part without a usable key")

            return
        }

        val index = TreadmillMessage.integer(message["i"]) ?: 0
        val parts = TreadmillMessage.integer(message["n"]) ?: 0

        // Connect IQ can deliver a message while the service is being torn down, and a shut-down
        // executor rejects rather than ignores.
        runCatching {
            worker.execute {
                runCatching { handlePart(key, index, parts, message, reply) }
                    .onFailure { error ->
                        Log.w(TAG, "Run $key part $index failed", error)

                        respond(reply, failure(key, error.javaClass.simpleName))
                    }
            }
        }.onFailure { Log.w(TAG, "Run $key part $index dropped: receiver is shutting down") }
    }

    fun shutdown() {
        worker.shutdownNow()
    }

    private fun handlePart(
        key: Long,
        index: Int,
        parts: Int,
        message: Map<*, *>,
        reply: (Map<String, Any>) -> Unit
    ) {
        val alreadyUploaded = Prefs.uploadedRun(context, key)

        // A re-sent run that Garmin already has: answer from the stored result, upload nothing.
        if (alreadyUploaded != null) {
            Log.i(TAG, "Run $key already uploaded; replying from the stored result")

            respond(reply, success(key, alreadyUploaded.first, alreadyUploaded.second))

            return
        }

        if (index == 0) {
            val meta = TreadmillMessage.meta(key, parts, message)

            if (meta == null) {
                respond(reply, failure(key, "bad header"))

                return
            }

            buffer.saveMeta(meta)
        } else {
            buffer.saveHeartRate(key, index, TreadmillMessage.heartRate(message["hr"]))
        }

        if (!buffer.isComplete(key)) {
            Log.i(TAG, "Run $key still waiting for parts")

            return
        }

        process(key, reply)
    }

    private fun process(key: Long, reply: (Map<String, Any>) -> Unit) {
        val assembled = buffer.assemble(key)

        if (assembled == null) {
            respond(reply, failure(key, "incomplete"))

            return
        }

        val (meta, samples) = assembled

        if (samples.isEmpty()) {
            buffer.discard(key)

            respond(reply, failure(key, "empty run"))

            return
        }

        val (fit, totals) = RunBuilder.buildFit(
            samples,
            meta.start,
            meta.altitude ?: RunBuilder.DEFAULT_ALTITUDE,
            RunBuilder.TZ_OFFSET_SECONDS
        )

        val distance = round(totals.distance).toInt()
        val ascent = round(totals.ascent).toInt()

        val error = GarminUpload.upload(context, "treadmill_$key.fit", fit)

        if (error != null) {
            Log.w(TAG, "Run $key upload rejected: $error")

            record(key, "failed — $error")
            notify("Treadmill upload failed", error)

            respond(reply, failure(key, error))

            return
        }

        Prefs.setUploadedRun(context, key, distance, ascent)

        buffer.discard(key)

        val summary = describe(distance, ascent)

        Log.i(TAG, "Run $key uploaded: $summary")

        record(key, summary)
        notify("Treadmill run uploaded", summary)

        respond(reply, success(key, distance, ascent))
    }

    private fun success(key: Long, distance: Int, ascent: Int): Map<String, Any> {
        return TreadmillMessage.success(key, distance, ascent)
    }

    private fun failure(key: Long, reason: String): Map<String, Any> {
        return TreadmillMessage.failure(key, reason)
    }

    private fun respond(reply: (Map<String, Any>) -> Unit, message: Map<String, Any>) {
        handler.post { reply(message) }
    }

    private fun describe(distance: Int, ascent: Int): String {
        return String.format(Locale.US, "%.2f km, +%d m", distance / 1000.0, ascent)
    }

    private fun record(key: Long, outcome: String) {
        val status = "run $key: $outcome"

        Prefs.setLastRunStatus(context, status)

        RemoteState.lastRunStatus = status

        RemoteState.notifyChanged()
    }

    /**
     * The service's own channel is IMPORTANCE_MIN so its ongoing notification stays silent; an
     * upload result is worth actually seeing, so it gets its own channel at the default importance.
     */
    private fun notify(title: String, text: String) {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)
        }
    }
}
