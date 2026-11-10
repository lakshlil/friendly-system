package com.lakshlabs.medreminder

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

/** "1.3.0 (build 7)", read from the installed package so it always matches the APK. */
fun Context.versionLabel(): String = try {
    val p = packageManager.getPackageInfo(packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
    "${p.versionName} (build $code)"
} catch (_: Exception) { "" }

class MainActivity : Activity() {
    private val screens = listOf("Today", "Medicines", "History", "Settings")
    private val screenIcons = listOf(R.drawable.ic_today, R.drawable.ic_stat_pill, R.drawable.ic_chart, R.drawable.ic_settings)
    private var screen = 0
    private var drawerOpen = false
    private var popKey: String? = null

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var content: FrameLayout
    private lateinit var fab: View
    private lateinit var drawer: LinearLayout
    private lateinit var scrim: View
    private val drawerW get() = dp(300)
    private var downX = 0f
    private var tracking = false

    // Time-of-day colours for the Today sections.
    private val parts = listOf(
        Triple("Morning", 0..719, 0xFFF59E0B.toInt()), Triple("Afternoon", 720..1019, 0xFF0EA5E9.toInt()),
        Triple("Evening", 1020..1259, 0xFF8B5CF6.toInt()), Triple("Night", 1260..1439, 0xFF4F46E5.toInt()))

    override fun onCreate(b: Bundle?) {
        Theme.init(this)
        super.onCreate(b)
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }

        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(14), dp(20), dp(10)) }
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_menu); imageTintList = ColorStateList.valueOf(INK)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = circle(SURFACE, LINE, dp(1))
            setOnClickListener { setDrawer(true) }
            pressable(24)
        }, lp(dp(48), dp(48)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = text("Today", 26f, weight = 700)
        subtitle = text("", 13f, MUTED, weight = 500)
        titles.addView(title); titles.addView(subtitle, lp(top = 1))
        bar.addView(titles, lp(0, -2, 1f, start = 14))
        main.addView(bar)
        content = FrameLayout(this)
        main.addView(content, lp(-1, 0, 1f))
        root.addView(main, FrameLayout.LayoutParams(-1, -1))

        fab = FrameLayout(this).apply {
            background = circle(TEAL); elevation = dpf(8f)
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_add); imageTintList = ColorStateList.valueOf(ON_TEAL) },
                FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            setOnClickListener { startActivity(Intent(this@MainActivity, AddActivity::class.java)) }
            pressable(30, withAlpha(ON_TEAL, 0.25f))
        }
        root.addView(fab, FrameLayout.LayoutParams(dp(62), dp(62), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(22), dp(28)) })

        scrim = View(this).apply { setBackgroundColor(0x88000000.toInt()); visibility = View.GONE; alpha = 0f; setOnClickListener { setDrawer(false) } }
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
        Theme.init(this)
        Scheduler.rescheduleAll(this)
        render(animate = true)
    }

    // ---------- drawer ----------
    private fun buildDrawer(): LinearLayout {
        val d = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; elevation = dpf(16f)
            background = rounded(SURFACE, 0, this@MainActivity)
            isClickable = true   // swallow taps so they don't reach the screen behind
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22), dp(48), dp(22), dp(28)) }
        head.addView(iconTile(R.drawable.ic_stat_pill, TEAL, ON_TEAL, 54))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        names.addView(text("Med Reminder", 20f, weight = 700))
        names.addView(text("Never miss a dose", 13f, MUTED, weight = 500), lp(top = 2))
        head.addView(names)
        d.addView(head)
        val rows = ArrayList<View>()
        screens.forEachIndexed { i, s ->
            val sel = i == screen
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15))
                background = if (sel) rounded(TEAL_SOFT, 16, this@MainActivity) else null
                setOnClickListener { go(i) }
                pressable(16)
            }
            row.addView(icon(screenIcons[i], if (sel) TEAL else MUTED, 22))
            row.addView(text(s, 16f, if (sel) TEAL else INK, weight = if (sel) 700 else 500), lp(start = 16))
            d.addView(row, lp(top = 4, start = 14, end = 14)); rows.add(row)
        }
        d.addView(View(this), lp(-1, 0, 1f))
        d.addView(text("Version ${versionLabel()}\nDrug data: NIH RxNorm", 12f, MUTED, weight = 500).apply { setPadding(dp(24), dp(12), dp(24), dp(28)) })
        d.tag = rows
        return d
    }

    private fun setDrawer(open: Boolean) {
        drawerOpen = open
        if (open) { refreshDrawer(); scrim.visibility = View.VISIBLE }
        scrim.animate().alpha(if (open) 1f else 0f).setDuration(220).withEndAction { if (!drawerOpen) scrim.visibility = View.GONE }.start()
        drawer.animate().translationX(if (open) 0f else -drawerW.toFloat()).setDuration(320).setInterpolator(EMPHASIZED).start()
        @Suppress("UNCHECKED_CAST")
        if (open) (drawer.tag as? List<View>)?.let { rows -> animateIn(rows, 35) }
    }

    private fun refreshDrawer() {
        val parent = drawer.parent as FrameLayout
        val x = drawer.translationX
        parent.removeView(drawer)
        drawer = buildDrawer().also { it.translationX = x }
        parent.addView(drawer, FrameLayout.LayoutParams(drawerW, -1))
    }

    private fun go(i: Int) { screen = i; setDrawer(false); render(animate = true) }

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
    override fun onBackPressed() { if (drawerOpen) setDrawer(false) else if (screen != 0) go(0) else super.onBackPressed() }

    // ---------- screens ----------
    private fun render(animate: Boolean = false) {
        title.text = screens[screen]
        subtitle.text = when (screen) {
            0 -> SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
            1 -> "${realMeds().size} medicine${if (realMeds().size == 1) "" else "s"}"
            2 -> "Your last two weeks"
            else -> "Reminders, sound and more"
        }
        val showFab = screen <= 1
        if (showFab && fab.visibility != View.VISIBLE) { fab.visibility = View.VISIBLE; pop(fab) }
        if (!showFab) fab.visibility = View.GONE

        val scroll = content.getChildAt(0) as? ScrollView
        val keepY = if (!animate) scroll?.scrollY ?: 0 else 0
        content.removeAllViews()
        val col = when (screen) { 0 -> todayView(); 1 -> medicinesView(); 2 -> historyView(); else -> settingsView() }
        content.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            addView(col)
            if (keepY > 0) post { scrollTo(0, keepY) }
        })
        if (animate && col is ViewGroup) animateIn((0 until col.childCount).map { col.getChildAt(it) })
    }

    private fun realMeds() = Store.medicines(this).filterNot { it.id.startsWith(Scheduler.DEV_PREFIX) }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(6), dp(18), dp(110))
    }

    private fun banner(col: LinearLayout, msg: String, intent: Intent) {
        col.addView(text(msg, 14f, AMBER, weight = 500).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(AMBER_BG, 16, this@MainActivity)
            setOnClickListener { openSettings(intent) }
            pressable(16)
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

    private fun emptyState(title: String, msg: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(70), dp(24), 0)
        addView(iconTile(R.drawable.ic_stat_pill, TEAL_SOFT, TEAL, 84).also { (it.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL })
        addView(text(title, 20f, weight = 700).apply { gravity = Gravity.CENTER }, lp(top = 20))
        addView(text(msg, 15f, MUTED).apply { gravity = Gravity.CENTER }, lp(top = 6))
    }

    /** Solid colour card used at the top of Today and History. */
    private fun heroCard(color: Int) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20))
        background = rounded(color, 26, this@MainActivity)
    }

    private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Hello"
    }

    // --- Today ---
    private fun todayView(): View {
        val col = column()
        exactAlarmBanner(col)
        val cal = Calendar.getInstance()
        val day = Store.dayStr(cal)
        val doses = Store.dosesOn(this, cal)
        if (doses.isEmpty()) {
            col.addView(emptyState("Nothing scheduled today", "Tap + to add a medicine and its reminder times."))
            return col
        }
        val st = doses.map { Store.status(this, day, it.med.id, it.minute) }
        val taken = st.count { it == 'T' }
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val frac = taken / doses.size.toFloat()

        // Hero: progress ring + summary
        val heroColor = if (Theme.dark) 0xFF115E59.toInt() else 0xFF0F766E.toInt()
        val hero = heroCard(heroColor)
        val ringBox = FrameLayout(this)
        val ring = Ring(this, Color.WHITE, withAlpha(Color.WHITE, 0.22f))
        ringBox.addView(ring, FrameLayout.LayoutParams(-1, -1))
        ringBox.addView(text("${(frac * 100).toInt()}%", 20f, Color.WHITE, weight = 700).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        hero.addView(ringBox, lp(dp(88), dp(88)))
        ring.post { ring.animateTo(frac) }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(text(greeting(), 13f, withAlpha(Color.WHITE, 0.75f), weight = 500))
        info.addView(text("$taken of ${doses.size} taken", 24f, Color.WHITE, weight = 700), lp(top = 2))
        val next = doses.indices.firstOrNull { st[it] == null && doses[it].minute >= now } ?: doses.indices.firstOrNull { st[it] == null }
        info.addView(text(if (next == null) "All done for today. Nice work!" else "Next: ${doses[next].med.name} at ${fmtTime(doses[next].minute)}",
            14f, withAlpha(Color.WHITE, 0.9f), weight = 500), lp(top = 4))
        Store.streak(this).takeIf { it >= 1 }?.let {
            info.addView(chip("$it-day streak", withAlpha(Color.WHITE, 0.18f), Color.WHITE), lp(-2, -2, top = 10))
        }
        hero.addView(info, lp(0, -2, 1f, start = 18))
        col.addView(hero, lp(bottom = 12))

        col.addView(weekStrip(), lp(bottom = 4))

        parts.forEach { (label, range, color) ->
            val idx = doses.indices.filter { doses[it].minute in range }
            if (idx.isEmpty()) return@forEach
            col.addView(sectionLabel(label, color), lp(top = 18))
            idx.forEach { i -> col.addView(doseCard(doses[i], st[i], now, day), lp(bottom = 10)) }
        }
        col.addView(text("Tap the circle to mark a dose taken. Tap a card for more.", 12f, MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
        return col
    }

    /** Last 7 days as coloured dots: full, partial, missed. */
    private fun weekStrip(): View {
        val c = card(14)
        val row = LinearLayout(this)
        for (i in 6 downTo 0) {
            val cal = Store.daysFromNow(-i)
            val (t, n) = Store.dayStats(this, cal)
            val fill = when { n == 0 -> TRACK; t == n -> TEAL; t > 0 -> 0xFFF59E0B.toInt(); i == 0 -> TRACK; else -> RED_SOFT }
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            cell.addView(text(SimpleDateFormat("EEE", Locale.getDefault()).format(cal.time).take(1), 12f,
                if (i == 0) TEAL else MUTED, weight = if (i == 0) 700 else 500))
            val dot = FrameLayout(this).apply {
                background = circle(fill, if (i == 0) TEAL else 0, dp(2))
                if (n > 0 && t == n) addView(icon(R.drawable.ic_check, ON_TEAL, 16), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
            }
            cell.addView(dot, lp(dp(30), dp(30), top = 6))
            row.addView(cell, lp(0, -2, 1f))
        }
        c.addView(row)
        return c
    }

    private fun doseCard(d: Dose, status: Char?, now: Int, day: String): View {
        val hue = d.med.hue
        val key = "${d.med.id}|${d.minute}"
        val missed = status == null && d.minute < now - 30
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(SURFACE, 22, this@MainActivity, if (missed) withAlpha(RED, 0.35f) else LINE)
        }
        card.addView(iconTile(R.drawable.ic_stat_pill, if (status == 'S') TRACK else hue.soft, if (status == 'S') MUTED else hue.main))

        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val nameRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        nameRow.addView(text(d.med.name, 17f, weight = 700).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp(-2, -2))
        if (d.med.strength.isNotBlank()) nameRow.addView(chip(d.med.strength, hue.soft, hue.main), lp(-2, -2, start = 8))
        info.addView(nameRow)
        val sub = listOfNotNull(fmtTime(d.minute), d.med.doseText, FOOD[d.med.food].takeIf { d.med.food > 0 }).joinToString("  ·  ")
        info.addView(text(sub, 13f, MUTED, weight = 500), lp(top = 4))
        if (missed) info.addView(text("Missed", 12f, RED, weight = 700), lp(top = 3))
        if (status == 'S') info.addView(text("Skipped", 12f, MUTED, weight = 700), lp(top = 3))
        if (status != null) info.alpha = 0.6f
        card.addView(info, lp(0, -2, 1f, start = 14))

        // Round check: tap to mark taken / undo.
        val check = FrameLayout(this).apply {
            background = if (status == 'T') circle(hue.main) else circle(Color.TRANSPARENT, if (missed) RED else withAlpha(hue.main, 0.55f), dp(2))
            if (status == 'T') addView(icon(R.drawable.ic_check, Color.WHITE, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener { setStatus(day, d, if (status == 'T') null else 'T') }
            pressable(22)
        }
        card.addView(check, lp(dp(42), dp(42), start = 8))
        if (popKey == key) { popKey = null; check.post { pop(check) } }

        card.setOnClickListener {
            if (status != null) { setStatus(day, d, null); return@setOnClickListener }
            AlertDialog.Builder(this).setTitle("${d.med.name} · ${fmtTime(d.minute)}")
                .setItems(arrayOf("Mark as taken", "Skip this dose", "Edit medicine")) { _, w ->
                    when (w) { 0 -> setStatus(day, d, 'T'); 1 -> setStatus(day, d, 'S')
                        else -> startActivity(Intent(this, AddActivity::class.java).putExtra("id", d.med.id)) }
                }.show()
        }
        card.setOnLongClickListener { startActivity(Intent(this, AddActivity::class.java).putExtra("id", d.med.id)); true }
        card.pressable(22)
        return card
    }

    private fun setStatus(day: String, d: Dose, st: Char?) {
        Store.setStatus(this, day, d.med.id, d.minute, st)
        if (st == 'T') popKey = "${d.med.id}|${d.minute}"
        render()
    }

    // --- Medicines ---
    private fun medicinesView(): View {
        val col = column()
        val meds = realMeds().sortedBy { it.name.lowercase() }
        if (meds.isEmpty()) { col.addView(emptyState("No medicines yet", "Tap + to add your first one.")); return col }
        meds.forEach { m ->
            val hue = m.hue
            val c = card(18)
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(iconTile(R.drawable.ic_stat_pill, hue.soft, hue.main, 50))
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text(m.name, 18f, weight = 700))
            info.addView(text(listOf(m.strength, m.category, m.doseText + " each time").filter { it.isNotBlank() }.joinToString("  ·  "), 13f, MUTED, weight = 500), lp(top = 3))
            top.addView(info, lp(0, -2, 1f, start = 14))
            c.addView(top)

            val times = LinearLayout(this)
            m.times.forEach { times.addView(chip(fmtTime(it), hue.soft, hue.main).apply { textSize = 13f }, lp(-2, -2, end = 6)) }
            c.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(times) }, lp(top = 14))
            val meta = listOfNotNull(m.daysText, FOOD[m.food].takeIf { m.food > 0 }, m.endDay?.let { "Until $it" }).joinToString("  ·  ")
            c.addView(text(meta, 13f, MUTED, weight = 500), lp(top = 10))
            m.daysLeft?.let { left ->
                val low = left <= m.alertDays
                val label = if (m.stock <= 0) "Out of stock" else "About $left day${if (left == 1) "" else "s"} left · ${fmtAmt(m.stock, m.category)} ${Cat.unit(m.category, m.stock)}"
                c.addView(text(label, 13f, if (low) RED else hue.main, weight = 700), lp(top = 12))
                c.addView(progressBar((left / 30f).coerceIn(0.04f, 1f), if (low) RED else hue.main), lp(-1, dp(6), top = 6))
            }
            if (m.notes.isNotBlank()) c.addView(text(m.notes, 13f, MUTED), lp(top = 10))
            c.setOnClickListener { startActivity(Intent(this, AddActivity::class.java).putExtra("id", m.id)) }
            c.pressable(22)
            col.addView(c, lp(bottom = 12))
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

        val hero = heroCard(if (Theme.dark) 0xFF3730A3.toInt() else 0xFF4F46E5.toInt())
        val ringBox = FrameLayout(this)
        val ring = Ring(this, Color.WHITE, withAlpha(Color.WHITE, 0.22f))
        ringBox.addView(ring, FrameLayout.LayoutParams(-1, -1))
        ringBox.addView(text(if (tot == 0) "–" else "$pct%", 20f, Color.WHITE, weight = 700).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        hero.addView(ringBox, lp(dp(88), dp(88)))
        ring.post { ring.animateTo(pct / 100f) }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(text("Last 7 days", 13f, withAlpha(Color.WHITE, 0.75f), weight = 500))
        info.addView(text(if (tot == 0) "No doses yet" else "$pct% on time", 24f, Color.WHITE, weight = 700), lp(top = 2))
        info.addView(chip("Streak: ${Store.streak(this)} day(s)", withAlpha(Color.WHITE, 0.18f), Color.WHITE), lp(-2, -2, top = 10))
        hero.addView(info, lp(0, -2, 1f, start = 18))
        col.addView(hero, lp(bottom = 12))

        // 14-day bar chart
        val chart = card(16)
        chart.addView(text("Two weeks", 15f, weight = 700))
        val bars = LinearLayout(this).apply { gravity = Gravity.BOTTOM }
        for (i in 13 downTo 0) {
            val (t, n) = stats[i]
            val f = if (n == 0) 0f else t / n.toFloat()
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM }
            val trackBox = FrameLayout(this).apply { background = rounded(TRACK, 6, this@MainActivity) }
            val bar = View(this).apply {
                background = rounded(when { n == 0 -> TRACK; f >= 1f -> TEAL; f > 0 -> 0xFFF59E0B.toInt(); else -> RED }, 6, this@MainActivity)
                pivotY = dpf(110f); scaleY = 0f
            }
            trackBox.addView(bar, FrameLayout.LayoutParams(-1, dp((110 * f.coerceAtLeast(if (n > 0) 0.06f else 0f)).toInt()), Gravity.BOTTOM))
            bar.post { bar.pivotY = bar.height.toFloat(); bar.animate().scaleY(1f).setStartDelay((13 - i) * 30L).setDuration(600).setInterpolator(EMPHASIZED).start() }
            cell.addView(trackBox, lp(dp(12), dp(110)))
            cell.addView(text(SimpleDateFormat("EEE", Locale.getDefault()).format(days[i].time).take(1), 11f,
                if (i == 0) TEAL else MUTED, weight = if (i == 0) 700 else 500), lp(-2, -2, top = 6))
            bars.addView(cell, lp(0, -2, 1f))
        }
        chart.addView(bars, lp(top = 14))
        col.addView(chart, lp(bottom = 4))

        col.addView(sectionLabel("Daily"), lp(top = 16))
        days.forEachIndexed { i, cal ->
            val (t, n) = stats[i]
            val row = card(14)
            val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(text(when (i) { 0 -> "Today"; 1 -> "Yesterday"; else -> SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(cal.time) }, 15f, weight = 600), lp(0, -2, 1f))
            head.addView(text(if (n == 0) "No doses" else "$t / $n", 14f, if (n > 0 && t == n) TEAL else MUTED, weight = 700))
            row.addView(head)
            if (n > 0) row.addView(progressBar(t / n.toFloat(), if (t == n) TEAL else 0xFFF59E0B.toInt()), lp(-1, dp(6), top = 10))
            col.addView(row, lp(bottom = 8))
        }
        return col
    }

    private fun toneTitle(): String {
        val uri = Store.alarmTone(this)?.let { Uri.parse(it) } ?: return "Default alarm tone"
        return try { RingtoneManager.getRingtone(this, uri)?.getTitle(this) } catch (_: Exception) { null } ?: "Default alarm tone"
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
    /** A tappable settings row: coloured icon tile, title, value, chevron. */
    private fun settingRow(iconRes: Int, hue: Hue, title: String, value: String, onClick: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(10))
        addView(iconTile(iconRes, hue.soft, hue.main, 42))
        val t = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        t.addView(text(title, 15f, weight = 600))
        if (value.isNotEmpty()) t.addView(text(value, 13f, MUTED, weight = 500), lp(top = 2))
        addView(t, lp(0, -2, 1f, start = 14))
        addView(text("›", 22f, MUTED))
        setOnClickListener { onClick() }
        pressable(14)
    }

    private fun settingsView(): View {
        val col = column()
        val pkg = Uri.parse("package:$packageName")

        col.addView(sectionLabel("Reminders", TEAL))
        val rem = card(16)
        rem.addView(text("Style", 15f, weight = 600))
        rem.addView(Segmented(this, listOf("Alarm", "Notification"), Store.alarmStyle(this)) { Store.setAlarmStyle(this, it) }, lp(top = 10))
        rem.addView(text("Alarm rings with your alarm tone and fills the screen. Notification is quieter.", 12f, MUTED), lp(top = 8))
        rem.addView(View(this).apply { setBackgroundColor(LINE) }, lp(-1, dp(1), top = 14, bottom = 4))
        rem.addView(settingRow(R.drawable.ic_alarm, HUES[4], "Alarm tone", toneTitle()) {
            startActivityForResult(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Store.alarmTone(this)?.let { Uri.parse(it) }), 42)
        })
        rem.addView(View(this).apply { setBackgroundColor(LINE) }, lp(-1, dp(1), top = 4, bottom = 14))
        rem.addView(text("Snooze length", 15f, weight = 600))
        val snoozes = listOf(5, 10, 15, 30)
        rem.addView(Segmented(this, snoozes.map { "$it min" }, snoozes.indexOf(Store.snoozeMinutes(this)).coerceAtLeast(1)) {
            Store.setSnoozeMinutes(this, snoozes[it])
        }, lp(top = 10))
        col.addView(rem, lp(bottom = 8))

        col.addView(sectionLabel("Permissions", 0xFF0EA5E9.toInt()), lp(top = 16))
        val perms = card(12)
        perms.addView(settingRow(R.drawable.ic_today, HUES[5], "Notifications", "Sound, banners and lock screen") {
            openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        })
        if (Build.VERSION.SDK_INT >= 34) perms.addView(settingRow(R.drawable.ic_alarm, HUES[1], "Full-screen alarm", "Show the alarm over the lock screen") {
            openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
        })
        if (!getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) perms.addView(settingRow(R.drawable.ic_alarm, HUES[3], "Exact alarms", "Needed for on-time reminders") {
            openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
        })
        col.addView(perms, lp(bottom = 8))

        col.addView(sectionLabel("Developer mode", 0xFF8B5CF6.toInt()), lp(top = 16))
        val dev = card(16)
        dev.addView(Segmented(this, listOf("Off", "On"), if (Store.devMode(this)) 1 else 0) {
            Store.setDevMode(this, it == 1); render()
        })
        if (Store.devMode(this)) {
            val delays = listOf(10, 30, 60)
            val seg = Segmented(this, delays.map { "$it s" }, 1)
            dev.addView(text("Ring a test alarm in", 14f, MUTED, weight = 500), lp(top = 18, bottom = 10))
            dev.addView(seg)
            dev.addView(pill("Schedule test alarm", TEAL, ON_TEAL, R.drawable.ic_alarm) {
                val secs = delays[seg.index]
                Scheduler.scheduleTest(this, secs)
                toast(if (getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) "Alarm in $secs s. Lock the phone to test the lock screen."
                      else "Scheduled, but exact alarms are off so it may be late.")
            }, lp(top = 14))
        }
        col.addView(dev, lp(bottom = 8))

        col.addView(sectionLabel("About", 0xFFF59E0B.toInt()), lp(top = 16))
        val about = card(18)
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(iconTile(R.drawable.ic_stat_pill, TEAL, ON_TEAL, 48))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(text("Med Reminder", 17f, weight = 700))
        names.addView(text("Version ${versionLabel()}", 13f, MUTED, weight = 500), lp(top = 2))
        head.addView(names, lp(0, -2, 1f, start = 14))
        about.addView(head)
        about.addView(text("Drug names and strengths come from the U.S. National Library of Medicine's RxNorm service. " +
            "Anything missing can be entered by hand. This app is a reminder aid and gives no medical advice. " +
            "Follow your doctor's instructions.\n\nFont: Space Grotesk (SIL Open Font License).", 13f, MUTED), lp(top = 14))
        col.addView(about)
        return col
    }
}
