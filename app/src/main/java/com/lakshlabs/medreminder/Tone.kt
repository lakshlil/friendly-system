package com.lakshlabs.medreminder

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import java.io.File

/**
 * The alarm sound. A picked sound is copied into the app's own (device-protected) storage, because playing
 * the original link at alarm time can fail: sounds in shared storage need a media permission, and nothing in
 * shared storage can be read after a restart until the phone is unlocked. That failure used to fall back to
 * the phone's default ring without telling anyone.
 */
object Tone {
    fun file(c: Context) = File((c.createDeviceProtectedStorageContext() ?: c).filesDir, "alarm_tone")
    fun hasCustom(c: Context) = file(c).length() > 0

    fun title(c: Context): String =
        Store.alarmToneTitle(c).ifEmpty { if (hasCustom(c)) "Custom sound" else "Default alarm tone" }

    /** Copies the sound behind [uri]. Returns null on success, or the exception that stopped it. */
    fun import(c: Context, uri: Uri, title: String): Exception? = try {
        val tmp = File(file(c).path + ".tmp")
        (c.contentResolver.openInputStream(uri) ?: throw IllegalStateException("no data")).use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        if (tmp.length() == 0L) throw IllegalStateException("empty file")
        if (!tmp.renameTo(file(c))) throw IllegalStateException("couldn't save")
        Store.setAlarmTone(c, uri.toString()); Store.setAlarmToneTitle(c, title)
        Store.log(c, "Alarm sound set to \"$title\" (${file(c).length() / 1024} KB copied)")
        null
    } catch (e: Exception) {
        Store.log(c, "Couldn't copy sound $uri (${e.javaClass.simpleName}: ${e.message})")
        e
    }

    fun useDefault(c: Context) {
        file(c).delete(); Store.setAlarmTone(c, null); Store.setAlarmToneTitle(c, "")
        Store.log(c, "Alarm sound set to the phone's default")
    }

    fun titleOf(c: Context, uri: Uri): String = try {
        if (uri.scheme == "content" && uri.authority != "media" && uri.authority != "settings")
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.substringBeforeLast('.') else null
            }
        else RingtoneManager.getRingtone(c, uri)?.getTitle(c)
    } catch (_: Exception) { null } ?: "Chosen sound"

    fun isDefault(uri: Uri?) = uri == null || uri.authority == "settings"

    /** Sounds to try, best first: the copied sound, the original link, then whatever the phone has. */
    fun candidates(c: Context): List<Uri> = listOfNotNull(
        if (hasCustom(c)) Uri.fromFile(file(c)) else null,
        Store.alarmTone(c)?.let { Uri.parse(it) },
        safe { RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_ALARM) },
        safe { firstSystemSound(c, RingtoneManager.TYPE_ALARM) },
        Settings.System.DEFAULT_ALARM_ALERT_URI,
        safe { RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_RINGTONE) },
        safe { firstSystemSound(c, RingtoneManager.TYPE_RINGTONE) },
        safe { RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_NOTIFICATION) },
    ).distinct()

    /** Starts the first playable sound on the alarm stream. Returns the player and what it is playing. */
    fun start(c: Context, looping: Boolean): Pair<MediaPlayer, Uri>? {
        for (uri in candidates(c)) {
            var p: MediaPlayer? = null
            try {
                p = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(c, uri)
                    isLooping = looping
                    prepare(); start()
                }
                Store.log(c, "Playing ${describe(c, uri)}")
                return p to uri
            } catch (e: Exception) {
                p?.release()
                Store.log(c, "Can't play ${describe(c, uri)} (${e.javaClass.simpleName})")
            }
        }
        return null
    }

    fun describe(c: Context, uri: Uri) = if (uri.scheme == "file") "your sound \"${title(c)}\"" else uri.toString()

    private fun firstSystemSound(c: Context, type: Int): Uri? {
        val rm = RingtoneManager(c).apply { setType(type) }
        val cur = rm.cursor
        return if (cur != null && cur.moveToFirst()) rm.getRingtoneUri(0) else null
    }

    private fun <T> safe(f: () -> T?): T? = try { f() } catch (_: Exception) { null }
}
