package com.lakshlabs.medreminder

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.*
import android.provider.Settings

/**
 * Rings until the dose is handled: loops an alarm tone, vibrates, posts the full-screen alarm notification
 * and opens the alarm screen. Every step is written to the alarm log (Settings → Developer mode).
 */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var beeper: ToneGenerator? = null
    private var restoreVolume = -1
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val NOTIF_ID = 7001
        const val ACTION_SILENCE = "SILENCE"
        const val CHANNEL = "alarm"
        private const val RING_MS = 120_000L

        fun stop(c: Context) {
            c.stopService(Intent(c, AlarmService::class.java))
            c.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        }

        /** Stops the sound but leaves the notification in the shade. */
        fun silence(c: Context) {
            try { c.startService(Intent(c, AlarmService::class.java).setAction(ACTION_SILENCE)) } catch (_: Exception) { }
        }

        fun screenIntent(c: Context, minute: Int) = Intent(c, AlarmActivity::class.java)
            .putExtra("minute", minute).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        /** The ongoing alarm notification. Also used by the backup alarm when the service can't start. */
        fun notification(c: Context, channel: String, minute: Int, pending: List<Dose>): Notification {
            val screen = PendingIntent.getActivity(c, 1, screenIntent(c, minute),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            fun action(name: String, req: Int) = PendingIntent.getBroadcast(c, 10 + req,
                Intent(c, ActionReceiver::class.java).setAction(name).putExtra("minute", minute),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_stat_pill)
                .setContentTitle("Time for your medicine")
                .setContentText(pending.joinToString(", ") { it.med.name })
                .setSubText(fmtTime(minute))
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setContentIntent(screen)
                .setFullScreenIntent(screen, true)
                .addAction(Notification.Action.Builder(null, "Taken", action("TAKEN", 1)).build())
                .addAction(Notification.Action.Builder(null, "Snooze ${Store.snoozeMinutes(c)} min", action("SNOOZE", 2)).build())
                .build()
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == ACTION_SILENCE) { endRinging(); return START_NOT_STICKY }
        val minute = i?.getIntExtra("minute", -1) ?: -1
        val pending = Store.pendingAt(this, minute)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Medicine alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings at dose time"; setSound(null, null); enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        val n = notification(this, CHANNEL, minute, pending)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            Store.log(this, "Service couldn't go foreground (${e.javaClass.simpleName}). Using backup alarm.")
            Notify.loudAlarm(this, minute, pending); stopSelf(); return START_NOT_STICKY
        }
        if (pending.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        if (player == null && beeper == null) {
            Store.log(this, "Ringing for ${pending.joinToString { it.med.name }} at ${fmtTime(minute)}")
            startSound(); handler.postDelayed({ endRinging() }, RING_MS)
            openScreen(minute)
        }
        return START_NOT_STICKY
    }

    /**
     * The full-screen intent only takes over a locked or idle screen. While the phone is in use, Android lets
     * the app open the alarm screen itself only with "Display over other apps" (or while the app is open).
     */
    private fun openScreen(minute: Int) {
        val overlay = Settings.canDrawOverlays(this)
        val fsi = Build.VERSION.SDK_INT < 34 || getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        Store.log(this, "Screen: display-over-apps ${if (overlay) "on" else "OFF"}, full-screen alarms ${if (fsi) "on" else "OFF"}")
        try { startActivity(screenIntent(this, minute)) } catch (e: Exception) { Store.log(this, "Couldn't open alarm screen (${e.javaClass.simpleName})") }
    }

    /** Tries the chosen tone, then every other sound the phone has, then plain beeps, so it is never silent. */
    private fun startSound() {
        val audio = getSystemService(AudioManager::class.java)
        if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, (audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) * 2 + 2) / 3, 0)
            restoreVolume = 0
            Store.log(this, "Alarm volume was 0, raised it while ringing")
        } catch (e: Exception) { Store.log(this, "Alarm volume is 0 and can't be raised (${e.javaClass.simpleName})") }
        val candidates = listOfNotNull(
            Store.alarmTone(this)?.let { Uri.parse(it) },
            safe { RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM) },
            safe { firstSystemSound(RingtoneManager.TYPE_ALARM) },
            safe { RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE) },
            safe { firstSystemSound(RingtoneManager.TYPE_RINGTONE) },
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            safe { RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION) },
        ).distinct()
        for (uri in candidates) {
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(this@AlarmService, uri)
                    isLooping = true
                    prepare(); start()
                }
                Store.log(this, "Playing $uri")
                break
            } catch (e: Exception) {
                player?.release(); player = null
                Store.log(this, "Can't play $uri (${e.javaClass.simpleName})")
            }
        }
        if (player == null) {
            Store.log(this, "No tone playable, using beeps")
            try {
                beeper = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
                val beep = object : Runnable {
                    override fun run() { beeper?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900); handler.postDelayed(this, 1500) }
                }
                handler.post(beep)
            } catch (e: Exception) { Store.log(this, "Beeps failed too (${e.javaClass.simpleName})") }
        }
        getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
    }

    private fun firstSystemSound(type: Int): Uri? {
        val rm = RingtoneManager(this).apply { setType(type) }
        val c = rm.cursor
        return if (c != null && c.moveToFirst()) rm.getRingtoneUri(0) else null
    }

    private fun <T> safe(f: () -> T?): T? = try { f() } catch (_: Exception) { null }

    private fun quiet() {
        handler.removeCallbacksAndMessages(null)
        player?.runCatching { stop(); release() }; player = null
        beeper?.runCatching { stopTone(); release() }; beeper = null
        getSystemService(Vibrator::class.java).cancel()
        if (restoreVolume >= 0) {
            try { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, restoreVolume, 0) } catch (_: Exception) { }
            restoreVolume = -1
        }
    }

    /** Sound off, notification stays as a normal (removable) reminder. */
    private fun endRinging() {
        quiet()
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onDestroy() { quiet(); super.onDestroy() }
}
