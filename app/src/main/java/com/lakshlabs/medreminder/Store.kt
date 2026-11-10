package com.lakshlabs.medreminder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("meds", Context.MODE_PRIVATE)
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

    // ---- settings ----
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
