package com.lakshlabs.medreminder

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
                Store.log(c, "Ringing service refused (${e.javaClass.simpleName}). Using loud notification.")
                Notify.loudAlarm(c, minute, Store.pendingAt(c, minute)); return
            }
        }
        Notify.dose(c, med, minute)
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val minute = i.getIntExtra("minute", -1)
        val medId = i.getStringExtra("medId")          // set by quiet notifications, absent for the alarm
        val day = Store.dayStr()
        val doses = if (medId != null) listOfNotNull(Store.find(c, medId)).map { Dose(it, minute) }
                    else Store.pendingAt(c, minute)
        when (i.action) {
            "TAKEN" -> doses.forEach { Store.setStatus(c, day, it.med.id, minute, 'T') }
            "SNOOZE" -> doses.forEach { Scheduler.snooze(c, it.med.id, minute) }
        }
        val nm = c.getSystemService(NotificationManager::class.java)
        doses.forEach { nm.cancel(Notify.id(it.med.id, minute)) }
        if (medId == null || Store.pendingAt(c, minute).isEmpty()) AlarmService.stop(c)
    }
}

/** Alarms are wiped on reboot/update, so queue them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = Scheduler.rescheduleAll(c)
}
