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

    @Before fun setUp() { Store.setLastUpdateCheck(app, System.currentTimeMillis())  // no real network in tests
        ShadowAlarmManager.setCanScheduleExactAlarms(true) }

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
        assertNull("developer mode is hidden by default", root(main).find("Schedule test alarm"))
        repeat(6) { root(main).find("Med Reminder")!!.tap() } // About card
        assertFalse(Store.devMode(main))
        root(main).find("Med Reminder")!!.tap()               // 7th tap unlocks
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

    @Test fun devModeCanBeHiddenAgain() {
        Store.setDevMode(app, true)
        val main = Robolectric.buildActivity(MainActivity::class.java, Intent(app, MainActivity::class.java).putExtra("screen", 3)).setup().get()
        root(main).find("Hide developer mode")!!.tap()
        assertFalse(Store.devMode(main))
        assertNull(root(main).find("Schedule test alarm"))
    }

    @Test fun themeChoiceIsSavedAndApplied() {
        val main = Robolectric.buildActivity(MainActivity::class.java, Intent(app, MainActivity::class.java).putExtra("screen", 3)).setup().get()
        root(main).find("Dark")!!.tap()
        assertEquals(2, Store.themeMode(app))
        val next = shadowOf(main).nextStartedActivity
        assertEquals("reopens on Settings", 3, next.getIntExtra("screen", -1))
        assertTrue(main.isFinishing)
        val dark = Robolectric.buildActivity(MainActivity::class.java, next).setup().get()
        assertTrue("forced dark even though the phone is light", Theme.dark)
        assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_YES,
            dark.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)

        root(dark).let { r -> generateSequence(listOf<View>(r)) { l -> l.flatMap { v -> (v as? ViewGroup)?.let { g -> (0 until g.childCount).map(g::getChildAt) } ?: emptyList() }.takeIf { it.isNotEmpty() } }
            .flatten().first { it.contentDescription == "Rose" } }.performClick(); idle()
        assertEquals(2, Store.accent(app))
        Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals(ACCENTS[2].dark, TEAL)
    }

    @Test fun manifestRequestsAutoGrantedExactAlarmPermission() {
        val perms = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions!!.toList()
        assertTrue("USE_EXACT_ALARM is what makes alarms exact by default on Android 13+", "android.permission.USE_EXACT_ALARM" in perms)
        assertTrue("android.permission.USE_FULL_SCREEN_INTENT" in perms)
        assertTrue("Android 14 throws when scheduling a network job without it (crashed 1.4.1 on launch)",
            "android.permission.ACCESS_NETWORK_STATE" in perms)
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

    @Test fun regularMedicineRingsAtItsTimeEndToEnd() {
        val at = Calendar.getInstance().apply { add(Calendar.MINUTE, 2) }
        val minute = at.get(Calendar.HOUR_OF_DAY) * 60 + at.get(Calendar.MINUTE)
        Store.upsert(app, Medicine(id = "real", name = "Metformin", strength = "500 mg", times = listOf(minute),
            startDay = Store.dayStr(), color = 2))
        val alarm = shadowOf(am).scheduledAlarms.single { shadowOf(it.operation).savedIntent.getStringExtra("medId") == "real" }
        assertNotNull("alarm-clock alarm", alarm.alarmClockInfo)
        val due = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, minute / 60); set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); if (timeInMillis < System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1) }
        assertEquals(due.timeInMillis, alarm.triggerAtMs)

        AlarmReceiver().onReceive(app, shadowOf(alarm.operation).savedIntent)
        val started = shadowOf(app).nextStartedService
        assertEquals(AlarmService::class.java.name, started.component!!.className)
        val service = Robolectric.buildService(AlarmService::class.java, started).create().startCommand(0, 1).get()
        assertNotNull(shadowOf(service).lastForegroundNotification.fullScreenIntent)
        val screen = Robolectric.buildActivity(AlarmActivity::class.java,
            Intent(app, AlarmActivity::class.java).putExtra("minute", minute)).setup().get()
        assertNotNull(root(screen).find("Metformin"))
        root(screen).find("Taken")!!.tap()
        assertEquals('T', Store.status(app, Store.dayStr(), "real", minute))
        // Tomorrow's alarm is queued again.
        assertTrue(shadowOf(am).scheduledAlarms.any { shadowOf(it.operation).savedIntent.getStringExtra("medId") == "real" })
    }

    @Test fun rebootRequeuesAlarms() {
        Store.upsert(app, Medicine(id = "r", name = "X", times = listOf(8 * 60), startDay = Store.dayStr()))
        shadowOf(am).scheduledAlarms.toList().forEach { am.cancel(it.operation) }
        assertTrue(shadowOf(am).scheduledAlarms.isEmpty())
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, shadowOf(am).scheduledAlarms.size)
    }

    private fun fireTestAlarm(): Int {
        Scheduler.scheduleTest(app, 30)
        val fired = shadowOf(shadowOf(am).scheduledAlarms.last().operation).savedIntent
        AlarmReceiver().onReceive(app, fired)
        val started = shadowOf(app).nextStartedService
        Robolectric.buildService(AlarmService::class.java, started).create().startCommand(0, 1)
        return fired.getIntExtra("minute", -1)
    }

    @Test fun neverSilentWhenNoToneCanBePlayed() {
        fireTestAlarm()
        val log = Store.alarmLog(app).joinToString("\n")
        assertTrue(log, log.contains("Alarm fired: Test alarm"))
        assertTrue(log, log.contains("Can't play"))
        assertTrue("falls back to beeps rather than silence:\n$log", log.contains("using beeps"))
    }

    @Test fun usesFirstPlayableTone() {
        val uri = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
        org.robolectric.shadows.ShadowMediaPlayer.addMediaInfo(
            org.robolectric.shadows.util.DataSource.toDataSource(app, uri),
            org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(3000, 0))
        fireTestAlarm()
        val log = Store.alarmLog(app).joinToString("\n")
        assertTrue(log, log.contains("Playing $uri"))
        assertFalse(log, log.contains("using beeps"))
    }

    @Test fun backupAlarmNotificationRepeatsItsSound() {
        Scheduler.scheduleTest(app, 30)
        val minute = shadowOf(shadowOf(am).scheduledAlarms.last().operation).savedIntent.getIntExtra("minute", -1)
        Notify.loudAlarm(app, minute, Store.pendingAt(app, minute))
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        val n = shadowOf(nm).getNotification(AlarmService.NOTIF_ID)
        assertNotNull(n)
        assertTrue("insistent = sound repeats", n.flags and android.app.Notification.FLAG_INSISTENT != 0)
        assertNotNull(n.fullScreenIntent)
        val ch = nm.getNotificationChannel("alarm_loud")
        assertEquals(android.media.AudioAttributes.USAGE_ALARM, ch.audioAttributes.usage)
        assertNotNull(ch.sound)
    }

    @Test fun crashIsRecordedAndShownOnNextLaunch() {
        Store.setLastCrash(app, "IllegalStateException: boom\n  at Foo.bar:1")
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        root(main).find("The app crashed last time. Screenshot this for Claude, then tap to dismiss.")!!.tap()
        assertEquals("", Store.lastCrash(app))
    }
}
