package app.waveei.android

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.LocaleList
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/** What the event log adds about the app itself: its crashes, why its process ended last time, and the phone. */
object Diagnostics {
    /**
     * Catches crashes into the log before the process dies; the default handler runs after. Only the exceptions' kinds
     * and where they were thrown: an exception's message can quote what it was working on.
     */
    fun catchCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val trace = trace(error).take(MAX_TRACE)
            DebugLog.add("crash on ${thread.name}: $trace")
            Stats.fault("crash on ${thread.name}: $trace")
            DebugLog.flush()
            Trace.current?.flush()
            previous?.uncaughtException(thread, error)
        }
    }

    internal fun trace(error: Throwable): String = generateSequence(error) { it.cause }.take(MAX_CAUSES).joinToString("\ncaused by ") { e ->
        e.javaClass.name + e.stackTrace.take(MAX_FRAMES).joinToString("") { "\n  at $it" }
    }

    /** Why the app's process ended since the last start: crashes, ANRs, the system killing it, updates. */
    fun exitReasons(context: Context) {
        val c = context.applicationContext
        val store = c.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        val since = store.getLong("exits_reported", 0L)
        val exits = runCatching { c.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(null, 0, MAX_EXITS) }
            .getOrDefault(emptyList())
            .filter { it.timestamp > since }
        exits.sortedBy { it.timestamp }.forEach { exit ->
            DebugLog.add("exit: ${reasonName(exit.reason)} ${exit.description.orEmpty()}, importance ${exit.importance}, pss ${exit.pss / 1024} MB")
            // Where an ANR hung: the main thread's part of the trace Android kept.
            if (exit.reason == ApplicationExitInfo.REASON_ANR) runCatching { exit.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                ?.substringAfter("\n\"main\"", "")?.takeIf { it.isNotEmpty() }
                ?.let { Stats.fault("ANR: \"main\"${it.substringBefore("\n\n").take(MAX_TRACE)}") }
        }
        exits.maxOfOrNull { it.timestamp }?.let { store.edit().putLong("exits_reported", it).apply() }
    }

    /**
     * The phone as the app sees it, for Share app reports: its model, Android, screen, languages and memory, what the
     * app may do, the keyboards turned on and the one in use, and where the app was installed from.
     */
    fun phone(context: Context): String = runCatching {
        val c = context.applicationContext
        val screen = c.resources.displayMetrics
        val memory = ActivityManager.MemoryInfo().also { c.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val keyboards = c.getSystemService(InputMethodManager::class.java).enabledInputMethodList.joinToString { it.packageName }
        val current = KeyboardService.current(c)?.substringBefore('/')
        val mic = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifications = c.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val battery = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
        val installer = runCatching { c.packageManager.getInstallSourceInfo(c.packageName).installingPackageName }.getOrNull()
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
            "screen ${screen.widthPixels}x${screen.heightPixels} ${screen.densityDpi} dpi, font ${c.resources.configuration.fontScale}x, " +
            "languages ${LocaleList.getDefault().toLanguageTags()}, memory ${memory.totalMem / MB} MB, " +
            "mic $mic, notifications $notifications, battery unrestricted $battery, " +
            "keyboards [$keyboards], in use $current, installed by ${installer ?: "a file"}"
    }.getOrElse { "not read: ${it.javaClass.simpleName}" }

    /** The phone's details in the event log: shared with app reports, only their kind on the phone. */
    fun logPhone(context: Context) = DebugLog.add("phone: ${DebugLog.name(phone(context), "details shared with app reports")}")

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "too many resources"
        ApplicationExitInfo.REASON_SIGNALED -> "signal"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user stopped it"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user force-stopped it"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_OTHER -> "other (often an update)"
        else -> "reason $reason"
    }

    private const val MAX_EXITS = 10
    private const val MAX_TRACE = 12_000
    private const val MAX_CAUSES = 5
    private const val MAX_FRAMES = 30
    private const val MB = 1024 * 1024
}
