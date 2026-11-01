package app.waveei.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Updates from the GitHub releases of the app's own repository, which CI publishes for each signed build of main,
 * tagged "build-<versionCode>" and titled with its version name. Nothing about the person goes with the check: GitHub
 * sees a request for the latest release. Blocking calls: run them off the main thread.
 */
class Updater(private val context: Context) {
    /** [name] is the version people see, as About shows the app's own. */
    data class Release(val versionCode: Long, val apkUrl: String, val name: String)

    /** Where an update is, shown in About: one tap on Install downloads it and installs it. */
    sealed interface Install {
        /** [total] is -1 while the size is not known. */
        data class Downloading(val read: Long, val total: Long) : Install

        /** The APK is being handed to the system installer. */
        data object Installing : Install

        data class Failed(val message: String) : Install
    }

    private val client = OkHttpClient()

    /** The latest release, or null when it has no APK. */
    fun latest(): Release? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .api()
            .header("Accept", "application/vnd.github+json")
            .build()
        val json = client.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> JSONObject(response.body.string())
                404 -> throw IOException("No release yet")
                else -> throw IOException("GitHub error (HTTP ${response.code})")
            }
        }
        val versionCode = json.getString("tag_name").removePrefix("build-").toLongOrNull() ?: return null
        // Releases before version names were titled "Build <code>": their code stands in.
        val name = json.optString("name").takeIf { it.firstOrNull()?.isDigit() == true } ?: versionCode.toString()
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk")) return Release(versionCode, asset.getString("url"), name)
        }
        return null
    }

    /**
     * Downloads the APK, reporting bytes read and the total size (-1 when not known) as it goes.
     * GitHub redirects to its file storage. A build downloaded whole before
     * is kept, so another tap after a cancelled install installs it again without downloading.
     */
    fun download(release: Release, onProgress: (read: Long, total: Long) -> Unit): File {
        val done = File(context.cacheDir, "update-${release.versionCode}.apk")
        if (done.exists()) {
            DebugLog.add("update: build ${release.versionCode} already downloaded")
            return done
        }
        context.cacheDir.listFiles { it.name.startsWith("update") }?.forEach { it.delete() }
        val request = Request.Builder().url(release.apkUrl).api().header("Accept", "application/octet-stream").build()
        val file = File(context.cacheDir, "update.part")
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> throw IOException("The update file is not there any more (HTTP 404)")
                    !response.isSuccessful -> throw IOException("Download failed (HTTP ${response.code})")
                }
                val total = response.body.contentLength()
                var read = 0L
                var reported = -1L
                onProgress(0, total)
                response.body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            read += count
                            // About a hundred updates per download, so the screen is not redrawn for every chunk.
                            val step = if (total > 0) read * 100 / total else read / (256 * 1024)
                            if (step != reported) {
                                reported = step
                                onProgress(read, total)
                            }
                        }
                    }
                }
                if (total > 0 && read != total) throw IOException("Download cut off at $read of $total bytes")
            }
        } catch (e: IOException) {
            file.delete()
            throw e
        }
        if (!file.renameTo(done)) throw IOException("Could not keep the downloaded update")
        return done
    }

    /**
     * Hands the APK to the system installer, only on the user's tap. The app updates itself and declares
     * UPDATE_PACKAGES_WITHOUT_USER_ACTION, so Android installs it without asking; if it still wants a
     * confirmation, [InstallResultReceiver] shows it.
     */
    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    /**
     * GitHub's API: the public repository's read without a sign-in; a developer build's private one with the read-only
     * token CI gave it (UPDATE_TOKEN), which OkHttp drops on the redirect to GitHub's file storage.
     */
    private fun Request.Builder.api() = header("X-GitHub-Api-Version", "2022-11-28").apply {
        if (BuildConfig.UPDATE_TOKEN.isNotEmpty()) header("Authorization", "Bearer ${BuildConfig.UPDATE_TOKEN}")
    }

    companion object {
        /** The install in progress or its error; snapshot state, so the screens follow it. Null when idle. */
        var install by mutableStateOf<Install?>(null)

        /** A system confirmation the installer asked for while the app was in the background; shown on return. */
        @Volatile
        var confirm: Intent? = null
    }
}

/** Receives the installer's result and shows the system confirmation screen. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            DebugLog.add("update: Android asks to confirm the install")
            // Android blocks a screen opened from the background, so it waits for the app to come back.
            if (MainActivity.resumed) confirm?.let { context.startActivity(it) } else Updater.confirm = confirm
            Updater.install = null
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            DebugLog.add("update: installed without asking")
        } else {
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            DebugLog.add("update: install failed, status $status $message")
            Updater.install = Updater.Install.Failed(
                if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "Install cancelled" else "Install failed: ${message ?: "status $status"}",
            )
        }
    }
}
