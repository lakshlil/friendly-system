package com.lakshlabs.medreminder

import android.app.*
import android.content.Context
import android.content.Intent

object Notify {
    private const val DOSES = "doses"
    private const val REFILL = "refill"

    private fun nm(c: Context): NotificationManager {
        val m = c.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(NotificationChannel(DOSES, "Dose reminders",
            NotificationManager.IMPORTANCE_HIGH).apply { description = "Time to take your medicine" })
        m.createNotificationChannel(NotificationChannel(REFILL, "Refill alerts",
            NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Supply running low" })
        return m
    }

    fun id(medId: String, minute: Int) = (medId + minute).hashCode()

    private fun open(c: Context) = PendingIntent.getActivity(c, 0,
        Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    fun dose(c: Context, m: Medicine, minute: Int) {
        fun action(name: String, req: Int) = PendingIntent.getBroadcast(c, id(m.id, minute) + req,
            Intent(c, ActionReceiver::class.java).setAction(name)
                .putExtra("medId", m.id).putExtra("minute", minute),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val detail = listOf(m.doseText, m.strength, FOOD[m.food].takeIf { m.food > 0 })
            .filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
        val n = Notification.Builder(c, DOSES)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle("Time for ${m.name}")
            .setContentText(detail)
            .setSubText(fmtTime(minute))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open(c)).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Taken", action("TAKEN", 1)).build())
            .addAction(Notification.Action.Builder(null, "Snooze ${Store.snoozeMinutes(c)} min", action("SNOOZE", 2)).build())
            .build()
        try { nm(c).notify(id(m.id, minute), n) } catch (_: SecurityException) { }
    }

    /** Warns once when a dose just taken pushes supply under the refill threshold. */
    fun refillIfLow(c: Context, before: Medicine, after: Medicine) {
        val was = before.daysLeft ?: return
        val now = after.daysLeft ?: return
        if (now > after.alertDays || was <= after.alertDays && after.stock > 0) return
        val text = if (after.stock <= 0) "You've run out. Time to refill." else "About $now day(s) of supply left."
        val n = Notification.Builder(c, REFILL).setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle("Refill ${after.name}").setContentText(text)
            .setContentIntent(open(c)).setAutoCancel(true).build()
        try { nm(c).notify(("refill" + after.id).hashCode(), n) } catch (_: SecurityException) { }
    }
}
