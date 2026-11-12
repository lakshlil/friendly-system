package com.lakshlabs.medreminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

/**
 * Over-the-air updates without the Play Store.
 *
 * A release publishes `update.json` ({versionCode, versionName, apk, sha256, notes}) next to the APK
 * (see .github/workflows/release.yml). The app checks it every few hours and when opened, downloads a newer
 * APK, verifies its SHA-256, and hands it to Android's PackageInstaller. From Android 12, once this app has
 * installed one update itself it becomes its own installer and later updates go in silently in the background;
 * the first one (or older Android) asks for one tap.
 */
object Updater {
    private const val JOB_ID = 4201
    private const val CHANNEL = "updates"
    private val main = Handler(Looper.getMainLooper())

    /** Tests can point this at a local server. */
    @Volatile var urlOverride: String? = null
    val url: String get() = urlOverride ?: BuildConfig.UPDATE_URL

    /** Background check every 6 hours on any network. Safe to call repeatedly. */
    fun schedule(c: Context) {
        if (url.isEmpty()) return
        val js = c.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_ID) != null) return
        // Never let the update check take the app down: on failure, just log it.
        try {
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(c, UpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(6 * 3_600_000L)
                .setPersisted(true)
                .build())
        } catch (e: Exception) { Store.log(c, "Couldn't schedule update checks (${e.javaClass.simpleName}: ${e.message})") }
    }

    /** Check in the background, then call [done] on the main thread with a status line. */
    fun check(c: Context, interactive: Boolean, done: (String) -> Unit = {}) {
        val app = c.applicationContext
        thread { val s = checkBlocking(app, interactive); main.post { done(s) } }
    }

    private fun stamp() = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date())

    fun checkBlocking(c: Context, interactive: Boolean): String {
        if (!interactive && !Store.autoUpdate(c)) return Store.updateStatus(c)
        if (url.isEmpty()) return "Update server not set up in this build".also { Store.setUpdateStatus(c, it) }
        Store.setLastUpdateCheck(c, System.currentTimeMillis())
        val status = try {
            val info = JSONObject(get(url))
            val remote = info.getLong("versionCode")
            val name = info.optString("versionName", remote.toString())
            if (remote <= currentCode(c)) "Up to date (checked ${stamp()})"
            else {
                val apk = download(c, info.getString("apk"), info.optString("sha256"))
                val archive = c.packageManager.getPackageArchiveInfo(apk.path, 0)
                if (archive == null || archive.packageName != c.packageName) "Downloaded file isn't this app, ignored"
                else { install(c, apk, name); "Installing $name…" }
            }
        } catch (e: Exception) {
            "Couldn't check for updates (${e.javaClass.simpleName}${e.message?.let { ": " + it.take(60) } ?: ""})"
        }
        Store.setUpdateStatus(c, status)
        Store.log(c, "Update check: $status")
        return status
    }

    fun currentCode(c: Context): Long {
        val p = c.packageManager.getPackageInfo(c.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
    }

    private fun get(u: String): String {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 20_000; conn.useCaches = false
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
    }

    private fun download(c: Context, u: String, sha256: String): File {
        val dir = File(c.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = File(dir, "update.apk")
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 60_000
        val md = MessageDigest.getInstance("SHA-256")
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n); out.write(buf, 0, n) }
            } }
        } finally { conn.disconnect() }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (sha256.isNotEmpty() && !got.equals(sha256, ignoreCase = true)) { f.delete(); throw SecurityException("checksum mismatch") }
        return f
    }

    private fun install(c: Context, apk: File, name: String) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("app.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(c, id, Intent(c, InstallReceiver::class.java).putExtra("name", name), flags)
            s.commit(status.intentSender)
        }
    }

    /** Shown when Android wants a tap (first update, or "install unknown apps" not yet allowed). */
    fun notifyNeedsTap(c: Context, name: String, intent: Intent) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(c, 77, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle("Update $name ready").setContentText("Tap to install. Later updates install by themselves.")
            .setContentIntent(pi).setAutoCancel(true).build()
        try { nm.notify(7003, n) } catch (_: SecurityException) { }
    }

    fun canInstall(c: Context) = c.packageManager.canRequestPackageInstalls()
    fun allowInstallsIntent(c: Context) = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
}

/** Result of an install session. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val name = i.getStringExtra("name") ?: "update"
        when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                Store.setUpdateStatus(c, "Update $name downloaded. Tap the notification to install.")
                Updater.notifyNeedsTap(c, name, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Store.setUpdateStatus(c, "Updated to $name")
            else -> {
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $st"
                Store.setUpdateStatus(c, "Update $name failed: $msg")
                Store.log(c, "Update failed: $msg")
            }
        }
    }
}

class UpdateJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        thread { Sync.syncBlocking(applicationContext); Updater.checkBlocking(applicationContext, false); jobFinished(p, false) }
        return true
    }
    override fun onStopJob(p: JobParameters) = true
}
