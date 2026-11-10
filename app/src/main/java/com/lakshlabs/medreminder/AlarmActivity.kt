package com.lakshlabs.medreminder

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.*

/** Full-screen alarm shown over the lock screen at dose time. Always dark, whatever the theme. */
class AlarmActivity : Activity() {
    private var minute = -1
    private lateinit var body: LinearLayout
    private val anims = ArrayList<ValueAnimator>()

    private val bg = 0xFF061F1D.toInt()
    private val cardBg = 0xFF0E302C.toInt()
    private val mint = 0xFF5EEAD4.toInt()
    private val soft = 0xFFB4D3CF.toInt()

    override fun onCreate(b: Bundle?) {
        Theme.init(this)
        super.onCreate(b)
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = bg; window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg)
            setPadding(dp(22), dp(36), dp(22), dp(26))
        }
        setContentView(body)
        minute = intent.getIntExtra("minute", -1)
        render(first = true)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        minute = i.getIntExtra("minute", minute)
        render()
    }

    override fun onDestroy() { anims.forEach { it.cancel() }; super.onDestroy() }

    private fun done() { AlarmService.stop(this); finish() }

    /** Pulsing rings around a wiggling alarm icon. */
    private fun pulse(): View {
        val box = FrameLayout(this)
        repeat(3) { k ->
            val ring = View(this).apply { background = circle(withAlpha(mint, 0.22f)); alpha = 0f }
            box.addView(ring, FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER))
            anims += ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2400; startDelay = k * 800L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { val f = it.animatedValue as Float; ring.scaleX = 0.55f + f * 0.75f; ring.scaleY = ring.scaleX; ring.alpha = 1f - f }
                start()
            }
        }
        val core = FrameLayout(this).apply { background = circle(mint) }
        val bell = ImageView(this).apply { setImageResource(R.drawable.ic_alarm); imageTintList = ColorStateList.valueOf(bg) }
        core.addView(bell, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        box.addView(core, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER))
        anims += ValueAnimator.ofFloat(-1f, 1f).apply {
            duration = 140; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
            addUpdateListener { bell.rotation = (it.animatedValue as Float) * 12f }
            start()
        }
        return box
    }

    private fun render(first: Boolean = false) {
        val pending = Store.dueDoses(this, minute)
        if (pending.isEmpty()) { done(); return }
        val day = Store.dayStr()
        anims.forEach { it.cancel() }; anims.clear()
        body.removeAllViews()

        body.addView(pulse(), lp(-1, dp(170)))
        body.addView(text("MEDICINE TIME", 13f, mint, weight = 700).apply { letterSpacing = 0.16f; gravity = Gravity.CENTER }, lp(top = 6))
        body.addView(text(fmtTime(pending.firstOrNull { it.minute == minute }?.minute ?: pending.first().minute), 56f, Color.WHITE, weight = 700).apply { gravity = Gravity.CENTER }, lp(top = 2, bottom = 18))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pending.forEach { d ->
            val hue = d.med.hue
            val tone = blend(hue.main, Color.WHITE, 0.15f)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
                background = rounded(cardBg, 24, this@AlarmActivity)
            }
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(iconTile(R.drawable.ic_stat_pill, withAlpha(tone, 0.22f), tone, 50))
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text(d.med.name, 20f, Color.WHITE, weight = 700).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            if (d.minute != minute) info.addView(text("Due ${fmtTime(d.minute)} · not taken yet", 12f, 0xFFFCA5A5.toInt(), weight = 700), lp(top = 3))
            info.addView(text(listOfNotNull(d.med.strength.takeIf { it.isNotBlank() }, d.med.doseText,
                FOOD[d.med.food].takeIf { d.med.food > 0 }).joinToString("  ·  "), 14f, soft, weight = 500), lp(top = 4))
            top.addView(info, lp(0, -2, 1f, start = 14))
            card.addView(top)
            val actions = LinearLayout(this)
            actions.addView(pill("Skip", withAlpha(Color.WHITE, 0.08f), soft) {
                Store.setStatus(this@AlarmActivity, day, d.med.id, d.minute, 'S'); render()
            }, lp(0, -2, 1f, end = 6))
            actions.addView(pill("Taken", mint, bg, R.drawable.ic_check) {
                Store.setStatus(this, day, d.med.id, d.minute, 'T'); render()
            }, lp(0, -2, 1.6f, start = 6))
            card.addView(actions, lp(top = 14))
            list.addView(card, lp(bottom = 10))
        }
        body.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }, lp(-1, 0, 1f))

        if (pending.size > 1) body.addView(pill("Taken all", mint, bg, R.drawable.ic_check) {
            pending.forEach { Store.setStatus(this, day, it.med.id, it.minute, 'T') }; done()
        }, lp(top = 8))
        body.addView(pill("Snooze ${Store.snoozeMinutes(this)} min", cardBg, Color.WHITE, R.drawable.ic_alarm) {
            pending.forEach { Scheduler.snooze(this, it.med.id, it.minute) }; done()
        }, lp(top = 10))

        if (first) animateIn((0 until body.childCount).map { body.getChildAt(it) }, 70)
    }

    /** Back silences the alarm but keeps the reminder in the notification shade. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { AlarmService.silence(this); finish() }
}
