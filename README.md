# Sunrise Alarm

A sunrise wake-up light and alarm for an old phone on a bedside stand.

- Sunrise glow on the screen before the alarm
- Wake to Spotify, internet radio, another music app or gentle chimes
- Quick one-off alarms (in 5, 10, 20 or 30 minutes, or at a time you pick)
- Morning screen with weather (Open-Meteo, including the chance of rain through the day), a link to the BOM forecast, daylight times and Todoist tasks

Open https://graemeblick-lang.github.io/sunrise-alarm/ in Chrome on Android, then ⋮ → Add to home screen → Install.

## Android app

The Android app is a native app (Jetpack Compose, Material 3) and nothing loads from the website. It opens itself at sunrise (even over the lock screen), keeps the screen on, controls brightness, plays radio in the background and rings a backup alarm.

GitHub builds it automatically. Download `SunriseAlarm.apk` from the latest Release on your phone and open it to install. After that, the app tells you on its main screen when a newer version is ready (it checks GitHub Releases at most twice a day, never near an alarm).

The GitHub Pages site is only needed if you want to use Sunrise in a browser instead.

### Spotify

In the app: **Wake-up sound → Spotify → Connect Spotify** (once), then in Spotify open a playlist, album or song, tap **Share** and pick **Sunrise**. That's it. If the music can't start, Sunrise presses play in Spotify (what you last played), and if nothing plays, the chimes take over.

Spotify only lets an app start a chosen playlist if it's registered as a Spotify "developer app". That's a one-time job for whoever builds the app:

1. At [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard), create an app (or open the one you already have) with:
   - Redirect URI: `au.sunrise.alarm://spotify-callback`
   - APIs used: **Android**
   - Android package: `au.sunrise.alarm`, SHA-1 fingerprint: `A0:00:4D:EE:71:42:49:C6:EB:0C:CF:24:2D:20:5D:3C:1B:94:D0:4A` (the app's release signing key)
2. Copy its **Client ID**. In this GitHub repo: **Settings → Secrets and variables → Actions → Variables → New repository variable**, name `SPOTIFY_CLIENT_ID`.
3. The next build has it built in. (Without it, Spotify still works but plays what you last played.)

### Other music apps (YouTube Music, Podcast Addict…)

Choose **Wake-up sound → Apps** and pick the app. Sunrise sends a play press only to that app and connects to its media service (the way Android Auto starts an app), so it never starts a different app. If the chosen app won't start, the chimes play.

Some apps only accept connections from apps they know. In testing, YouTube Music wouldn't start this way (chimes played instead); Spotify and Podcast Addict did. (Sunrise doesn't ask for Notification access to watch other apps: Google Play Protect blocks installing apps from outside the Play Store that ask for it.)

### How the alarm stays reliable

- **The app keeps its own schedule.** The next 8 alarms are kept set even if the app is closed, the phone restarts, the app updates or the clock/time zone changes.
- **Backup alarm.** A minute after each alarm time, Android checks the app actually started ringing. If it didn't (the app was stopped, or battery saving froze it), the phone rings with its own alarm sound, fading up, with a Stop button on the lock screen.
- **No internet needed at alarm time.** The alarm screens are built into the app; only the radio, Spotify and the weather need the internet, and the chimes take over if they can't play.
- **Crashes don't block alarms.** If something goes wrong, the details are kept and shown on a normal launch later, never in place of the sunrise.

For best results, tap **Fix** next to anything the Settings → Status card flags (notifications, full-screen notifications, alarms & reminders, battery saving).

## Development

- The Android app is fully native: there is no web page inside it. `index.html` is only the browser version, and the source the native colours and built-in stations are checked against.
- The app opens on a native home screen (`HomeScreen.kt`, in `SettingsActivity.kt`, Jetpack Compose with Material 3). The night, alarm and morning screens are native too (`AlarmFlowActivity.kt`, `AlarmFlowViews.kt`), with the wake-up sound in `WakeSound.kt`, the sunrise maths in `Sunrise.kt` (colours generated from the page by `tools/gen-theme-data.js`, checked by `tests/themes.test.js`) and the morning data in `MorningData.kt`. Android computes and keeps the alarm schedule itself (`AlarmSettings.plan`, checked against the page's `alarmPlan`). The home screen has the alarm card (time, days, skip, pause) and quick one-off alarms; the settings follow the night: Night and Sunrise (`LookScreen.kt`), Wake-up sound (`SoundScreen.kt`, `StationsScreen.kt`) and Morning (`MorningScreen.kt`), with the detailed Schedule in `AlarmScreen.kt`, with shared parts in `SettingsUi.kt` and `Themes.kt`.  The native Test sound (`SoundTester.kt`, `ChimePlayer.kt`) plays every source natively. `tests/stations.test.js` checks the native copy of the built-in stations matches the page. Settings are kept in one store (`SettingsStore.kt`, the same JSON format the page uses). The native next-alarm logic (`AlarmSettings.kt`) has unit tests matching the JavaScript ones: `gradle testReleaseUnitTest`.
- Fonts are bundled from Fontsource (SIL Open Font License, see `fonts/`).
- The alarm schedule logic sits between the `schedule:start` and `schedule:end` markers and is tested by `tests/schedule.test.js`:
  `TZ=Australia/Melbourne node tests/schedule.test.js`
- GitHub Actions runs the tests and builds the APK on every push to `main` (published as the `latest` Release) and on pull requests. A pull request's APK goes to the **Test build** pre-release on the Releases page (one tap to install on the phone); it never replaces the normal release.
- Signing: set the `KEYSTORE_PASSWORD` secret. Optionally also set `KEYSTORE_BASE64` (`base64 -w0 android/release.jks`) and then `android/release.jks` can be deleted from the repo.
