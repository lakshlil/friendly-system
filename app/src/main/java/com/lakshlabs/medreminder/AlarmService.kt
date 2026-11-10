package com.lakshlabs.medreminder

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.*

/** Plays the alarm tone and vibrates until the dose is handled, and posts the full-screen alarm notification. */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val NOTIF_ID = 7001
        const val ACTION_SILENCE = "SILENCE"
        private const val CHANNEL = "alarm"
        private const val RING_MS = 120_000L

        fun stop(c: Context) {
            c.stopService(Intent(c, AlarmService::class.java))
            c.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        }

        /** Stops the sound but leaves the notification in the shade. */
        fun silence(c: Context) {
            try { c.startService(Intent(c, AlarmService::class.java).setAction(ACTION_SILENCE)) } catch (_: Exception) { }
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
        val n = build(minute, pending)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, n)
        if (pending.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        if (player == null) {
            startSound(); handler.postDelayed({ endRinging() }, RING_MS)
            // The full-screen intent only takes over a locked or idle screen. When the phone is in use,
            // open the alarm screen directly (Android allows this while the app is in the foreground).
            try { startActivity(screenIntent(minute)) } catch (_: Exception) { }
        }
        return START_NOT_STICKY
    }

    private fun screenIntent(minute: Int) = Intent(this, AlarmActivity::class.java)
        .putExtra("minute", minute).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun build(minute: Int, pending: List<Dose>): Notification {
        val screen = PendingIntent.getActivity(this, 1, screenIntent(minute),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(name: String, req: Int) = PendingIntent.getBroadcast(this, 10 + req,
            Intent(this, ActionReceiver::class.java).setAction(name).putExtra("minute", minute),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle("Time for your medicine")
            .setContentText(pending.joinToString(", ") { it.med.name })
            .setSubText(fmtTime(minute))
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .addAction(Notification.Action.Builder(null, "Taken", action("TAKEN", 1)).build())
            .addAction(Notification.Action.Builder(null, "Snooze ${Store.snoozeMinutes(this)} min", action("SNOOZE", 2)).build())
            .build()
    }

    private fun startSound() {
        val uri = Store.alarmTone(this)?.let { Uri.parse(it) }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@AlarmService, uri)
                isLooping = true
                prepare(); start()
            }
        } catch (_: Exception) { player?.release(); player = null }
        val v = getSystemService(Vibrator::class.java)
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0))
    }

    private fun quiet() {
        handler.removeCallbacksAndMessages(null)
        player?.runCatching { stop(); release() }; player = null
        getSystemService(Vibrator::class.java).cancel()
    }

    /** Sound off, notification stays as a normal (removable) reminder. */
    private fun endRinging() {
        quiet()
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onDestroy() { quiet(); super.onDestroy() }
}
