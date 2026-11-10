package com.lakshlabs.medreminder

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowAlertDialog
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppFlowTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val am get() = app.getSystemService(AlarmManager::class.java)

    @Before fun setUp() { ShadowAlarmManager.setCanScheduleExactAlarms(true) }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun View.find(t: String): TextView? {
        if (this is TextView && text.toString() == t) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).find(t)?.let { return it }
        return null
    }

    /** Taps a label the way a finger would: the nearest view that has a click listener handles it. */
    private fun View.tap() {
        var v: View? = this
        while (v != null && !v.hasOnClickListeners()) v = v.parent as? View
        assertNotNull("nothing clickable around \"${(this as? TextView)?.text}\"", v)
        assertTrue(v!!.performClick()); idle()
    }

    private fun root(a: android.app.Activity) = a.window.decorView

    @Test fun devModeSwitchShowsTestAlarmAndSchedulesIt30sOut() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        root(main).find("Settings")!!.tap()                 // drawer row
        assertNull(root(main).find("Schedule test alarm"))
        root(main).find("On")!!.tap()                        // Developer mode: On
        assertTrue(Store.devMode(main))
        val before = System.currentTimeMillis()
        root(main).find("Schedule test alarm")!!.tap()

        val alarm = shadowOf(am).scheduledAlarms.last()
        assertNotNull("must be an alarm-clock alarm so it rings on time", alarm.alarmClockInfo)
        assertTrue(alarm.triggerAtMs in (before + 29_000)..(before + 32_000))
    }

    @Test fun firedAlarmRingsServiceShowsFullScreenAndTakenStopsIt() {
        Scheduler.scheduleTest(app, 30)
        val alarm = shadowOf(am).scheduledAlarms.last()
        val fired = shadowOf(alarm.operation).savedIntent
        AlarmReceiver().onReceive(app, fired)

        val started = shadowOf(app).nextStartedService
        assertEquals(AlarmService::class.java.name, started.component!!.className)

        val service = Robolectric.buildService(AlarmService::class.java, started).create().startCommand(0, 1).get()
        val n = shadowOf(service).lastForegroundNotification
        assertNotNull("alarm notification must be posted", n)
        assertNotNull("must open the alarm screen over the lock screen", n.fullScreenIntent)
        assertEquals(AlarmActivity::class.java.name, shadowOf(app).nextStartedActivity.component!!.className)

        val minute = fired.getIntExtra("minute", -1)
        val screen = Robolectric.buildActivity(AlarmActivity::class.java,
            Intent(app, AlarmActivity::class.java).putExtra("minute", minute)).setup().get()
        assertNotNull(root(screen).find("Test alarm"))
        root(screen).find("Taken")!!.tap()
        assertEquals('T', Store.status(app, Store.dayStr(), "dev-test", minute))
        assertTrue(screen.isFinishing)
    }

    @Test fun snoozeQueuesAnotherAlarm() {
        Scheduler.scheduleTest(app, 30)
        val fired = shadowOf(shadowOf(am).scheduledAlarms.last().operation).savedIntent
        val minute = fired.getIntExtra("minute", -1)
        val screen = Robolectric.buildActivity(AlarmActivity::class.java,
            Intent(app, AlarmActivity::class.java).putExtra("minute", minute)).setup().get()
        val count = shadowOf(am).scheduledAlarms.size
        root(screen).find("Snooze 10 min")!!.tap()
        assertEquals(count + 1, shadowOf(am).scheduledAlarms.size)
        assertTrue(screen.isFinishing)
    }

    @Test fun addMedicineThenMarkTakenFromToday() {
        val add = Robolectric.buildActivity(AddActivity::class.java).setup().get()
        val name = root(add).let { r -> generateSequence(listOf(r)) { l -> l.flatMap { v -> (v as? ViewGroup)?.let { g -> (0 until g.childCount).map(g::getChildAt) } ?: emptyList() }.takeIf { it.isNotEmpty() } }.flatten().first { it is EditText } } as EditText
        name.setText("Vitamin"); idle()
        root(add).find("Save")!!.tap()
        assertEquals("Vitamin", Store.medicines(app).single().name)
        assertTrue(add.isFinishing)
        assertTrue("a daily alarm is scheduled", shadowOf(am).scheduledAlarms.isNotEmpty())

        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        root(main).find("Vitamin")!!.tap()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("tapping a dose opens the taken/skip dialog", dialog)
        dialog.listView.performItemClick(dialog.listView, 0, 0); idle()
        assertEquals('T', Store.status(app, Store.dayStr(), Store.medicines(app).single().id, 480))
    }

    @Test fun drawerOpensFromMenuButton() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val menu = (root(main) as ViewGroup).let { r ->
            generateSequence(listOf<View>(r)) { l -> l.flatMap { v -> (v as? ViewGroup)?.let { g -> (0 until g.childCount).map(g::getChildAt) } ?: emptyList() }.takeIf { it.isNotEmpty() } }
                .flatten().first { it is android.widget.ImageView && it.hasOnClickListeners() }
        }
        menu.performClick(); idle()
        root(main).find("History")!!.tap()
        assertEquals("History", (root(main).find("Last 7 days") != null).let { if (it) "History" else "?" })
    }

    @Test fun manifestRequestsAutoGrantedExactAlarmPermission() {
        val perms = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions!!.toList()
        assertTrue("USE_EXACT_ALARM is what makes alarms exact by default on Android 13+", "android.permission.USE_EXACT_ALARM" in perms)
        assertTrue("android.permission.USE_FULL_SCREEN_INTENT" in perms)
    }

    @Test fun withoutExactAlarmsStillRemindsAndDoesNotCrash() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        Scheduler.scheduleTest(app, 30)
        val alarm = shadowOf(am).scheduledAlarms.last()
        assertNull(alarm.alarmClockInfo)
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNotNull("Today shows the banner to fix it", root(main).find("Allow exact alarms so reminders arrive on time. Tap here."))
        root(main).find("Allow exact alarms so reminders arrive on time. Tap here.")!!.tap()   // must not crash
    }
}
