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
- Developer mode is hidden: tap the About card in Settings 7 times ("Hide developer mode" re-hides it): schedules a throwaway `dev-test` medicine to ring in
  10/30/60 s. Ids starting with `Scheduler.DEV_PREFIX` are skipped by `rescheduleAll`, the Medicines list and history.
- Exact alarms: Android 14+ denies SCHEDULE_EXACT_ALARM by default, which made alarms late and blocked the
  ringing service (silent fallback). The app uses USE_EXACT_ALARM (auto-granted, API 33+) and `setAlarmClock`.
  Keep it that way. Settings pages can be missing per Android version; open them via `openSettings` (never crashes).
- Tests: `gradle testDebugUnitTest` runs Robolectric flows (`AppFlowTest`): dev-mode switch, test alarm firing,
  alarm service + full-screen intent, Taken/Snooze, add medicine, drawer. Test-only deps; the APK has no AndroidX.
  The first run downloads the Robolectric Android jar and one test can fail on that download; rerun.
- Design (1.3.0): Space Grotesk (`res/font`, 4 static weights, via `Fonts.get(weight)` and the theme's
  fontFamily), colours are theme-aware getters in `Ui.kt` (light + `values-night`), each medicine has a
  `color` (index into `HUES`). Animations: `animateIn`, `pop`, `pressable`, sliding `Segmented`, `Ring`.
- Screenshots without a phone: `gradle testDebugUnitTest --tests '*ScreenshotTest*'` writes light/dark PNGs of
  every screen to `app/build/screens`. Look at them after UI changes. (A match_parent height collapses to 0
  inside a HorizontalScrollView; that hid the Segmented highlight once.)
- Version shown in Settings → About and the drawer comes from the package (`versionLabel()`); bump both
  `versionCode` and `versionName` in `app/build.gradle.kts`.
- Appearance (Settings): theme System/Light/Dark (`Store.themeMode`, applied in `attachBaseContext` via
  `Theme.wrap`, so window and dialogs follow it) and accent colour (`ACCENTS`; `TEAL` is the accent getter).
  Changing either reopens MainActivity with a fade (`restartForTheme`). New activities must call
  `Theme.wrap` in attachBaseContext and `Theme.init` before super.onCreate.
- User prefs: do it, don't suggest it; short answers; send the APK after each change.
