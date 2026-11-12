package com.lakshlabs.medreminder

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
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
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var server: ServerSocket? = null
    private val stored = ConcurrentHashMap<String, String>()

    @Before fun setUp() {
        Store.setLastUpdateCheck(app, System.currentTimeMillis())
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        Sync.offline = false
    }
    @After fun tearDown() { server?.close(); Sync.urlOverride = null; Sync.offline = true }

    /** Stand-in for the Vercel function: GET/PUT /api/sync?id=… kept in memory. */
    private fun fakeServer(): String {
        val ss = ServerSocket(0); server = ss
        Thread {
            while (!ss.isClosed) try {
                ss.accept().use { sock ->
                    val inp = sock.getInputStream().bufferedReader()
                    val (method, path) = inp.readLine().split(" ").let { it[0] to it[1] }
                    var len = 0
                    while (true) { val h = inp.readLine() ?: break; if (h.isEmpty()) break
                        if (h.lowercase().startsWith("content-length:")) len = h.substringAfter(":").trim().toInt() }
                    val body = if (len > 0) CharArray(len).also { var r = 0; while (r < len) r += inp.read(it, r, len - r) }.concatToString() else ""
                    val id = path.substringAfter("id=")
                    val (code, out) = when (method) {
                        "GET" -> stored[id]?.let { 200 to it } ?: (404 to """{"error":"none"}""")
                        "PUT" -> { stored[id] = body; 200 to """{"ok":true}""" }
                        else -> 405 to "{}"
                    }
                    val bytes = out.toByteArray()
                    sock.getOutputStream().apply {
                        write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true }.start()
        return "http://127.0.0.1:${ss.localPort}"
    }

    private fun wipePhone() {
        app.createDeviceProtectedStorageContext().getSharedPreferences("meds", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSystemService(AlarmManager::class.java).let { am -> shadowOf(am).scheduledAlarms.toList().forEach { am.cancel(it.operation) } }
    }

    @Test fun encryptionRoundTripAndWrongCodeFails() {
        val code = Sync.normalize("abcd efgh jkmn pqrs tuvw xyz2 34")!!
        val box = Sync.encrypt(code, "Metformin 500 mg")
        assertFalse("ciphertext doesn't contain the data", box.toString().contains("Metformin"))
        assertEquals("Metformin 500 mg", Sync.decrypt(code, box))
        val other = Sync.normalize("ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZ")!!
        assertThrows(Exception::class.java) { Sync.decrypt(other, box) }
    }

    @Test fun codeIsReadableAndStable() {
        val c = Sync.code(app)
        assertTrue(c, Regex("^([A-Z2-9]{4}-){6}[A-Z2-9]{2}$").matches(c))
        assertEquals(c, Sync.code(app))
        assertEquals(c, Sync.normalize(c.lowercase().replace("-", " ")))
        assertNull(Sync.normalize("too short"))
    }

    @Test fun lostPhoneGetsEverythingBack() {
        Sync.urlOverride = fakeServer()
        Store.upsert(app, Medicine(id = "a", name = "Metformin", strength = "500 mg", times = listOf(8 * 60, 20 * 60), startDay = Store.dayStr()))
        Store.setStatus(app, Store.dayStr(), "a", 8 * 60, 'T')
        val code = Sync.code(app)
        assertEquals("Synced", Sync.syncBlocking(app))
        assertFalse("server only holds ciphertext", stored.values.single().contains("Metformin"))

        wipePhone()                                  // new phone
        assertTrue(Store.medicines(app).isEmpty())
        Store.setSyncCode(app, code)                 // user types the code
        assertEquals("Synced", Sync.syncBlocking(app, force = true))
        assertEquals("Metformin", Store.medicines(app).single().name)
        assertEquals('T', Store.status(app, Store.dayStr(), "a", 8 * 60))
        assertTrue("alarms rescheduled after restore", shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.isNotEmpty())
    }

    @Test fun twoPhonesMerge() {
        Sync.urlOverride = fakeServer()
        val code = Sync.code(app)
        // Phone A
        Store.upsert(app, Medicine(id = "a", name = "A-med", times = listOf(480), startDay = Store.dayStr()))
        Store.upsert(app, Medicine(id = "gone", name = "Old med", times = listOf(600), startDay = Store.dayStr()))
        Sync.syncBlocking(app)
        val phoneA = Sync.snapshot(app)
        // Phone B starts empty, joins with the code, adds its own medicine and deletes one
        wipePhone(); Store.setSyncCode(app, code)
        Sync.syncBlocking(app, force = true)
        Thread.sleep(5)
        Store.upsert(app, Medicine(id = "b", name = "B-med", times = listOf(540), startDay = Store.dayStr()))
        Store.delete(app, "gone")
        Store.setStatus(app, Store.dayStr(), "a", 480, 'T')
        Sync.syncBlocking(app)
        // Back on phone A (its own older data) after syncing
        wipePhone(); Store.setSyncCode(app, code)
        Sync.merge(app, phoneA)                      // A's local state
        Sync.syncBlocking(app, force = true)
        assertEquals(listOf("A-med", "B-med"), Store.medicines(app).map { it.name }.sorted())
        assertEquals("taken on phone B shows on A", 'T', Store.status(app, Store.dayStr(), "a", 480))
    }

    @Test fun newerEditWinsOlderLoses() {
        Store.upsert(app, Medicine(id = "x", name = "Local new", times = listOf(480), startDay = Store.dayStr()))
        val older = JSONObject().put("meds", org.json.JSONArray().put(Medicine(id = "x", name = "Remote old", times = listOf(480), startDay = Store.dayStr()).toJson()))
            .put("medUpdated", JSONObject().put("x", 1L))
        Sync.merge(app, older)
        assertEquals("Local new", Store.find(app, "x")!!.name)
        val newer = JSONObject().put("meds", org.json.JSONArray().put(Medicine(id = "x", name = "Remote new", times = listOf(480), startDay = Store.dayStr()).toJson()))
            .put("medUpdated", JSONObject().put("x", System.currentTimeMillis() + 60_000))
        assertTrue(Sync.merge(app, newer))
        assertEquals("Remote new", Store.find(app, "x")!!.name)
    }

    @Test fun wrongCodeNeverOverwritesTheCloudCopy() {
        Sync.urlOverride = fakeServer()
        Store.upsert(app, Medicine(id = "a", name = "Mine", times = listOf(480), startDay = Store.dayStr()))
        Sync.syncBlocking(app)
        val id = Sync.idFor(Sync.code(app))
        val before = stored[id]
        stored[id] = Sync.encrypt(Sync.normalize("ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZ")!!, "{}").toString()   // can't be opened
        val st = Sync.syncBlocking(app)
        assertTrue(st, st.startsWith("Couldn't sync"))
        assertNotEquals("not replaced by this phone's copy", before, stored[id])
    }

    /** Runs only with LIVE_SYNC=1: talks to the real Vercel deployment. */
    @Test fun liveServerRoundTrip() {
        assumeTrue(System.getenv("LIVE_SYNC") == "1")
        Store.upsert(app, Medicine(id = "live", name = "Live check", times = listOf(480), startDay = Store.dayStr()))
        val code = Sync.code(app)
        assertEquals("Synced", Sync.syncBlocking(app))
        wipePhone(); Store.setSyncCode(app, code)
        assertEquals("Synced", Sync.syncBlocking(app, force = true))
        assertEquals("Live check", Store.medicines(app).single().name)
    }

    @Test fun missedDoseHasOneTapButtonsOnHome() {
        Sync.offline = true
        val past = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, -2) }.let { it.get(Calendar.HOUR_OF_DAY) * 60 }
        Store.upsert(app, Medicine(id = "m", name = "Missed med", times = listOf(past), startDay = Store.dayStr()))
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        fun clickable(start: View): View { var v: View = start; while (!v.hasOnClickListeners()) v = v.parent as View; return v }
        fun tap(label: String) { clickable(all(a.window.decorView).first { it is TextView && it.text.toString() == label }).performClick()
            shadowOf(Looper.getMainLooper()).idle() }
        assertTrue(all(a.window.decorView).any { it is TextView && it.text.toString() == "1 missed today" })
        tap("Skip")
        assertEquals('S', Store.status(app, Store.dayStr(), "m", past))
        tap("Taken")                                   // a skipped dose can still be marked taken in one tap
        assertEquals('T', Store.status(app, Store.dayStr(), "m", past))
        Store.setStatus(app, Store.dayStr(), "m", past, null)
        a.recreate(); shadowOf(Looper.getMainLooper()).idle()
        val b = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        clickable(all(b.window.decorView).first { it is TextView && it.text.toString() == "Mark all 1 as taken" }).performClick()
        assertEquals('T', Store.status(app, Store.dayStr(), "m", past))
    }
}
