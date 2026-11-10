package com.lakshlabs.medreminder

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

const val TEAL = 0xFF0F766E.toInt()
const val TEAL_SOFT = 0xFFE6F4F1.toInt()
const val INK = 0xFF16302D.toInt()
const val MUTED = 0xFF6B7F7C.toInt()
const val BG = 0xFFF6F8F8.toInt()
const val LINE = 0xFFE3EAE9.toInt()
const val RED = 0xFFB42318.toInt()
const val AMBER_BG = 0xFFFFF1D6.toInt()
const val AMBER = 0xFF7A4B00.toInt()

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun rounded(fill: Int, radiusDp: Int, ctx: Context, stroke: Int = 0) = GradientDrawable().apply {
    setColor(fill); cornerRadius = ctx.dp(radiusDp).toFloat()
    if (stroke != 0) setStroke(ctx.dp(1), stroke)
}

fun Context.lp(w: Int = -1, h: Int = -2, weight: Float = 0f, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0) =
    LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(start), dp(top), dp(end), dp(bottom)) }

fun Context.text(s: String, sp: Float, color: Int = INK, bold: Boolean = false) = TextView(this).apply {
    text = s; textSize = sp; setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

fun Context.pill(label: String, fill: Int, fg: Int, onClick: () -> Unit) = TextView(this).apply {
    text = label; textSize = 15f; setTextColor(fg)
    setTypeface(typeface, Typeface.BOLD)
    gravity = Gravity.CENTER
    setPadding(dp(18), dp(13), dp(18), dp(13))
    background = rounded(fill, 24, this@pill)
    setOnClickListener { onClick() }
}

fun Context.card(pad: Int = 16) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    background = rounded(Color.WHITE, 18, this@card, LINE)
}

fun Context.field(hint: String, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) =
    EditText(this).apply {
        this.hint = hint; inputType = type; textSize = 16f
        setPadding(dp(16), dp(13), dp(16), dp(13))
        background = rounded(Color.WHITE, 14, this@field, LINE)
    }

fun Context.sectionLabel(s: String) = text(s.uppercase(), 12f, MUTED, bold = true).apply {
    letterSpacing = 0.08f
    setPadding(dp(2), 0, 0, dp(8))
}

/** Round icon tile used on cards. */
fun Context.iconTile(res: Int, fill: Int, tint: Int, size: Int = 46) = FrameLayout(this).apply {
    background = rounded(fill, 14, this@iconTile)
    addView(ImageView(this@iconTile).apply { setImageResource(res); setColorFilter(tint) },
        FrameLayout.LayoutParams(dp(size - 22), dp(size - 22), Gravity.CENTER))
    layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
}

fun Context.progressBar(frac: Float, fill: Int = TEAL, track: Int = LINE) = LinearLayout(this).apply {
    background = rounded(track, 6, this@progressBar)
    val f = frac.coerceIn(0f, 1f)
    addView(View(this@progressBar).apply { background = rounded(fill, 6, this@progressBar) }, LinearLayout.LayoutParams(0, -1, f))
    addView(View(this@progressBar), LinearLayout.LayoutParams(0, -1, 1f - f))
}

/** Segmented button row: one option is selected at a time. */
class Segmented(ctx: Context, options: List<String>, selected: Int = 0, private val equal: Boolean = true,
                private val onChange: (Int) -> Unit = {}) : LinearLayout(ctx) {
    private var opts = options
    var index = selected
        private set

    init { orientation = HORIZONTAL; background = rounded(0xFFE6ECEB.toInt(), 14, ctx); setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3)); build() }

    private fun build() {
        removeAllViews()
        opts.forEachIndexed { i, o ->
            val sel = i == index
            addView(TextView(context).apply {
                text = o; textSize = 14f; gravity = Gravity.CENTER; maxLines = 1
                setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
                setTextColor(if (sel) Color.WHITE else INK)
                if (sel) { background = rounded(TEAL, 12, context); setTypeface(typeface, Typeface.BOLD) }
                setOnClickListener { index = i; build(); onChange(i) }
            }, if (equal) LayoutParams(0, -2, 1f) else LayoutParams(-2, -2))
        }
    }

    /** Changes the highlighted option without calling onChange. */
    fun select(i: Int) { index = i; build() }
    fun setOptions(list: List<String>, selected: Int = 0) { opts = list; index = selected; build() }
}

/** Dropdown (Spinner) with a rounded outline and an arrow. */
class DropDown(ctx: Context, items: List<String>, select: Int = 0, private val onSelect: (Int) -> Unit = {}) : FrameLayout(ctx) {
    private val spinner = Spinner(ctx)
    private var current = -1
    val index: Int get() = spinner.selectedItemPosition
    val itemCount: Int get() = spinner.adapter?.count ?: 0

    init {
        background = rounded(Color.WHITE, 14, ctx, LINE)
        spinner.background = null
        addView(spinner, LayoutParams(-1, ctx.dp(50)))
        addView(TextView(ctx).apply { text = "▾"; textSize = 16f; setTextColor(MUTED) },
            LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = ctx.dp(14) })
        setItems(items, select)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos != current) { current = pos; onSelect(pos) }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    fun setItems(items: List<String>, select: Int = 0) {
        spinner.adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, items) {
            private fun style(v: View): View {
                (v as TextView).apply { setTextColor(INK); textSize = 16f; maxLines = 1
                    setPadding(context.dp(14), context.dp(12), context.dp(36), context.dp(12)) }
                return v
            }
            override fun getView(pos: Int, cv: View?, parent: ViewGroup) = style(super.getView(pos, cv, parent))
            override fun getDropDownView(pos: Int, cv: View?, parent: ViewGroup) = style(super.getDropDownView(pos, cv, parent))
        }
        current = select.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        spinner.setSelection(current, false)
    }

    fun select(i: Int) { current = i; spinner.setSelection(i, false) }
}
