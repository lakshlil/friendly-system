package com.lakshlabs.medreminder

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.provider.Settings
import org.junit.After
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
import java.net.ServerSocket
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RobustnessTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val am get() = app.getSystemService(AlarmManager::class.java)
    private var server: ServerSocket? = null
    private val routes = HashMap<String, ByteArray>()

    @Before fun setUp() { Store.setLastUpdateCheck(app, System.currentTimeMillis())  // no real network in tests
        ShadowAlarmManager.setCanScheduleExactAlarms(true) }
    @After fun tearDown() { server?.close(); Updater.urlOverride = null }

    private fun minuteAgo(m: Int) = Calendar.getInstance().apply { add(Calendar.MINUTE, -m) }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
    private fun alarmsFor(id: String) = shadowOf(am).scheduledAlarms.filter { shadowOf(it.operation).savedIntent.getStringExtra("medId") == id }

    @Test fun unansweredAlarmGoesQuietThenRingsAgain() {
        val minute = minuteAgo(0)
        Store.upsert(app, Medicine(id = "n", name = "Nag", times = listOf(minute), startDay = Store.dayStr()))
        Store.setRingSeconds(app, 30); Store.setNagMinutes(app, 5)
        val ctrl = Robolectric.buildService(AlarmService::class.java, Intent(app, AlarmService::class.java).putExtra("minute", minute)).create().startCommand(0, 1)
        val before = alarmsFor("n").size
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(31))   // ring time runs out
        val again = alarmsFor("n").minByOrNull { it.triggerAtMs }!!   // tomorrow's regular alarm is also queued
        assertTrue("a repeat alarm is queued", alarmsFor("n").size > before)
        val inMin = (again.triggerAtMs - System.currentTimeMillis()) / 60_000.0
        assertTrue("rings again in ~5 min, got $inMin", inMin in 4.5..5.5)
        assertTrue(Store.alarmLog(app).any { it.contains("ringing again in 5 min") })
        ctrl.destroy()
    }

    @Test fun stopsNaggingAfterTheWindow() {
        val minute = minuteAgo(130)   // due 2h10m ago, window is 2h
        Store.upsert(app, Medicine(id = "o", name = "Old", times = listOf(minute), startDay = Store.dayStr()))
        assertFalse(Scheduler.nag(app, "o", minute))
    }

    @Test fun alarmScreenAlsoListsEarlierUnansweredDoses() {
        val early = minuteAgo(40); val now = minuteAgo(0)
        Store.upsert(app, Medicine(id = "e", name = "Earlier", times = listOf(early), startDay = Store.dayStr()))
        Store.upsert(app, Medicine(id = "c", name = "Current", times = listOf(now), startDay = Store.dayStr()))
        assertEquals(listOf("Earlier", "Current"), Store.dueDoses(app, now).map { it.med.name })
    }

    @Test fun restartRingsForDosesMissedWhileOffAndListsThem() {
        val missed = minuteAgo(45)
        Store.upsert(app, Medicine(id = "m", name = "While off", times = listOf(missed), startDay = Store.dayStr()))
        shadowOf(am).scheduledAlarms.toList().forEach { am.cancel(it.operation) }      // a reboot clears alarms
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 7)

        BootReceiver().onReceive(app, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        val soon = alarmsFor("m").minByOrNull { it.triggerAtMs }!!
        assertTrue("rings within seconds of starting", soon.triggerAtMs - System.currentTimeMillis() in 0..15_000)
        val nm = app.getSystemService(NotificationManager::class.java)
        assertNotNull("missed-doses notification", shadowOf(nm).getNotification(7002))

        val count = shadowOf(am).scheduledAlarms.size
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))         // same boot, after unlock
        assertEquals("handled once per boot", count, shadowOf(am).scheduledAlarms.size)
    }

    @Test fun restartWorksBeforeFirstUnlock() {
        val pm = app.packageManager
        fun aware(cls: Class<*>, receiver: Boolean = true) =
            (if (receiver) pm.getReceiverInfo(ComponentName(app, cls), PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE)
             else pm.getServiceInfo(ComponentName(app, cls), PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE)).directBootAware
        assertTrue(aware(BootReceiver::class.java)); assertTrue(aware(AlarmReceiver::class.java))
        assertTrue(aware(ActionReceiver::class.java)); assertTrue(aware(AlarmService::class.java, receiver = false))
        assertTrue(pm.getActivityInfo(ComponentName(app, AlarmActivity::class.java), PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE).directBootAware)
    }

    /** Minimal HTTP server: GET <path> returns whatever is in [routes]. */
    private fun serve(): String {
        val ss = ServerSocket(0); server = ss
        Thread {
            while (!ss.isClosed) try {
                ss.accept().use { sock ->
                    val path = sock.getInputStream().bufferedReader().readLine()?.split(" ")?.getOrNull(1) ?: "/"
                    val body = routes[path]
                    val out = sock.getOutputStream()
                    out.write(("HTTP/1.1 ${if (body != null) "200 OK" else "404 Not Found"}\r\nContent-Length: ${body?.size ?: 0}\r\nConnection: close\r\n\r\n").toByteArray())
                    body?.let { out.write(it) }; out.flush()
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true }.start()
        return "http://127.0.0.1:${ss.localPort}"
    }

    @Test fun updateCheckUpToDate() {
        val base = serve()
        routes["/update.json"] = """{"versionCode": 1, "versionName": "0.1", "apk": "$base/app.apk", "sha256": ""}""".toByteArray()
        Updater.urlOverride = "$base/update.json"
        val st = Updater.checkBlocking(app, true)
        assertTrue(st, st.startsWith("Up to date"))
    }

    @Test fun updateRejectsTamperedDownload() {
        val base = serve()
        routes["/update.json"] = """{"versionCode": 99999, "versionName": "9.9", "apk": "$base/app.apk", "sha256": "00ff"}""".toByteArray()
        routes["/app.apk"] = "not the real apk".toByteArray()
        Updater.urlOverride = "$base/update.json"
        val st = Updater.checkBlocking(app, true)
        assertTrue(st, st.contains("checksum mismatch"))
    }

    @Test fun updateHandlesServerErrors() {
        Updater.urlOverride = "http://127.0.0.1:1/update.json"
        assertTrue(Updater.checkBlocking(app, true).startsWith("Couldn't check"))
    }
}
