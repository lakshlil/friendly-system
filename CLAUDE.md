# CLAUDE.md — Med Reminder

Native Android medicine-reminder app. Plain Kotlin, framework APIs only (no Flutter, no AndroidX),
package `com.lakshlabs.medreminder`. The user said Flutter is too bulky for it.
Not related to Formzy (it was built by mistake inside that repo and moved out).

- Build: `gradle assembleDebug` (needs an Android SDK; set `sdk.dir` in the gitignored `local.properties`).
  A cloud box has no emulator, so nothing here has been run on a device by Claude.
- Drug lookup: free NIH RxNorm API (`Drug.kt`): names cached in `filesDir`, strengths from `drugs.json?name=`,
  small offline list as fallback. RxNorm uses US names, so `Drug.ALIASES` maps e.g. paracetamol to acetaminophen.
- Reminders are a full-screen alarm by default: `AlarmReceiver` starts `AlarmService` (foreground, loops the alarm
  tone + vibration, 2 min max), which posts a full-screen-intent notification for `AlarmActivity`.
  Settings can switch to a plain notification. Android 14 may need the "full-screen intent" permission.
- Developer mode (Settings → Developer mode → On): schedules a throwaway `dev-test` medicine to ring in
  10/30/60 s. Ids starting with `Scheduler.DEV_PREFIX` are skipped by `rescheduleAll`, the Medicines list and history.
- User prefs: do it, don't suggest it; short answers; send the APK after each change.
