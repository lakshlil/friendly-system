package com.lakshlabs.medreminder

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.UUID

class AddActivity : Activity() {
    private var editing: Medicine? = null
    private var variants: List<Drug.Variant> = emptyList()
    private var cat = "Tablet"
    private var reqId = 0
    private var suppress = false
    private val times = sortedSetOf(8 * 60)
    private val daySel = mutableSetOf<Int>()
    private var endExtra: String? = null
    private var lastAmount = 1.0
    private val newId = UUID.randomUUID().toString()
    private var colorIdx = 0
    private lateinit var colorRow: LinearLayout
    private lateinit var preview: FrameLayout
    private var amountList = listOf(1.0)

    private lateinit var name: EditText
    private lateinit var suggestions: LinearLayout
    private lateinit var lookup: TextView
    private lateinit var status: TextView
    private lateinit var formSeg: Segmented
    private lateinit var strengthDd: DropDown
    private lateinit var customRow: LinearLayout
    private lateinit var customVal: EditText
    private lateinit var customUnit: DropDown
    private lateinit var amountDd: DropDown
    private lateinit var timesSeg: Segmented
    private lateinit var timesBox: LinearLayout
    private lateinit var dayRow: LinearLayout
    private lateinit var foodSeg: Segmented
    private lateinit var durationDd: DropDown
    private lateinit var trackSeg: Segmented
    private lateinit var supplyBox: LinearLayout
    private lateinit var stockEt: EditText
    private lateinit var stockLabel: TextView
    private lateinit var alertDd: DropDown
    private lateinit var notes: EditText

    private val units = mutableListOf("mg", "mcg", "g", "mL", "IU", "%", "mg/mL", "mg/5 mL", "units")
    private val durations = listOf(0, 3, 5, 7, 10, 14, 30, 90)
    private val alertValues = listOf(2, 3, 5, 7, 14)
    private val defaultTimes = listOf(listOf(480), listOf(480, 1200), listOf(480, 840, 1260), listOf(480, 720, 960, 1200))

    /** A titled card holding one group of fields. */
    private fun section(title: String, vararg views: View) = card(16).apply {
        addView(text(title, 15f, weight = 700), lp(bottom = 12))
        views.forEachIndexed { i, v -> addView(v, lp(bottom = if (i == views.size - 1) 0 else 10)) }
    }

    override fun onCreate(b: Bundle?) {
        Theme.init(this)
        super.onCreate(b)
        editing = intent.getStringExtra("id")?.let { Store.find(this, it) }
        colorIdx = HUES.indexOf(editing?.hue ?: Medicine(id = newId, name = "", times = emptyList(), startDay = "").hue)
        Drug.preload(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(14), dp(20), dp(10)) }
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back); imageTintList = android.content.res.ColorStateList.valueOf(INK)
            setPadding(dp(12), dp(12), dp(12), dp(12)); background = circle(SURFACE, LINE, dp(1))
            setOnClickListener { finish() }; pressable(24)
        }, lp(dp(48), dp(48)))
        bar.addView(text(if (editing == null) "New medicine" else "Edit medicine", 24f, weight = 700), lp(0, -2, 1f, start = 14))
        preview = iconTile(R.drawable.ic_stat_pill, HUES[colorIdx].soft, HUES[colorIdx].main, 48)
        bar.addView(preview)
        root.addView(bar)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(6), dp(18), dp(24)) }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
        root.addView(scroll, lp(-1, 0, 1f))

        // --- Medicine name + lookup ---
        name = field("Search or type a name")
        suggestions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        lookup = text("", 14f, TEAL, weight = 700).apply {
            visibility = View.GONE; setPadding(dp(4), dp(6), dp(4), dp(6))
            setOnClickListener { fetch(Drug.apiNameFor(name.text.toString())) }
        }
        status = text("", 12f, MUTED)
        name.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (suppress) return
                reqId++; variants = emptyList(); status.text = ""
                val q = s.toString().trim()
                showSuggestions(Drug.suggest(q))
                lookup.visibility = if (q.length >= 3) View.VISIBLE else View.GONE
                lookup.text = "Look up strengths for “$q”"
                refreshStrength()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        colorRow = LinearLayout(this)
        HUES.forEachIndexed { i, _ ->
            colorRow.addView(FrameLayout(this).apply { tag = i; setOnClickListener { colorIdx = i; styleColors(); pop(preview) } },
                lp(0, dp(34), 1f, start = 3, end = 3))
        }
        styleColors()
        col.addView(section("Medicine", name, suggestions, lookup, status, colorRow), lp(bottom = 12))

        // --- Form + strength ---
        formSeg = Segmented(this, Cat.all, 0, equal = false) { cat = currentCats()[it]; refreshStrength() }
        strengthDd = DropDown(this, listOf("Custom…")) { customRow.visibility = if (it == strengthDd.itemCount - 1) View.VISIBLE else View.GONE }
        customVal = field("Strength", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        customUnit = DropDown(this, units)
        customRow = LinearLayout(this).apply { visibility = View.GONE; gravity = Gravity.CENTER_VERTICAL }
        customRow.addView(customVal, lp(0, -2, 1f, end = 8))
        customRow.addView(customUnit, lp(dp(130), -2))
        col.addView(section("Form & strength", HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(formSeg) }, strengthDd, customRow), lp(bottom = 12))

        // --- Dose per intake ---
        amountDd = DropDown(this, listOf("1 tablet")) { lastAmount = amountList.getOrElse(it) { 1.0 } }
        col.addView(section("Dose each time", amountDd), lp(bottom = 12))

        // --- Schedule ---
        timesSeg = Segmented(this, listOf("1×", "2×", "3×", "4×"), 0) {
            times.clear(); times.addAll(defaultTimes[it]); drawTimes()
        }
        timesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val addTime = pill("Add a time", TEAL_SOFT, TEAL, R.drawable.ic_add) {
            TimePickerDialog(this, { _, h, m -> times.add(h * 60 + m); drawTimes() }, 12, 0, false).show()
        }
        dayRow = LinearLayout(this)
        WEEK.forEach { (dow, label) ->
            dayRow.addView(TextView(this).apply {
                text = label.take(1); gravity = Gravity.CENTER; textSize = 14f; typeface = Fonts.get(700)
                tag = dow
                setOnClickListener { if (!daySel.add(dow)) daySel.remove(dow); styleDays() }
            }, lp(0, dp(40), 1f, start = 2, end = 2))
        }
        col.addView(section("How often", timesSeg, timesBox, addTime), lp(bottom = 12))
        col.addView(section("On which days", dayRow, text("Leave all off for every day.", 12f, MUTED)), lp(bottom = 12))

        // --- Food, duration ---
        foodSeg = Segmented(this, listOf("Anytime", "Before", "After", "With"), 0)
        col.addView(section("Food", foodSeg, text("Relative to a meal.", 12f, MUTED)), lp(bottom = 12))
        durationDd = DropDown(this, durations.map { if (it == 0) "Ongoing" else "$it days" })
        col.addView(section("Course length", durationDd), lp(bottom = 12))

        // --- Supply ---
        trackSeg = Segmented(this, listOf("Not tracking", "Track supply"), 0) { supplyBox.visibility = if (it == 1) View.VISIBLE else View.GONE }
        stockLabel = text("", 13f, MUTED)
        stockEt = field("Amount left", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        alertDd = DropDown(this, alertValues.map { "Remind me at $it days left" }, 1)
        supplyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        supplyBox.addView(stockLabel, lp(bottom = 6)); supplyBox.addView(stockEt, lp(bottom = 8)); supplyBox.addView(alertDd)
        col.addView(section("Supply", trackSeg, supplyBox), lp(bottom = 12))

        notes = field("Notes (optional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        col.addView(section("Notes", notes), lp(bottom = 12))

        editing?.let { m -> col.addView(pill("Delete this medicine", RED_SOFT, RED) {
            AlertDialog.Builder(this@AddActivity).setTitle("Delete ${m.name}?")
                .setMessage("Its reminders will stop.")
                .setPositiveButton("Delete") { _, _ -> Store.delete(this@AddActivity, m.id); finish() }
                .setNegativeButton("Cancel", null).show()
        }, lp(top = 4)) }

        val bottom = LinearLayout(this).apply { setPadding(dp(18), dp(10), dp(18), dp(18)); setBackgroundColor(BG) }
        bottom.addView(pill("Save", TEAL, ON_TEAL, R.drawable.ic_check) { save() }, lp())
        root.addView(bottom)
        setContentView(root)
        animateIn((0 until col.childCount).map { col.getChildAt(it) }, 50)

        editing?.let { prefill(it) } ?: run { drawTimes(); styleDays(); refreshStrength() }
    }

    // ---------- lookup ----------
    private fun showSuggestions(list: List<Drug.Suggestion>) {
        suggestions.removeAllViews()
        suggestions.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        suggestions.background = if (list.isEmpty()) null else rounded(SURFACE, 16, this, LINE)
        list.forEach { s ->
            suggestions.addView(text(s.label, 16f, weight = 500).apply {
                setPadding(dp(16), dp(13), dp(16), dp(13)); pressable(12)
                setOnClickListener {
                    suppress = true; name.setText(s.medName); name.setSelection(name.text.length); suppress = false
                    suggestions.visibility = View.GONE; lookup.visibility = View.GONE
                    fetch(s.apiName)
                }
            })
        }
    }

    private fun fetch(api: String) {
        val my = ++reqId
        status.text = "Loading strengths…"
        Drug.variants(api) { list, offline ->
            if (my != reqId) return@variants
            variants = list ?: emptyList()
            status.text = when {
                list == null -> "Couldn't reach the drug database. Enter the strength below."
                list.isEmpty() -> "No match in the database. Enter the strength below."
                offline -> "Common strengths (offline list)."
                else -> "Strengths from NIH RxNorm."
            }
            lookup.visibility = View.GONE
            if (variants.isNotEmpty() && variants.none { it.category == cat }) cat = variants.first().category
            refreshStrength()
        }
    }

    private fun currentCats(): List<String> =
        if (variants.isEmpty()) Cat.all else Cat.all.filter { c -> variants.any { it.category == c } }

    private fun refreshStrength(selectLabel: String? = null) {
        val cats = currentCats()
        if (cat !in cats) cat = cats.first()
        formSeg.setOptions(cats, cats.indexOf(cat))
        val vs = variants.filter { it.category == cat }
        val items = listOf("Choose strength…") + vs.map { it.label } + "Custom…"
        val sel = when {
            selectLabel != null && selectLabel in items -> items.indexOf(selectLabel)
            vs.isEmpty() -> items.size - 1
            else -> 0
        }
        strengthDd.setItems(items, sel)
        customRow.visibility = if (sel == items.size - 1) View.VISIBLE else View.GONE
        refreshAmount()
    }

    private fun refreshAmount(keep: Double? = null) {
        val presets = Cat.amounts(cat)
        val want = keep ?: lastAmount
        val list = if (want in presets) presets else (presets + want).sorted()
        amountList = list; lastAmount = want
        amountDd.setItems(list.map { "${fmtAmt(it, cat)} ${Cat.unit(cat, it)}" }, list.indexOf(want).coerceAtLeast(0))
        stockLabel.text = "How many ${Cat.unit(cat, 2.0)} do you have left?"
    }

    // ---------- schedule ----------
    private fun drawTimes() {
        timesBox.removeAllViews()
        times.toList().forEach { t ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(8), dp(10))
                background = rounded(SURFACE, 16, this@AddActivity, LINE)
                setOnClickListener {
                    TimePickerDialog(this@AddActivity, { _, h, m -> times.remove(t); times.add(h * 60 + m); drawTimes() }, t / 60, t % 60, false).show()
                }
            }
            row.addView(text(fmtTime(t), 17f, bold = true), lp(0, -2, 1f))
            if (times.size > 1) row.addView(text("Remove", 14f, RED, true).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { times.remove(t); drawTimes() }
            })
            timesBox.addView(row, lp(bottom = 8))
        }
        val match = defaultTimes.indexOfFirst { it == times.toList() }
        timesSeg.select(if (match >= 0) match else -1)
    }

    private fun styleColors() {
        for (i in 0 until colorRow.childCount) {
            val v = colorRow.getChildAt(i) as FrameLayout
            val sel = i == colorIdx
            v.removeAllViews()
            val box = FrameLayout(this).apply { background = if (sel) circle(Color.TRANSPARENT, HUES[i].main, dp(2)) else null }
            box.addView(View(this).apply { background = circle(HUES[i].main) },
                FrameLayout.LayoutParams(dp(if (sel) 24 else 28), dp(if (sel) 24 else 28), Gravity.CENTER))
            if (sel) box.addView(icon(R.drawable.ic_check, Color.WHITE, 14), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
            v.addView(box, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        }
        if (::preview.isInitialized) {
            preview.background = rounded(HUES[colorIdx].soft, 15, this)
            (preview.getChildAt(0) as ImageView).imageTintList = android.content.res.ColorStateList.valueOf(HUES[colorIdx].main)
        }
    }

    private fun styleDays() {
        for (i in 0 until dayRow.childCount) {
            val v = dayRow.getChildAt(i) as TextView
            val on = (v.tag as Int) in daySel
            v.setTextColor(if (on) ON_TEAL else INK)
            v.background = rounded(if (on) TEAL else SURFACE, 20, this, if (on) TEAL else LINE)
        }
    }

    // ---------- edit / save ----------
    private fun prefill(m: Medicine) {
        suppress = true; name.setText(m.name); suppress = false
        cat = m.category
        times.clear(); times.addAll(m.times); drawTimes()
        daySel.addAll(m.days); styleDays()
        foodSeg.select(m.food)
        formSeg.setOptions(Cat.all, Cat.all.indexOf(cat))
        refreshStrength()
        // Strength is stored as text like "500 mg": show it in the custom field.
        if (m.strength.isNotBlank()) {
            val v = m.strength.substringBefore(' '); val u = m.strength.substringAfter(' ', "")
            if (u.isNotEmpty() && u !in units) units.add(u)
            customUnit.setItems(units, units.indexOf(u).coerceAtLeast(0))
            customVal.setText(v)
            strengthDd.select(strengthDd.itemCount - 1); customRow.visibility = View.VISIBLE
        }
        refreshAmount(m.amount)
        m.endDay?.let { end ->
            val idx = durations.indexOfFirst { it > 0 && Store.dayStr(Store.daysFromNow(it - 1)) == end }
            if (idx > 0) durationDd.select(idx) else {
                endExtra = end
                durationDd.setItems(durations.map { if (it == 0) "Ongoing" else "$it days" } + "Until $end", durations.size)
            }
        }
        if (m.stock >= 0) {
            trackSeg.select(1); supplyBox.visibility = View.VISIBLE
            stockEt.setText(fmtAmt(m.stock, m.category))
            alertDd.select(alertValues.indexOf(m.alertDays).coerceAtLeast(0))
        }
        notes.setText(m.notes)
    }

    private fun save() {
        val n = name.text.toString().trim()
        if (n.isEmpty()) { name.error = "Enter a name"; return }
        val vs = variants.filter { it.category == cat }
        val sIdx = strengthDd.index
        val strength = when {
            sIdx in 1..vs.size -> vs[sIdx - 1].strength
            sIdx == strengthDd.itemCount - 1 && customVal.text.isNotBlank() ->
                customVal.text.toString().trim() + " " + units[customUnit.index.coerceIn(0, units.size - 1)]
            else -> ""
        }
        val dIdx = durationDd.index
        val end = when {
            dIdx == 0 -> null
            dIdx < durations.size -> Store.dayStr(Store.daysFromNow(durations[dIdx] - 1))
            else -> endExtra
        }
        val stock = if (trackSeg.index == 1) stockEt.text.toString().toDoubleOrNull() ?: -1.0 else -1.0
        Store.upsert(this, Medicine(
            id = editing?.id ?: newId, name = n, strength = strength, category = cat,
            amount = amountList.getOrElse(amountDd.index) { 1.0 },
            times = times.toList(), days = if (daySel.size == 7) emptySet() else daySel.toSet(),
            food = foodSeg.index, startDay = editing?.startDay ?: Store.dayStr(), endDay = end,
            stock = stock, alertDays = alertValues[alertDd.index.coerceIn(0, alertValues.size - 1)],
            notes = notes.text.toString().trim(), color = colorIdx,
        ))
        finish()
    }
}
