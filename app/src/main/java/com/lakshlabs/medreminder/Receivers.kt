package com.lakshlabs.medreminder

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.util.Calendar

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val medId = i.getStringExtra("medId") ?: return
        val minute = i.getIntExtra("minute", -1)
        val med = Store.find(c, medId) ?: return Store.log(c, "Alarm fired for a deleted medicine, ignored")
        Scheduler.rescheduleAll(c) // queue the next one
        val today = Calendar.getInstance()
        if (!Store.appliesOn(med, today)) return
        if (Store.status(c, Store.dayStr(today), medId, minute) != null) return
        Store.log(c, "Alarm fired: ${med.name} ${fmtTime(minute)} (style ${if (Store.alarmStyle(c) == 0) "alarm" else "notification"})")
        if (Store.alarmStyle(c) == 0) {
            try { c.startForegroundService(Intent(c, AlarmService::class.java).putExtra("minute", minute)); return }
            catch (e: Exception) {
                // Some phones refuse to start the ringing service from the background: ring from a notification.
                Store.log(c, "Ringing service refused (${e.javaClass.simpleName}). Using backup alarm.")
                Notify.loudAlarm(c, minute, Store.dueDoses(c, minute))
                Store.dueDoses(c, minute).forEach { Scheduler.nag(c, it.med.id, it.minute) }
                return
            }
        }
        Notify.dose(c, med, minute)
        Scheduler.nag(c, medId, minute)
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val minute = i.getIntExtra("minute", -1)
        val medId = i.getStringExtra("medId")          // set by quiet notifications, absent for the alarm
        val day = Store.dayStr()
        val doses = if (medId != null) listOfNotNull(Store.find(c, medId)).map { Dose(it, minute) }
                    else Store.dueDoses(c, minute)
        when (i.action) {
            "TAKEN" -> doses.forEach { Store.setStatus(c, day, it.med.id, it.minute, 'T') }
            "SNOOZE" -> doses.forEach { Scheduler.snooze(c, it.med.id, it.minute) }
        }
        val nm = c.getSystemService(NotificationManager::class.java)
        doses.forEach { nm.cancel(Notify.id(it.med.id, it.minute)) }
        if (medId == null || Store.dueDoses(c, minute).isEmpty()) AlarmService.stop(c)
    }
}

/**
 * After a restart: queue alarms again, then deal with doses that came due while the phone was off.
 * LOCKED_BOOT_COMPLETED arrives before the first unlock (data is in device-protected storage), so this
 * works without entering the PIN. Doses still inside the reminding window ring straight away; the rest
 * are listed in a "missed" notification.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Scheduler.rescheduleAll(c)
        Updater.schedule(c)
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val boot = try { Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT) } catch (_: Exception) { -2 }
        if (boot != -2 && boot == Store.lastBootHandled(c)) return   // locked + unlocked boot both arrive; act once
        Store.setLastBootHandled(c, boot)
        catchUp(c, i.action ?: "")
    }

    companion object {
        fun catchUp(c: Context, why: String) {
            val missed = Store.missedToday(c)
            Store.log(c, "Phone started ($why). ${missed.size} unanswered dose(s) today.")
            if (missed.isEmpty()) return
            val windowMs = Store.nagHours(c) * 3_600_000L
            val now = System.currentTimeMillis()
            val ringNow = missed.filter { now - Store.dueAt(it.minute) <= windowMs }
            ringNow.firstOrNull()?.let { Scheduler.ringSoon(c, it.med.id, it.minute) }   // the alarm screen lists them all
            Notify.missedSummary(c, missed)
        }
    }
}
