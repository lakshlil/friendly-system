package com.lakshlabs.medreminder

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
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
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToneTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val picked = Uri.parse("content://media/external/audio/media/42")
    private val song = Uri.parse("content://com.android.providers.downloads.documents/document/7")

    @Before fun setUp() { Store.setLastUpdateCheck(app, System.currentTimeMillis())  // no real network in tests
       
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(app.contentResolver).registerInputStream(picked, ByteArrayInputStream(ByteArray(4096) { 7 }))
        shadowOf(app.contentResolver).registerInputStream(song, ByteArrayInputStream(ByteArray(8192) { 3 }))
    }

    private fun main() = Robolectric.buildActivity(MainActivity::class.java, Intent(app, MainActivity::class.java).putExtra("screen", 3)).setup()

    /** Taps Settings → Alarm sound → [choice] like a user and returns the activity and the picker it opened. */
    private fun pick(choice: Int): Pair<MainActivity, Intent> {
        val a = main().get()
        fun walk(v: android.view.View): List<android.view.View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { walk(g.getChildAt(it)) } } ?: emptyList())
        var row: android.view.View? = walk(a.window.decorView).first { it is android.widget.TextView && it.text.toString() == "Alarm sound" }
        while (row != null && !row.hasOnClickListeners()) row = row.parent as? android.view.View
        row!!.performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(dialog.listView, choice, choice.toLong())
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return a to shadowOf(a).nextStartedActivity
    }

    @Test fun pickedSoundIsCopiedAndTheAlarmPlaysIt() {
        val (a, opened) = pick(0)
        assertEquals(RingtoneManager.ACTION_RINGTONE_PICKER, opened.action)
        shadowOf(a).receiveResult(opened, Activity.RESULT_OK, Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, picked))
        assertTrue("copied into the app's own storage", Tone.hasCustom(app))
        assertEquals(4096L, Tone.file(app).length())
        assertEquals(Uri.fromFile(Tone.file(app)), Tone.candidates(app).first())

        // The alarm itself: the copied file plays, not the phone's default ring.
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(app, Uri.fromFile(Tone.file(app))), ShadowMediaPlayer.MediaInfo(3000, 0))
        Store.clearLog(app)                       // only look at what the alarm itself does
        Scheduler.scheduleTest(app, 30)
        val am = app.getSystemService(AlarmManager::class.java)
        AlarmReceiver().onReceive(app, shadowOf(shadowOf(am).scheduledAlarms.last().operation).savedIntent)
        Robolectric.buildService(AlarmService::class.java, shadowOf(app).nextStartedService).create().startCommand(0, 1)
        val log = Store.alarmLog(app).joinToString("\n")
        assertTrue(log, log.contains("Playing your sound"))
        assertFalse(log, log.contains("Can't play your sound"))
    }

    @Test fun songFromFilesWorksToo() {
        val (a, opened) = pick(1)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, opened.action)
        shadowOf(a).receiveResult(opened, Activity.RESULT_OK, Intent().setData(song))
        assertEquals(8192L, Tone.file(app).length())
    }

    @Test fun choosingDefaultRemovesTheCopy() {
        Tone.import(app, picked, "Chimes")
        assertTrue(Tone.hasCustom(app))
        val (a, opened) = pick(0)
        shadowOf(a).receiveResult(opened, Activity.RESULT_OK,
            Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI))
        assertFalse(Tone.hasCustom(app))
        assertEquals("Default alarm tone", Tone.title(app))
    }

    @Test fun settingsShowsChosenSoundName() {
        Tone.import(app, picked, "Morning Birds")
        val a = main().get()
        fun find(v: android.view.View): Boolean = (v is android.widget.TextView && v.text.toString() == "Morning Birds") ||
            (v is android.view.ViewGroup && (0 until v.childCount).any { find(v.getChildAt(it)) })
        assertTrue(find(a.window.decorView))
    }
}
