package app.waveei.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Looks for a new version twice a day with the app closed, and says it once per version in a notification: the only
 * one the app shows by itself. Off with About's Check automatically, or Android's notification permission refused.
 */
class UpdateCheckJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val prefs = Prefs(this)
        if (!prefs.autoUpdateCheck) return false
        Thread {
            runCatching { Updater(this).latest() }
                .onSuccess { release ->
                    val installed = packageManager.getPackageInfo(packageName, 0).longVersionCode
                    // Not marked as told while notifications are off: allowed later, the version is still told.
                    val shown = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
                    if (shown && release != null && release.versionCode > installed && release.versionCode > prefs.notifiedVersion) {
                        prefs.notifiedVersion = release.versionCode
                        notify(this)
                        DebugLog.add("update: new version notified")
                    }
                }
                .onFailure { DebugLog.add("update: background check failed: ${it.javaClass.simpleName}") }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 7
        private const val CHANNEL = "new_version"
        private const val NOTIFICATION_ID = 4
        private const val PERIOD_MS = 12 * 3_600_000L

        /** Once, kept over restarts; already scheduled, its period is left as it is. */
        fun schedule(context: Context) {
            val jobs = context.getSystemService(JobScheduler::class.java)
            if (jobs.getPendingJob(JOB_ID) != null) return
            jobs.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
                    .setPeriodic(PERIOD_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build(),
            )
        }

        /** "New update available", no version number; a tap opens About, where Install is. */
        private fun notify(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.new_versions), NotificationManager.IMPORTANCE_DEFAULT))
            val open = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SCREEN, "ABOUT")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val tap = PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, MainActivity.splashless())
            manager.notify(
                NOTIFICATION_ID,
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(context.getString(R.string.new_version_out))
                    .setContentText(context.getString(R.string.new_version_tap))
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}
