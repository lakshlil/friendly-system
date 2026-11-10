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
- "Notification only, no sound/screen" (user report, 1.3.1): the old service played one tone URI and stayed
  silent if it failed. `AlarmService.startSound` now tries the chosen tone, actual default alarm/ringtone,
  the first system alarm/ringtone, then ToneGenerator beeps; raises a 0 alarm volume while ringing. If the
  service can't start, `Notify.loudAlarm` rings from an insistent alarm-usage notification channel. The alarm
  screen opens over other apps only with SYSTEM_ALERT_WINDOW ("Display over other apps", Settings → Permissions).
  Every step goes to `Store.log`, shown as "Alarm log" in developer mode: ask the user for it when alarms misbehave.
- Nagging (1.4.0): an alarm rings `ringSeconds`, goes quiet, and every unanswered dose rings again every
  `nagMinutes` until `nagHours` after it was due (`Scheduler.nag`, snooze slot). The alarm screen lists all due
  doses (`Store.dueDoses`), not just the alarm's minute.
- Restarts: data is in device-protected storage (migrated once in `Store.prefs`), alarm components are
  `directBootAware`, and `BootReceiver` handles LOCKED_BOOT_COMPLETED, so alarms work before the first unlock.
  It rings for doses missed while off (inside the window) and posts a "not taken yet" summary, once per boot.
  A phone that is switched OFF can't ring at all; Android gives apps no power-on alarm.
- Updates: `Updater.kt` reads `update.json` from `BuildConfig.UPDATE_URL` (`updateUrl` in gradle.properties:
  raw.githubusercontent.com/lakshlil/friendly-system/medreminder-dist/update.json), downloads, checks SHA-256 and
  installs through PackageInstaller (silent from Android 12 after the first self-install). To release: bump
  versionCode/versionName, run `scripts/publish.sh "notes"` (tests, builds, pushes the dist branch). The user wants
  ZERO effort on their side: publish every change that should reach the phone.
  Code lives on branch `medreminder` of lakshlil/friendly-system (the user's spare public repo; its own files on
  the default branch are not ours, leave them). The signing key MUST be the one installed builds use (cert
  SHA-256 775a293f…, the debug keystore of the first cloud box; the user has the file). A fresh box makes a new
  debug key, so get the keystore before publishing or updates fail with a signature mismatch.
  raw.githubusercontent.com caches for ~5 min (404s too: requesting a file right after creating it cached a
  404 for ~2 min). Phones pick up a release within that plus their 6-hour check. Verify a publish with
  curl on update.json + the APK's sha256 and `apksigner verify --print-certs` (cert must be 775a293f…).
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
