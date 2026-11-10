package com.lakshlabs.medreminder

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.*

// ---------------------------------------------------------------- theme

/** Light/dark is read from the system each time an activity starts. */
object Theme {
    var dark = false
    fun init(a: Activity) {
        dark = (a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        Fonts.init(a)
    }
}

private fun pick(light: Long, dark: Long) = (if (Theme.dark) dark else light).toInt()

val TEAL get() = pick(0xFF0F766E, 0xFF2DD4BF)
val ON_TEAL get() = pick(0xFFFFFFFF, 0xFF042F2E)
val TEAL_SOFT get() = pick(0xFFE3F3EF, 0xFF132E2B)
val INK get() = pick(0xFF14211F, 0xFFE8F0EF)
val MUTED get() = pick(0xFF667A77, 0xFF93A7A4)
val BG get() = pick(0xFFF4F7F6, 0xFF0B1211)
val SURFACE get() = pick(0xFFFFFFFF, 0xFF141D1C)
val LINE get() = pick(0xFFE2E9E8, 0xFF22302E)
val TRACK get() = pick(0xFFE9EFEE, 0xFF1B2725)
val RED get() = pick(0xFFDC2626, 0xFFF87171)
val RED_SOFT get() = pick(0xFFFDECEC, 0xFF3A1717)
val AMBER get() = pick(0xFF8A5300, 0xFFFBBF24)
val AMBER_BG get() = pick(0xFFFFF3DA, 0xFF33270D)

/** Colour for one medicine: a strong tone for icons and a soft tint for tiles. */
class Hue(val name: String, private val base: Int) {
    val main: Int get() = if (Theme.dark) blend(base, Color.WHITE, 0.25f) else base
    val soft: Int get() = blend(base, SURFACE, if (Theme.dark) 0.78f else 0.88f)
}

val HUES = listOf(
    Hue("Teal", 0xFF0F766E.toInt()), Hue("Indigo", 0xFF4F46E5.toInt()), Hue("Rose", 0xFFE11D48.toInt()),
    Hue("Amber", 0xFFD97706.toInt()), Hue("Violet", 0xFF7C3AED.toInt()), Hue("Sky", 0xFF0284C7.toInt()),
    Hue("Green", 0xFF16A34A.toInt()), Hue("Orange", 0xFFEA580C.toInt()),
)

/** Mixes [a] toward [b] by [t] (0 = a, 1 = b). */
fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
    (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
    (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
    (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt())

fun withAlpha(c: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

/** Space Grotesk in four weights (bundled in res/font). */
object Fonts {
    private val cache = HashMap<Int, Typeface>()
    private var ctx: Context? = null
    fun init(c: Context) { if (ctx == null) ctx = c.applicationContext }
    fun get(weight: Int): Typeface = cache.getOrPut(weight) {
        val res = when { weight >= 700 -> R.font.space_grotesk_700; weight >= 600 -> R.font.space_grotesk_600
            weight >= 500 -> R.font.space_grotesk_500; else -> R.font.space_grotesk_400 }
        try { ctx!!.resources.getFont(res) } catch (_: Exception) { Typeface.DEFAULT }
    }
}

// ---------------------------------------------------------------- basics

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density

fun rounded(fill: Int, radiusDp: Int, ctx: Context, stroke: Int = 0, strokeDp: Int = 1) = GradientDrawable().apply {
    setColor(fill); cornerRadius = ctx.dp(radiusDp).toFloat()
    if (stroke != 0) setStroke(ctx.dp(strokeDp), stroke)
}

fun circle(fill: Int, stroke: Int = 0, strokePx: Int = 0) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL; setColor(fill); if (stroke != 0) setStroke(strokePx, stroke)
}

fun Context.lp(w: Int = -1, h: Int = -2, weight: Float = 0f, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0) =
    LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(start), dp(top), dp(end), dp(bottom)) }

fun Context.text(s: String, sp: Float, color: Int = INK, bold: Boolean = false, weight: Int = if (bold) 700 else 400) =
    TextView(this).apply {
        text = s; textSize = sp; setTextColor(color); typeface = Fonts.get(weight)
        setLineSpacing(0f, 1.1f)
    }

fun Context.icon(res: Int, tint: Int, sizeDp: Int = 24) = ImageView(this).apply {
    setImageResource(res); imageTintList = ColorStateList.valueOf(tint)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** Ripple plus a gentle squash while pressed. Keeps the click listener working. */
fun View.pressable(radiusDp: Int = 20, rippleColor: Int = withAlpha(INK, 0.08f)): View {
    foreground = RippleDrawable(ColorStateList.valueOf(rippleColor), null, rounded(Color.WHITE, radiusDp, context))
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(OvershootInterpolator(2.5f)).start()
        }
        false
    }
    return this
}

fun Context.pill(label: String, fill: Int, fg: Int, iconRes: Int = 0, onClick: () -> Unit) = LinearLayout(this).apply {
    gravity = Gravity.CENTER
    setPadding(dp(20), dp(15), dp(20), dp(15))
    background = rounded(fill, 26, this@pill)
    if (iconRes != 0) addView(icon(iconRes, fg, 20), lp(dp(20), dp(20), end = 8))
    addView(text(label, 15f, fg, weight = 600))
    setOnClickListener { onClick() }
    pressable(26, withAlpha(fg, 0.18f))
}

fun Context.card(pad: Int = 16) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    background = rounded(SURFACE, 22, this@card, LINE)
}

fun Context.field(hint: String, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) =
    EditText(this).apply {
        this.hint = hint; inputType = type; textSize = 16f; typeface = Fonts.get(500)
        setTextColor(INK); setHintTextColor(MUTED)
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(SURFACE, 16, this@field, TEAL, 2))
            addState(intArrayOf(), rounded(SURFACE, 16, this@field, LINE))
        }
    }

fun Context.sectionLabel(s: String, dot: Int = 0) = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(4), 0, 0, dp(10))
    if (dot != 0) addView(View(this@sectionLabel).apply { background = circle(dot) }, lp(dp(8), dp(8), end = 8))
    addView(text(s.uppercase(), 12f, MUTED, weight = 700).apply { letterSpacing = 0.1f })
}

/** Rounded icon tile used on cards. */
fun Context.iconTile(res: Int, fill: Int, tint: Int, size: Int = 46) = FrameLayout(this).apply {
    background = rounded(fill, 15, this@iconTile)
    addView(ImageView(this@iconTile).apply { setImageResource(res); imageTintList = ColorStateList.valueOf(tint) },
        FrameLayout.LayoutParams(dp(size / 2), dp(size / 2), Gravity.CENTER))
    layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
}

fun Context.chip(s: String, fill: Int, fg: Int) = text(s, 12f, fg, weight = 600).apply {
    setPadding(dp(10), dp(5), dp(10), dp(5)); background = rounded(fill, 12, this@chip)
}

/** Bar that grows to its value when shown. */
fun Context.progressBar(frac: Float, fill: Int = TEAL, track: Int = TRACK) = FrameLayout(this).apply {
    background = rounded(track, 6, this@progressBar)
    val bar = View(this@progressBar).apply { background = rounded(fill, 6, this@progressBar); pivotX = 0f; scaleX = 0f }
    addView(bar, FrameLayout.LayoutParams(-1, -1))
    post { bar.animate().scaleX(frac.coerceIn(0f, 1f)).setDuration(800).setInterpolator(DecelerateInterpolator(2f)).start() }
}

/** Circular progress that sweeps in when shown. */
class Ring(ctx: Context, private val color: Int, private val track: Int, private val strokeDp: Float = 9f) : View(ctx) {
    private var shown = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    fun animateTo(target: Float) {
        ValueAnimator.ofFloat(0f, target.coerceIn(0f, 1f)).apply {
            duration = 1000; interpolator = EMPHASIZED
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
        }.start()
    }
    override fun onDraw(c: Canvas) {
        val s = context.dpf(strokeDp); paint.strokeWidth = s
        val r = RectF(s / 2, s / 2, width - s / 2, height - s / 2)
        paint.color = track; c.drawArc(r, 0f, 360f, false, paint)
        if (shown > 0f) { paint.color = color; c.drawArc(r, -90f, 360f * shown, false, paint) }
    }
}

val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)

/** Staggered fade-and-rise for a list of views. */
fun animateIn(views: List<View>, step: Long = 45) {
    views.forEachIndexed { i, v ->
        v.alpha = 0f; v.translationY = v.context.dpf(20f)
        v.animate().alpha(1f).translationY(0f).setStartDelay(i * step).setDuration(420).setInterpolator(EMPHASIZED).start()
    }
}

/** Bouncy scale-in, used when something gets ticked. */
fun pop(v: View) {
    v.scaleX = 0.5f; v.scaleY = 0.5f
    v.animate().scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(OvershootInterpolator(3f)).start()
}

// ---------------------------------------------------------------- controls

/** Segmented buttons with a sliding highlight. One option is selected at a time. */
class Segmented(ctx: Context, options: List<String>, selected: Int = 0, private val equal: Boolean = true,
                private val onChange: (Int) -> Unit = {}) : FrameLayout(ctx) {
    private var opts = options
    private val row = LinearLayout(ctx)
    private val indicator = View(ctx)
    private var sliding = false
    var index = selected
        private set

    init {
        background = rounded(TRACK, 16, ctx)
        setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        indicator.background = rounded(TEAL, 13, ctx)
        addView(indicator, LayoutParams(0, 0))
        addView(row, LayoutParams(if (equal) -1 else -2, -2))
        build()
    }

    private fun build() {
        row.removeAllViews()
        opts.forEachIndexed { i, o ->
            row.addView(TextView(context).apply {
                text = o; textSize = 14f; gravity = Gravity.CENTER; maxLines = 1
                val h = if (equal) 6 else 14
                setPadding(context.dp(h), context.dp(11), context.dp(h), context.dp(11))
                setOnClickListener { if (i != index) { index = i; style(); slide(true); onChange(i) } }
                // Place the highlight once the selected button has its real size.
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (i == index && !sliding) slide(false) }
            }, if (equal) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-2, -2))
        }
        style()
        indicator.visibility = INVISIBLE
        indicator.layoutParams = indicator.layoutParams.apply { width = 0 }
        post { slide(false) }
    }

    private fun style() {
        for (i in 0 until row.childCount) (row.getChildAt(i) as TextView).apply {
            val sel = i == index
            setTextColor(if (sel) ON_TEAL else INK); typeface = Fonts.get(if (sel) 700 else 500)
        }
    }

    private fun slide(animate: Boolean) {
        val child = row.getChildAt(index)
        if (child == null || child.width == 0) { indicator.visibility = INVISIBLE; return }
        val toX = child.left.toFloat(); val toW = child.width
        val lp = indicator.layoutParams
        // Size from the button itself: a fill-parent height collapses to 0 inside a HorizontalScrollView.
        if (lp.height != child.height) { lp.height = child.height; indicator.layoutParams = lp }
        if (!animate || lp.width == 0 || indicator.visibility != VISIBLE) {
            lp.width = toW; indicator.layoutParams = lp; indicator.translationX = toX; indicator.visibility = VISIBLE; return
        }
        val fromX = indicator.translationX; val fromW = lp.width
        sliding = true
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340; interpolator = EMPHASIZED
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { sliding = false }
            })
            addUpdateListener {
                val f = it.animatedValue as Float
                indicator.translationX = fromX + (toX - fromX) * f
                lp.width = (fromW + (toW - fromW) * f).toInt(); indicator.layoutParams = lp
            }
        }.start()
    }

    /** Changes the highlighted option without calling onChange. */
    fun select(i: Int) { index = i; style(); post { slide(true) } }
    fun setOptions(list: List<String>, selected: Int = 0) { opts = list; index = selected; build() }
}

/** Dropdown (Spinner) with a rounded outline and an arrow. */
class DropDown(ctx: Context, items: List<String>, select: Int = 0, private val onSelect: (Int) -> Unit = {}) : FrameLayout(ctx) {
    private val spinner = Spinner(ctx, Spinner.MODE_DROPDOWN)
    private var current = -1
    val index: Int get() = spinner.selectedItemPosition
    val itemCount: Int get() = spinner.adapter?.count ?: 0

    init {
        background = rounded(SURFACE, 16, ctx, LINE)
        spinner.background = null
        spinner.setPopupBackgroundDrawable(rounded(SURFACE, 16, ctx, LINE))
        addView(spinner, LayoutParams(-1, ctx.dp(54)))
        addView(TextView(ctx).apply { text = "▾"; textSize = 16f; setTextColor(TEAL) },
            LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = ctx.dp(16) })
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
            private fun style(v: View, drop: Boolean): View {
                (v as TextView).apply { setTextColor(INK); textSize = 16f; maxLines = 1; typeface = Fonts.get(500)
                    setPadding(context.dp(16), context.dp(if (drop) 14 else 12), context.dp(40), context.dp(if (drop) 14 else 12)) }
                return v
            }
            override fun getView(pos: Int, cv: View?, parent: ViewGroup) = style(super.getView(pos, cv, parent), false)
            override fun getDropDownView(pos: Int, cv: View?, parent: ViewGroup) = style(super.getDropDownView(pos, cv, parent), true)
        }
        current = select.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        spinner.setSelection(current, false)
    }

    fun select(i: Int) { current = i; spinner.setSelection(i, false) }
}
