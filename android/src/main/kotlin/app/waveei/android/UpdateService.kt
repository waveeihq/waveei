package app.waveei.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Downloads and installs an update the user started, as a foreground service, so it finishes after they
 * leave the app: Android does not stop it to free memory.
 * Progress and errors go to [Updater.install].
 * The app never asks for notifications, so Android shows the service only in its list of running apps.
 */
class UpdateService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val versionCode = intent?.getLongExtra(EXTRA_VERSION, 0L) ?: 0L
        val url = intent?.getStringExtra(EXTRA_URL)
        val name = intent?.getStringExtra(EXTRA_NAME) ?: versionCode.toString()
        val start = runCatching { startForeground(NOTIFICATION_ID, notification(name), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
            .onFailure { DebugLog.add("update: service could not start: ${it.javaClass.simpleName}: ${it.message}") }
        if (start.isFailure || url == null || running) {
            if (!running) {
                // Nothing downloads: the screen is told, so it does not wait on "Downloading" for good.
                Updater.install = Updater.Install.Failed(start.exceptionOrNull()?.message ?: "no download link")
                stopSelf()
            }
            return START_NOT_STICKY
        }
        running = true
        val release = Updater.Release(versionCode, url, name)
        Thread {
            val result = runCatching {
                val updater = Updater(this)
                val apk = updater.download(release) { read, total -> main.post { Updater.install = Updater.Install.Downloading(read, total) } }
                main.post { Updater.install = Updater.Install.Installing }
                // Installing ends the app's process, the keyboard's and the dots' with it: a dictation going on ends first.
                var waited = 0L
                while (Dictation.state != Dictation.State.IDLE && waited < INSTALL_WAIT_MS) {
                    Thread.sleep(INSTALL_POLL_MS)
                    waited += INSTALL_POLL_MS
                }
                DebugLog.add("update: build $versionCode downloaded, installing${if (waited > 0) " after a dictation, ${waited / 1000} s" else ""}")
                updater.install(apk)
            }
            main.post {
                result.onFailure {
                    val message = when (it) {
                        is UnknownHostException, is ConnectException, is SocketTimeoutException -> getString(R.string.update_no_network)
                        else -> it.message ?: it.javaClass.simpleName
                    }
                    Updater.install = Updater.Install.Failed(message)
                    DebugLog.add("update: install of build $versionCode failed: ${it.message}")
                }
                running = false
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    /** Android 15+: dataSync services get 6 hours a day; past that the service must stop within seconds. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        DebugLog.add("update: service timed out, stopping")
        stopSelf()
    }

    private fun notification(name: String): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.updates), NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.downloading_version, name))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 3
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"

        /** How long an install waits for a dictation to end, looking every 2 s: a long one, not one left running. */
        private const val INSTALL_WAIT_MS = 30 * 60_000L
        private const val INSTALL_POLL_MS = 2_000L

        fun start(context: Context, release: Updater.Release) {
            context.startForegroundService(
                Intent(context, UpdateService::class.java)
                    .putExtra(EXTRA_VERSION, release.versionCode)
                    .putExtra(EXTRA_URL, release.apkUrl)
                    .putExtra(EXTRA_NAME, release.name),
            )
        }
    }
}
