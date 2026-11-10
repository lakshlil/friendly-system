package com.lakshlabs.medreminder

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Renders screens to PNG (app/build/screens) so the design can be checked without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class ScreenshotTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun seed() {
        val start = Store.dayStr(Store.daysFromNow(-20))
        val meds = listOf(
            Medicine(id = "a", name = "Metformin", strength = "500 mg", times = listOf(8 * 60, 20 * 60), food = 2, startDay = start, color = 1,
                stock = 24.0),
            Medicine(id = "b", name = "Vitamin D3", strength = "60000 IU", category = "Capsule", times = listOf(9 * 60), startDay = start, color = 3),
            Medicine(id = "c", name = "Amlodipine", strength = "5 mg", times = listOf(14 * 60), startDay = start, color = 2, stock = 4.0),
            Medicine(id = "d", name = "Cough syrup", strength = "100 mg/5 mL", category = "Liquid", amount = 5.0, times = listOf(22 * 60), food = 2, startDay = start, color = 4),
        )
        meds.forEach { Store.upsert(app, it) }
        for (i in 1..13) {
            val day = Store.dayStr(Store.daysFromNow(-i))
            meds.forEach { m -> m.times.forEachIndexed { k, t -> if ((i + k + m.id[0].code) % 5 != 0) Store.setStatus(app, day, m.id, t, 'T') } }
        }
        Store.setStatus(app, Store.dayStr(), "a", 8 * 60, 'T')
        Store.setStatus(app, Store.dayStr(), "b", 9 * 60, 'T')
    }

    private fun idle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3)) }

    private fun View.find(t: String): TextView? {
        if (this is TextView && text.toString() == t) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).find(t)?.let { return it }
        return null
    }
    private fun View.firstScroll(): ScrollView? {
        if (this is ScrollView) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).firstScroll()?.let { return it }
        return null
    }
    private fun View.tap() { var v: View? = this; while (v != null && !v.hasOnClickListeners()) v = v.parent as? View; v!!.performClick(); }

    private fun save(v: View, name: String) {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        File(System.getProperty("user.dir"), "build/screens").apply { mkdirs() }
            .resolve("$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shoot(a: Activity, name: String, full: Boolean = true) {
        idle(); save(a.window.decorView, name)
        if (full) a.window.decorView.firstScroll()?.getChildAt(0)?.let { save(it, "${name}_full") }
    }

    private fun all(prefix: String) {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        shoot(main, "${prefix}today")
        for (s in listOf("Medicines", "History", "Settings")) {
            (main.window.decorView as ViewGroup).find("${s}")?.let { }
            main.window.decorView.let { r ->
                // open drawer and pick the screen, the way a user would
                r.find("Today")  // title
            }
            val row = main.window.decorView.let { r -> generateSequence(listOf(r)) { l -> l.flatMap { v -> (v as? ViewGroup)?.let { g -> (0 until g.childCount).map(g::getChildAt) } ?: emptyList() }.takeIf { it.isNotEmpty() } }.flatten() }
                .filterIsInstance<TextView>().last { it.text.toString() == s }
            if (s == "Medicines") { main.window.decorView.firstScroll(); }
            row.tap(); shoot(main, "${prefix}${s.lowercase()}")
        }
        // drawer open
        main.window.decorView.let { r -> generateSequence(listOf(r)) { l -> l.flatMap { v -> (v as? ViewGroup)?.let { g -> (0 until g.childCount).map(g::getChildAt) } ?: emptyList() }.takeIf { it.isNotEmpty() } }.flatten() }
            .first { it is android.widget.ImageView && it.hasOnClickListeners() }.performClick()
        shoot(main, "${prefix}drawer", full = false)

        val add = Robolectric.buildActivity(AddActivity::class.java).setup().visible().get()
        shoot(add, "${prefix}add")
        val edit = Robolectric.buildActivity(AddActivity::class.java, Intent(app, AddActivity::class.java).putExtra("id", "a")).setup().visible().get()
        shoot(edit, "${prefix}edit")

        Store.setStatus(app, Store.dayStr(), "a", 8 * 60, null)
        Store.upsert(app, Store.find(app, "b")!!.copy(times = listOf(8 * 60)))
        Store.setStatus(app, Store.dayStr(), "b", 8 * 60, null)
        val alarm = Robolectric.buildActivity(AlarmActivity::class.java, Intent(app, AlarmActivity::class.java).putExtra("minute", 8 * 60)).setup().visible().get()
        shoot(alarm, "${prefix}alarm", full = false)
    }

    @Test fun light() = all("light_")

    /** Phone in light mode, app set to Dark with the Rose accent. */
    @Test fun forcedDarkRose() { Store.setThemeMode(app, 2); Store.setAccent(app, 2); Store.setDevMode(app, true); all("rose_") }

    @Config(qualifiers = "w393dp-h852dp-night-xhdpi")
    @Test fun dark() = all("dark_")
}
