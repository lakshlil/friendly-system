package com.lakshlabs.medreminder

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Intent
import android.media.RingtoneManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private val screens = listOf("Today", "Medicines", "History", "Settings")
    private var screen = 0
    private var drawerOpen = false

    private lateinit var title: TextView
    private lateinit var content: FrameLayout
    private lateinit var fab: TextView
    private lateinit var drawer: LinearLayout
    private lateinit var scrim: View
    private val drawerW get() = dp(290)
    private var downX = 0f
    private var tracking = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }

        // Top bar + content
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(12), dp(20), dp(8)) }
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_menu); setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { setDrawer(true) }
        }, lp(dp(48), dp(48)))
        title = text("Today", 24f, bold = true)
        bar.addView(title, lp(0, -2, 1f, start = 4))
        main.addView(bar)
        content = FrameLayout(this)
        main.addView(content, lp(-1, 0, 1f))
        root.addView(main, FrameLayout.LayoutParams(-1, -1))

        fab = text("+", 30f, Color.WHITE).apply {
            gravity = Gravity.CENTER; elevation = dp(6).toFloat()
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TEAL) }
            setOnClickListener { startActivity(Intent(this@MainActivity, AddActivity::class.java)) }
        }
        root.addView(fab, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(20), dp(24)) })

        scrim = View(this).apply { setBackgroundColor(0x66000000); visibility = View.GONE; alpha = 0f; setOnClickListener { setDrawer(false) } }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        drawer = buildDrawer()
        drawer.translationX = -drawerW.toFloat()
        root.addView(drawer, FrameLayout.LayoutParams(drawerW, -1))
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        Drug.preload(this)
    }

    override fun onResume() {
        super.onResume()
        Scheduler.rescheduleAll(this)
        render()
    }

    // ---------- drawer ----------
    private fun buildDrawer(): LinearLayout {
        val d = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); elevation = dp(16).toFloat() }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(44), dp(20), dp(24)) }
        head.addView(iconTile(R.drawable.ic_stat_pill, TEAL, Color.WHITE, 52))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        names.addView(text("Med Reminder", 18f, bold = true))
        names.addView(text("Never miss a dose", 13f, MUTED))
        head.addView(names)
        d.addView(head)
        val icons = listOf(R.drawable.ic_stat_pill, R.drawable.ic_stat_pill, R.drawable.ic_stat_pill, R.drawable.ic_stat_pill)
        screens.forEachIndexed { i, s ->
            val sel = i == screen
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
                background = if (sel) rounded(TEAL_SOFT, 14, this@MainActivity) else null
                setOnClickListener { go(i) }
            }
            row.addView(text(listOf("◷", "☷", "▦", "⚙")[i], 20f, if (sel) TEAL else MUTED), lp(dp(34), -2))
            row.addView(text(s, 16f, if (sel) TEAL else INK, bold = sel))
            d.addView(row, lp(top = 2, start = 12, end = 12))
        }
        d.addView(View(this), lp(-1, 0, 1f))
        d.addView(text("Drug data: NIH RxNorm", 12f, MUTED).apply { setPadding(dp(24), dp(12), dp(24), dp(24)) })
        return d
    }

    private fun setDrawer(open: Boolean) {
        drawerOpen = open
        if (open) { refreshDrawer(); scrim.visibility = View.VISIBLE }
        scrim.animate().alpha(if (open) 1f else 0f).withEndAction { if (!open) scrim.visibility = View.GONE }.setDuration(180).start()
        drawer.animate().translationX(if (open) 0f else -drawerW.toFloat()).setDuration(220).start()
    }

    private fun refreshDrawer() {
        val parent = drawer.parent as FrameLayout
        parent.removeView(drawer)
        drawer = buildDrawer().also { it.translationX = drawer.translationX }
        parent.addView(drawer, FrameLayout.LayoutParams(drawerW, -1))
    }

    private fun go(i: Int) { screen = i; setDrawer(false); render() }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; tracking = true }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                if (!drawerOpen && downX < dp(24) && e.x - downX > dp(60)) { tracking = false; setDrawer(true) }
                if (drawerOpen && downX - e.x > dp(60)) { tracking = false; setDrawer(false) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return super.dispatchTouchEvent(e)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (drawerOpen) setDrawer(false) else super.onBackPressed() }

    // ---------- screens ----------
    private fun render() {
        title.text = screens[screen]
        fab.visibility = if (screen <= 1) View.VISIBLE else View.GONE
        content.removeAllViews()
        content.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(when (screen) { 0 -> todayView(); 1 -> medicinesView(); 2 -> historyView(); else -> settingsView() })
        })
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(100))
    }

    private fun banner(col: LinearLayout, msg: String, intent: Intent) {
        col.addView(text(msg, 14f, AMBER).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(AMBER_BG, 14, this@MainActivity)
            setOnClickListener { openSettings(intent) }
        }, lp(bottom = 12))
    }

    private fun exactAlarmBanner(col: LinearLayout) {
        val pkg = Uri.parse("package:$packageName")
        if (!getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
            banner(col, "Allow exact alarms so reminders arrive on time. Tap here.", Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
        if (Build.VERSION.SDK_INT >= 34 && Store.alarmStyle(this) == 0 &&
            !getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent())
            banner(col, "Allow full-screen alarms so the alarm shows over the lock screen. Tap here.", Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
    }

    /** Settings pages differ by Android version and maker; never crash if one is missing. */
    private fun openSettings(i: Intent) {
        try { startActivity(i) } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { toast("Couldn't open settings") }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun emptyState(msg: String) = text(msg, 16f, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(80), 0, 0) }

    // --- Today ---
    private fun todayView(): View {
        val col = column()
        exactAlarmBanner(col)
        val cal = Calendar.getInstance()
        val day = Store.dayStr(cal)
        val doses = Store.dosesOn(this, cal)
        if (doses.isEmpty()) {
            col.addView(emptyState("Nothing scheduled today.\nTap + to add a medicine."))
            return col
        }
        val st = doses.map { Store.status(this, day, it.med.id, it.minute) }
        val taken = st.count { it == 'T' }
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        val sum = card(18)
        sum.addView(text(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(cal.time), 13f, MUTED))
        sum.addView(text("$taken of ${doses.size} taken", 26f, bold = true), lp(top = 2))
        val next = doses.indices.firstOrNull { st[it] == null && doses[it].minute >= now } ?: doses.indices.firstOrNull { st[it] == null }
        sum.addView(text(if (next == null) "All done for today" else "Next: ${doses[next].med.name} at ${fmtTime(doses[next].minute)}", 14f, TEAL), lp(top = 2, bottom = 12))
        sum.addView(progressBar(taken / doses.size.toFloat()), lp(-1, dp(8)))
        col.addView(sum, lp(bottom = 8))
        Store.streak(this).takeIf { it >= 2 }?.let {
            col.addView(text("$it-day streak", 13f, MUTED), lp(top = 4, bottom = 4, start = 2))
        }

        val parts = listOf("Morning" to 0..719, "Afternoon" to 720..1019, "Evening" to 1020..1259, "Night" to 1260..1439)
        parts.forEach { (label, range) ->
            val idx = doses.indices.filter { doses[it].minute in range }
            if (idx.isEmpty()) return@forEach
            col.addView(sectionLabel(label), lp(top = 16))
            idx.forEach { i -> col.addView(doseCard(doses[i], st[i], now, day), lp(bottom = 10)) }
        }
        col.addView(text("Tap a dose to mark it. Long-press for more.", 12f, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        return col
    }

    private fun doseCard(d: Dose, status: Char?, now: Int, day: String): View {
        val missed = status == null && d.minute < now - 30
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.WHITE, 18, this@MainActivity, LINE)
        }
        val tile = when (status) {
            'T' -> iconTile(R.drawable.ic_stat_pill, TEAL, Color.WHITE)
            'S' -> iconTile(R.drawable.ic_stat_pill, LINE, MUTED)
            else -> iconTile(R.drawable.ic_stat_pill, if (missed) 0xFFFDE8E6.toInt() else TEAL_SOFT, if (missed) RED else TEAL)
        }
        card.addView(tile)
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(text(d.med.name + if (d.med.strength.isNotBlank()) "  ${d.med.strength}" else "", 17f, bold = true))
        val sub = listOf(fmtTime(d.minute), d.med.doseText, FOOD[d.med.food].takeIf { d.med.food > 0 }).filterNotNull().joinToString(" · ")
        info.addView(text(sub, 14f, if (missed) RED else MUTED))
        card.addView(info, lp(0, -2, 1f, start = 14))
        card.addView(text(when (status) { 'T' -> "Taken"; 'S' -> "Skipped"; else -> if (missed) "Missed" else "" }, 14f,
            when (status) { 'T' -> TEAL; 'S' -> MUTED; else -> RED }, bold = true))
        card.setOnClickListener {
            if (status != null) { Store.setStatus(this, day, d.med.id, d.minute, null); render() }
            else AlertDialog.Builder(this).setTitle("${d.med.name} · ${fmtTime(d.minute)}")
                .setItems(arrayOf("Mark as taken", "Skip this dose")) { _, w ->
                    Store.setStatus(this, day, d.med.id, d.minute, if (w == 0) 'T' else 'S'); render()
                }.show()
        }
        card.setOnLongClickListener {
            AlertDialog.Builder(this).setTitle(d.med.name).setItems(arrayOf("Edit medicine")) { _, _ ->
                startActivity(Intent(this, AddActivity::class.java).putExtra("id", d.med.id))
            }.show(); true
        }
        return card
    }

    // --- Medicines ---
    private fun medicinesView(): View {
        val col = column()
        val meds = Store.medicines(this).filterNot { it.id.startsWith(Scheduler.DEV_PREFIX) }.sortedBy { it.name.lowercase() }
        if (meds.isEmpty()) { col.addView(emptyState("No medicines yet.\nTap + to add your first one.")); return col }
        meds.forEach { m ->
            val c = card()
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(iconTile(R.drawable.ic_stat_pill, TEAL_SOFT, TEAL))
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text(m.name, 17f, bold = true))
            info.addView(text(listOf(m.strength, m.category, m.doseText + " per dose").filter { it.isNotBlank() }.joinToString(" · "), 13f, MUTED))
            top.addView(info, lp(0, -2, 1f, start = 14))
            c.addView(top)
            c.addView(text(m.times.joinToString("  ·  ") { fmtTime(it) }, 15f, INK, bold = true), lp(top = 12))
            val meta = listOfNotNull(m.daysText, FOOD[m.food].takeIf { m.food > 0 },
                m.endDay?.let { "Until $it" }).joinToString(" · ")
            c.addView(text(meta, 13f, MUTED), lp(top = 2))
            m.daysLeft?.let { left ->
                val low = left <= m.alertDays
                c.addView(text(if (m.stock <= 0) "Out of stock" else "About $left day(s) left · ${fmtAmt(m.stock, m.category)} ${Cat.unit(m.category, m.stock)}", 13f,
                    if (low) RED else TEAL, bold = true), lp(top = 8))
            }
            if (m.notes.isNotBlank()) c.addView(text(m.notes, 13f, MUTED), lp(top = 6))
            c.setOnClickListener { startActivity(Intent(this, AddActivity::class.java).putExtra("id", m.id)) }
            col.addView(c, lp(bottom = 10))
        }
        return col
    }

    // --- History ---
    private fun historyView(): View {
        val col = column()
        val days = (0..13).map { Store.daysFromNow(-it) }
        val stats = days.map { Store.dayStats(this, it) }
        val week = stats.take(7)
        val tot = week.sumOf { it.second }
        val pct = if (tot == 0) 0 else week.sumOf { it.first } * 100 / tot

        val sum = card(18)
        sum.addView(text("Last 7 days", 13f, MUTED))
        sum.addView(text(if (tot == 0) "No doses yet" else "$pct% on time", 28f, bold = true), lp(top = 2))
        sum.addView(text("Current streak: ${Store.streak(this)} day(s)", 14f, TEAL), lp(top = 2))
        col.addView(sum, lp(bottom = 8))

        col.addView(sectionLabel("Daily"), lp(top = 12))
        days.forEachIndexed { i, cal ->
            val (t, n) = stats[i]
            val row = card(14)
            val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(text(when (i) { 0 -> "Today"; 1 -> "Yesterday"; else -> SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(cal.time) }, 15f, bold = true), lp(0, -2, 1f))
            head.addView(text(if (n == 0) "No doses" else "$t / $n", 14f, if (n > 0 && t == n) TEAL else MUTED, bold = true))
            row.addView(head)
            if (n > 0) row.addView(progressBar(t / n.toFloat(), if (t == n) TEAL else 0xFFE0A93B.toInt()), lp(-1, dp(6), top = 10))
            col.addView(row, lp(bottom = 8))
        }
        return col
    }

    private fun toneTitle(): String {
        val uri = Store.alarmTone(this)?.let { Uri.parse(it) } ?: return "Default alarm tone"
        return RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "Default alarm tone"
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 42 && res == RESULT_OK) {
            val uri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            Store.setAlarmTone(this, uri?.toString())
            render()
        }
    }

    // --- Settings ---
    private fun settingsView(): View {
        val col = column()
        val snoozes = listOf(5, 10, 15, 30)
        col.addView(sectionLabel("Reminder style"))
        col.addView(Segmented(this, listOf("Alarm", "Notification"), Store.alarmStyle(this)) { Store.setAlarmStyle(this, it) })
        col.addView(text("Alarm rings with your alarm tone and opens a full-screen screen. Notification is silent-style.", 12f, MUTED), lp(top = 6))

        col.addView(sectionLabel("Alarm tone"), lp(top = 24))
        col.addView(pill(toneTitle(), TEAL_SOFT, TEAL) {
            startActivityForResult(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Store.alarmTone(this)?.let { Uri.parse(it) }), 42)
        }, lp())

        col.addView(sectionLabel("Snooze length"), lp(top = 24))
        col.addView(Segmented(this, snoozes.map { "$it min" }, snoozes.indexOf(Store.snoozeMinutes(this)).coerceAtLeast(1)) {
            Store.setSnoozeMinutes(this, snoozes[it])
        })
        col.addView(text("Used by the Snooze button on reminders.", 12f, MUTED), lp(top = 6))

        col.addView(sectionLabel("Permissions"), lp(top = 24))
        col.addView(pill("Notification settings", TEAL_SOFT, TEAL) {
            openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }, lp())
        if (Build.VERSION.SDK_INT >= 34) col.addView(pill("Full-screen alarm permission", TEAL_SOFT, TEAL) {
            openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        }, lp(top = 8))
        if (!getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) col.addView(pill("Exact alarm permission", TEAL_SOFT, TEAL) {
            openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }, lp(top = 8))

        col.addView(sectionLabel("About"), lp(top = 24))
        val about = card()
        about.addView(text("Med Reminder 1.2.3", 16f, bold = true))
        about.addView(text("Drug names and strengths come from the U.S. National Library of Medicine's RxNorm service. " +
            "Anything missing can be entered by hand. This app is a reminder aid and gives no medical advice. " +
            "Follow your doctor's instructions.", 13f, MUTED), lp(top = 6))
        col.addView(about)

        col.addView(sectionLabel("Developer mode"), lp(top = 24))
        col.addView(Segmented(this, listOf("Off", "On"), if (Store.devMode(this)) 1 else 0) {
            Store.setDevMode(this, it == 1); render()
        })
        if (Store.devMode(this)) {
            val dev = card()
            val delays = listOf(10, 30, 60)
            val seg = Segmented(this, delays.map { "$it s" }, 1)
            dev.addView(text("Ring a test alarm in", 14f, MUTED), lp(bottom = 8))
            dev.addView(seg)
            dev.addView(pill("Schedule test alarm", TEAL, Color.WHITE) {
                val secs = delays[seg.index]
                Scheduler.scheduleTest(this, secs)
                toast(if (getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) "Alarm in $secs s. Lock the phone to test the lock screen."
                      else "Scheduled, but exact alarms are off so it may be late.")
            }, lp(top = 12))
            col.addView(dev, lp(top = 10))
        }
        return col
    }
}
