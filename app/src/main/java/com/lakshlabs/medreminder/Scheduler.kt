package com.lakshlabs.medreminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.*

object Scheduler {
    const val DEV_PREFIX = "dev-"

    private fun pending(c: Context, medId: String, minute: Int, snooze: Boolean = false): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java).putExtra("medId", medId).putExtra("minute", minute)
        val code = (medId + minute).hashCode() + if (snooze) 1 else 0
        return PendingIntent.getBroadcast(c, code, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Next moment this dose is due, honouring chosen weekdays and the end date. */
    private fun nextTrigger(m: Medicine, minute: Int): Long? {
        val now = System.currentTimeMillis()
        for (offset in 0..8) {
            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, minute / 60); set(Calendar.MINUTE, minute % 60)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis > now && Store.appliesOn(m, cal)) return cal.timeInMillis
        }
        return null
    }

    /**
     * Alarm-clock alarms ring on time even in Doze and let the receiver start the ringing service.
     * Without exact-alarm permission Android may delay the alarm, and the reminder falls back to a notification.
     */
    internal fun set(c: Context, at: Long, pi: PendingIntent) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) {
            val show = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** Queues the next alarm for every dose slot. Safe to call any time. */
    fun rescheduleAll(c: Context) {
        Store.medicines(c).filterNot { it.id.startsWith(DEV_PREFIX) }.forEach { m ->
            m.times.forEach { t -> nextTrigger(m, t)?.let { set(c, it, pending(c, m.id, t)) } }
        }
    }

    fun cancel(c: Context, m: Medicine) {
        val am = c.getSystemService(AlarmManager::class.java)
        m.times.forEach { t -> am.cancel(pending(c, m.id, t)); am.cancel(pending(c, m.id, t, true)) }
    }

    /**
     * Unanswered dose: ring again in [Store.nagMinutes], until [Store.nagHours] after it was due.
     * Returns false when the window is over.
     */
    fun nag(c: Context, medId: String, minute: Int): Boolean {
        val next = System.currentTimeMillis() + Store.nagMinutes(c) * 60_000L
        if (next > Store.dueAt(minute) + Store.nagHours(c) * 3_600_000L) return false
        set(c, next, pending(c, medId, minute, true))
        return true
    }

    /** Ring once, [seconds] from now, for a dose that is already due (used after a restart). */
    fun ringSoon(c: Context, medId: String, minute: Int, seconds: Int = 10) =
        set(c, System.currentTimeMillis() + seconds * 1000L, pending(c, medId, minute, true))

    fun snooze(c: Context, medId: String, minute: Int) =
        set(c, System.currentTimeMillis() + Store.snoozeMinutes(c) * 60_000L, pending(c, medId, minute, true))
}

/** Developer mode: rings a throwaway test medicine a few seconds from now. */
fun Scheduler.scheduleTest(c: Context, seconds: Int) {
    Store.medicines(c).filter { it.id.startsWith(DEV_PREFIX) }.forEach { Store.delete(c, it.id) }
    val at = Calendar.getInstance().apply { add(Calendar.SECOND, seconds) }
    val minute = at.get(Calendar.HOUR_OF_DAY) * 60 + at.get(Calendar.MINUTE)
    val m = Medicine(id = DEV_PREFIX + "test", name = "Test alarm", times = listOf(minute),
        startDay = Store.dayStr(), endDay = Store.dayStr())
    Store.upsert(c, m)
    val pi = PendingIntent.getBroadcast(c, (m.id + minute).hashCode(),
        Intent(c, AlarmReceiver::class.java).putExtra("medId", m.id).putExtra("minute", minute),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    set(c, at.timeInMillis, pi)
}
