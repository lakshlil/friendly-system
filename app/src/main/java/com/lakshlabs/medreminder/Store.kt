package com.lakshlabs.medreminder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

object Store {
    @Volatile private var migrated = false

    /**
     * Data lives in device-protected storage so alarms can be rescheduled and ring after a restart even before
     * the phone is unlocked for the first time. Older installs kept it in normal storage: move it once.
     */
    private fun prefs(c: Context): android.content.SharedPreferences {
        val dp = c.createDeviceProtectedStorageContext() ?: c
        if (!migrated) {
            val unlocked = c.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked ?: true
            if (unlocked) {
                if (!dp.getSharedPreferences("meds", Context.MODE_PRIVATE).contains("list"))
                    try { dp.moveSharedPreferencesFrom(c, "meds") } catch (_: Exception) { }
                migrated = true
            }
        }
        return dp.getSharedPreferences("meds", Context.MODE_PRIVATE)
    }
    private val fmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun dayStr(cal: Calendar = Calendar.getInstance()): String = fmt.format(cal.time)
    fun daysFromNow(n: Int): Calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, n) }

    // ---- medicines ----
    fun medicines(c: Context): List<Medicine> {
        val arr = JSONArray(prefs(c).getString("list", "[]"))
        return (0 until arr.length()).map { Medicine.fromJson(arr.getJSONObject(it)) }
    }

    private fun save(c: Context, list: List<Medicine>) {
        val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString("list", arr.toString()).apply()
    }

    fun find(c: Context, id: String) = medicines(c).find { it.id == id }

    fun upsert(c: Context, m: Medicine) {
        val old = find(c, m.id)
        if (old != null) Scheduler.cancel(c, old)
        save(c, medicines(c).filter { it.id != m.id } + m)
        Scheduler.rescheduleAll(c)
    }

    fun delete(c: Context, id: String) {
        find(c, id)?.let { Scheduler.cancel(c, it) }
        save(c, medicines(c).filter { it.id != id })
    }

    fun appliesOn(m: Medicine, cal: Calendar): Boolean {
        val d = dayStr(cal)
        return d >= m.startDay && (m.endDay == null || d <= m.endDay) &&
            (m.days.isEmpty() || cal.get(Calendar.DAY_OF_WEEK) in m.days)
    }

    fun dosesOn(c: Context, cal: Calendar): List<Dose> =
        medicines(c).filter { appliesOn(it, cal) }
            .flatMap { m -> m.times.map { Dose(m, it) } }.sortedBy { it.minute }

    // ---- dose log: "day|medId|minute" -> 'T' (taken) or 'S' (skipped) ----
    private fun log(c: Context) = JSONObject(prefs(c).getString("log", "{}"))

    fun status(c: Context, day: String, medId: String, minute: Int): Char? =
        log(c).optString("$day|$medId|$minute").firstOrNull()

    fun setStatus(c: Context, day: String, medId: String, minute: Int, st: Char?) {
        val l = log(c)
        val key = "$day|$medId|$minute"
        val prev = l.optString(key).firstOrNull()
        if (st == null) l.remove(key) else l.put(key, st.toString())
        // Forget entries older than 90 days.
        val cutoff = dayStr(daysFromNow(-90))
        l.keys().asSequence().toList().filter { it.substringBefore('|') < cutoff }.forEach { l.remove(it) }
        prefs(c).edit().putString("log", l.toString()).apply()

        val m = find(c, medId) ?: return
        if (m.stock >= 0 && prev != st) {
            var s = m.stock
            if (st == 'T') s = (s - m.amount).coerceAtLeast(0.0)
            if (prev == 'T') s += m.amount
            val updated = m.copy(stock = s)
            save(c, medicines(c).map { if (it.id == medId) updated else it })
            if (st == 'T') Notify.refillIfLow(c, m, updated)
        }
    }

    /** Today's doses at this minute that haven't been taken or skipped yet. */
    fun pendingAt(c: Context, minute: Int): List<Dose> {
        val day = dayStr()
        return dosesOn(c, Calendar.getInstance()).filter { it.minute == minute && status(c, day, it.med.id, it.minute) == null }
    }

    /** Time a dose is due today, in millis. */
    fun dueAt(minute: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minute / 60); set(Calendar.MINUTE, minute % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * Everything the alarm should show now: doses at [minute], plus earlier doses today that are still
     * unanswered and inside the "keep reminding" window.
     */
    fun dueDoses(c: Context, minute: Int): List<Dose> {
        val day = dayStr()
        val now = System.currentTimeMillis()
        val windowMs = nagHours(c) * 3_600_000L
        val overdue = dosesOn(c, Calendar.getInstance()).filter {
            status(c, day, it.med.id, it.minute) == null && dueAt(it.minute) <= now && now - dueAt(it.minute) <= windowMs
        }
        return (pendingAt(c, minute) + overdue).distinctBy { "${it.med.id}|${it.minute}" }.sortedBy { it.minute }
    }

    /** Doses earlier today that were never answered. */
    fun missedToday(c: Context): List<Dose> {
        val day = dayStr(); val now = System.currentTimeMillis()
        return dosesOn(c, Calendar.getInstance()).filter { status(c, day, it.med.id, it.minute) == null && dueAt(it.minute) < now }
    }

    // ---- history ----
    /** (taken, scheduled) for one day, using today's schedule. */
    fun dayStats(c: Context, cal: Calendar): Pair<Int, Int> {
        val day = dayStr(cal)
        val doses = dosesOn(c, cal)
        val real = doses.filterNot { it.med.id.startsWith(Scheduler.DEV_PREFIX) }
        return real.count { status(c, day, it.med.id, it.minute) == 'T' } to real.size
    }

    fun streak(c: Context): Int {
        var s = 0
        for (i in 0..90) {
            val (t, n) = dayStats(c, daysFromNow(-i))
            if (n == 0) continue
            if (t == n) s++ else if (i == 0) continue else break
        }
        return s
    }

    // ---- alarm log (shown in developer mode) ----
    fun log(c: Context, msg: String) {
        val t = java.text.SimpleDateFormat("d MMM HH:mm:ss", Locale.US).format(Date())
        val list = (listOf("$t  $msg") + alarmLog(c)).take(40)
        prefs(c).edit().putString("alarmlog", JSONArray(list).toString()).apply()
    }
    fun alarmLog(c: Context): List<String> {
        val a = JSONArray(prefs(c).getString("alarmlog", "[]"))
        return (0 until a.length()).map { a.getString(it) }
    }
    fun clearLog(c: Context) = prefs(c).edit().remove("alarmlog").apply()

    // ---- settings ----
    /** 0 = follow the phone, 1 = light, 2 = dark. */
    fun themeMode(c: Context) = prefs(c).getInt("theme", 0)
    fun setThemeMode(c: Context, v: Int) = prefs(c).edit().putInt("theme", v).apply()
    fun accent(c: Context) = prefs(c).getInt("accent", 0)
    fun setAccent(c: Context, v: Int) = prefs(c).edit().putInt("accent", v).apply()
    /** How long one alarm rings before it goes quiet and waits to ring again. */
    fun ringSeconds(c: Context) = prefs(c).getInt("ring", 60)
    fun setRingSeconds(c: Context, v: Int) = prefs(c).edit().putInt("ring", v).apply()
    /** Unanswered doses ring again every this many minutes... */
    fun nagMinutes(c: Context) = prefs(c).getInt("nag", 5)
    fun setNagMinutes(c: Context, v: Int) = prefs(c).edit().putInt("nag", v).apply()
    /** ...for this many hours after they were due. */
    fun nagHours(c: Context) = prefs(c).getInt("nagHours", 2)
    fun setNagHours(c: Context, v: Int) = prefs(c).edit().putInt("nagHours", v).apply()
    fun lastBootHandled(c: Context) = prefs(c).getInt("bootHandled", -1)
    fun setLastBootHandled(c: Context, v: Int) = prefs(c).edit().putInt("bootHandled", v).apply()
    fun autoUpdate(c: Context) = prefs(c).getBoolean("autoUpdate", true)
    fun setAutoUpdate(c: Context, v: Boolean) = prefs(c).edit().putBoolean("autoUpdate", v).apply()
    fun updateStatus(c: Context) = prefs(c).getString("updateStatus", "") ?: ""
    fun setUpdateStatus(c: Context, v: String) = prefs(c).edit().putString("updateStatus", v).apply()
    fun lastUpdateCheck(c: Context) = prefs(c).getLong("updateChecked", 0)
    fun setLastUpdateCheck(c: Context, v: Long) = prefs(c).edit().putLong("updateChecked", v).apply()
    fun snoozeMinutes(c: Context) = prefs(c).getInt("snooze", 10)
    /** 0 = full-screen alarm with sound, 1 = quiet notification. */
    fun alarmStyle(c: Context) = prefs(c).getInt("style", 0)
    fun setAlarmStyle(c: Context, v: Int) = prefs(c).edit().putInt("style", v).apply()
    /** Chosen alarm tone, or null for the phone's default alarm. */
    fun alarmTone(c: Context): String? = prefs(c).getString("tone", null)
    fun setAlarmTone(c: Context, uri: String?) = prefs(c).edit().putString("tone", uri).apply()
    /** Hidden developer mode (tap the version in Settings 7 times). */
    fun devMode(c: Context) = prefs(c).getBoolean("dev", false)
    fun setDevMode(c: Context, v: Boolean) = prefs(c).edit().putBoolean("dev", v).apply()
    fun setSnoozeMinutes(c: Context, m: Int) = prefs(c).edit().putInt("snooze", m).apply()
}
