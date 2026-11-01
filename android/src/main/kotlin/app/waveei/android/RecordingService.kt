package app.waveei.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps the microphone while the keyboard or the dot is hidden.
 *
 * The keyboard holds the microphone only while its window is visible. A foreground service of type
 * "microphone" started while it is still visible keeps that right after it hides, so a dictation goes on
 * when the user switches apps. It runs from the start of a recording until the dictation is idle again.
 * The app does not ask to post notifications, so Android lists the service in its Task Manager only, not in the
 * notification drawer.
 */
class RecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Dictation.stop()
            return START_NOT_STICKY
        }
        val result = runCatching { startForeground(NOTIFICATION_ID, notification(this, Dictation.state == Dictation.State.PAUSED), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
        result.onFailure {
            DebugLog.add("recording service: could not start: ${it.javaClass.simpleName}")
            running = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "app.waveei.android.STOP"
        private var running = false

        /** The ongoing notification; paused, it says so, as the dictation then waits for the person. */
        private fun notification(context: Context, paused: Boolean): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW),
            )
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, "BUFFER"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, MainActivity.splashless(),
            )
            val stop = PendingIntent.getService(context, 1, Intent(context, RecordingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(context.getString(if (paused) R.string.recording_paused_title else R.string.recording_title))
                .setContentText(context.getString(R.string.recording_text))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, context.getString(R.string.stop), stop).build())
                .build()
        }

        /** Whether the service holds the microphone, so a recording survives the keyboard hiding. */
        val active: Boolean get() = running

        /**
         * Follows the dictation state: on when a recording starts, off when the dictation is idle.
         *
         * Android lets a keyboard start a microphone service only while our Accessibility service is on;
         * without it the start throws, and a service started but never put in the foreground can crash the
         * app. So without Accessibility the service is not started at all.
         */
        fun follow(context: Context, state: Dictation.State) {
            when {
                state == Dictation.State.RECORDING && !running && DotService.instance == null -> {
                    DebugLog.add("recording service: skipped, Accessibility is off")
                }
                state == Dictation.State.RECORDING && !running -> {
                    running = runCatching { context.startForegroundService(Intent(context, RecordingService::class.java)) }
                        .onFailure { DebugLog.add("recording service: start refused: ${it.javaClass.simpleName}") }
                        .isSuccess
                }
                running && state != Dictation.State.PROCESSING && state != Dictation.State.IDLE -> runCatching {
                    context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(context, state == Dictation.State.PAUSED))
                }
                state == Dictation.State.IDLE && running -> {
                    running = false
                    context.stopService(Intent(context, RecordingService::class.java))
                }
            }
        }
    }
}
