package com.lakshlabs.medreminder

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.*

/** Full-screen alarm shown over the lock screen at dose time. */
class AlarmActivity : Activity() {
    private var minute = -1
    private lateinit var body: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0B2E2B.toInt())
            setPadding(dp(24), dp(56), dp(24), dp(28))
        }
        setContentView(body)
        minute = intent.getIntExtra("minute", -1)
        render()
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        minute = i.getIntExtra("minute", minute)
        render()
    }

    private fun done() { AlarmService.stop(this); finish() }

    private fun render() {
        val pending = Store.pendingAt(this, minute)
        if (pending.isEmpty()) { done(); return }
        val day = Store.dayStr()
        body.removeAllViews()
        body.addView(text("MEDICINE TIME", 13f, 0xFF99F6E4.toInt(), bold = true).apply { letterSpacing = 0.12f; gravity = Gravity.CENTER }, lp())
        body.addView(text(fmtTime(minute), 52f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }, lp(top = 4, bottom = 20))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pending.forEach { d ->
            val card = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(12), dp(14))
                background = rounded(0xFF12413D.toInt(), 18, this@AlarmActivity)
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text(d.med.name + if (d.med.strength.isNotBlank()) "  ${d.med.strength}" else "", 19f, Color.WHITE, bold = true))
            info.addView(text(listOfNotNull(d.med.doseText, FOOD[d.med.food].takeIf { d.med.food > 0 }).joinToString(" · "), 14f, 0xFFB7D6D2.toInt()))
            card.addView(info, lp(0, -2, 1f))
            card.addView(text("Skip", 14f, 0xFFB7D6D2.toInt(), bold = true).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { Store.setStatus(this@AlarmActivity, day, d.med.id, d.minute, 'S'); render() }
            })
            card.addView(pill("Taken", Color.WHITE, TEAL) { Store.setStatus(this, day, d.med.id, d.minute, 'T'); render() })
            list.addView(card, lp(bottom = 10))
        }
        body.addView(ScrollView(this).apply { addView(list) }, lp(-1, 0, 1f))

        if (pending.size > 1) body.addView(pill("Taken all", Color.WHITE, TEAL) {
            pending.forEach { Store.setStatus(this, day, it.med.id, it.minute, 'T') }; done()
        }, lp(top = 8))
        body.addView(pill("Snooze ${Store.snoozeMinutes(this)} min", 0xFF12413D.toInt(), Color.WHITE) {
            pending.forEach { Scheduler.snooze(this, it.med.id, it.minute) }; done()
        }, lp(top = 10))
    }

    /** Back silences the alarm but keeps the reminder in the notification shade. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { AlarmService.silence(this); finish() }
}
