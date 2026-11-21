# Sunrise Alarm

A sunrise wake-up light and alarm for an old phone on a bedside stand.

- Sunrise glow on the screen before the alarm
- Wake to Spotify, internet radio, another music app or gentle chimes
- Quick one-off alarms (in 5, 10, 20 or 30 minutes, or at a time you pick)
- Skips your state's public holidays, if you like
- The night clock can start by itself when the phone goes on the charger; sleep timer for music; bedtime reminder
- Home-screen widget and Quick Settings tile
- Checks at Goodnight that tomorrow's sound will work (Spotify, the station, the music app, volume, battery)
- Rehearse the alarm: a real 2½-minute test with the phone locked, with a report of what worked
- Wake-up log, with a warning if the backup alarm ever had to ring; settings backup to a file
- Morning screen with weather (Open-Meteo, including the chance of rain through the day), a button for the BOM Weather app, daylight times, today's calendar and Todoist tasks

## Android app

Sunrise is a native Android app (Jetpack Compose, Material 3). (There used to be a browser version too; it has been retired.) It opens itself at sunrise (even over the lock screen), keeps the screen on, controls brightness, plays radio in the background and rings a backup alarm.

GitHub builds it automatically. Download `SunriseAlarm.apk` from the latest Release on your phone and open it to install. After that, the app tells you on its main screen when a newer version is ready (it checks GitHub Releases at most twice a day, never near an alarm).

The GitHub Pages site is only needed if you want to use Sunrise in a browser instead.

### Spotify

In the app: **Wake-up sound → Spotify → Connect Spotify** (once: Spotify opens and asks you to agree), then in Spotify open a playlist, album or song, tap **Share** and pick **Sunrise**. That's it. If the music can't start, Sunrise presses play in Spotify (what you last played), and if nothing plays, the chimes take over.

Spotify only lets an app start a chosen playlist if it's registered as a Spotify "developer app". That's a one-time job for whoever builds the app:

1. At [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard), create an app (or open the one you already have) with:
   - Redirect URI: `au.sunrise.alarm://spotify-callback`
   - APIs used: **Android**
   - Android package: `au.sunrise.alarm`, SHA-1 fingerprint: `A0:00:4D:EE:71:42:49:C6:EB:0C:CF:24:2D:20:5D:3C:1B:94:D0:4A` (the app's release signing key)
   - **User Management**: add your own name and the email of the Spotify account on your phone (apps in development mode only work for the accounts listed there).
   - For each field, tap **Add**, then **Save** at the bottom.
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

- Everything is in `android/` (Kotlin and a little Java):
  - Home and settings: `SettingsActivity.kt` and `HomeScreen.kt` (the alarm card and quick alarms). The settings follow the night: Night and Sunrise (`LookScreen.kt`), Wake-up sound (`SoundScreen.kt`, `StationsScreen.kt`) and Morning (`MorningScreen.kt`). The detailed Schedule is in `AlarmScreen.kt`. Shared parts are in `SettingsUi.kt` and `Themes.kt`, and the colour themes in `ThemeData.kt`.
  - The night, alarm and morning screens: `AlarmFlowActivity.kt` and `AlarmFlowViews.kt`. The wake-up sound is in `WakeSound.kt`, the sleep sounds in `NoisePlayer.kt`, the sunrise maths in `Sunrise.kt` and the morning data in `MorningData.kt` and `CalendarData.kt`.
  - The schedule: `AlarmSettings.kt` (next alarm and plan), `AlarmScheduler.java` (Android alarms and the backup alarm), `Evening.kt` (bedtime reminder, auto-Goodnight) and `Holidays.kt`.
  - Also: `Widgets.kt` (the widget and the Quick Settings tile), `AlarmLog.kt` (the wake-up log, the weekly view and settings backup) and `Secret.kt` (the Todoist token, encrypted).
- Tests: `gradle testDebugUnitTest` runs the unit tests and the screen tests (Robolectric, `app/src/test/.../screens`: the settings screens and the real night, alarm and morning screens), with no phone needed.
- GitHub Actions runs the tests and builds the APK on every push to `main`, published as the `latest` Release. It does the same on pull requests: a pull request's APK goes to the **Test build** pre-release on the Releases page, one tap to install on the phone, and never replaces the normal release.
- Signing: the key lives only in the `KEYSTORE_BASE64` secret, with its password in `KEYSTORE_PASSWORD` (it is no longer kept in the repo). Keep a copy of both somewhere safe: without them, updates can't install over the top.
