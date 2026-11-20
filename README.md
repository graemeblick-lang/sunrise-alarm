# Sunrise Alarm

A sunrise wake-up light and alarm for an old phone on a bedside stand.

- Sunrise glow on the screen before the alarm
- Wake to Spotify, internet radio, your own songs or gentle chimes
- Morning screen with weather (Open-Meteo), daylight times and Todoist tasks

Open https://graemeblick-lang.github.io/sunrise-alarm/ in Chrome on Android, then ⋮ → Add to home screen → Install.

## Android app

The Android app is self-contained: the screens (`index.html`, fonts and icons) are built into the APK and nothing loads from the website, so it works with no internet. The `android/` folder adds what a web page can't do: it opens itself at sunrise (even over the lock screen), keeps the screen on, controls brightness, plays radio in the background and rings a backup alarm.

GitHub builds it automatically. Download `SunriseAlarm.apk` from the latest Release on your phone and open it to install. After that, the app tells you on its main screen when a newer version is ready (it checks GitHub Releases at most twice a day, never near an alarm).

The web page still keeps the same address inside the app (`graemeblick-lang.github.io/sunrise-alarm/`), but every request to it is answered from the APK. That keeps your saved settings, songs and logins, and the Spotify login redirect, working unchanged. The GitHub Pages site is only needed if you want to use the app in a browser instead.

### Spotify

In the app: **Wake-up sound → Spotify → Connect Spotify** (once), then in Spotify open a playlist, album or song, tap **Share** and pick **Sunrise**. That's it. If the music can't start, Sunrise presses play in Spotify (what you last played), and if nothing plays, the chimes take over.

Spotify only lets an app start a chosen playlist if it's registered as a Spotify "developer app". That's a one-time job for whoever builds the app:

1. At [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard), create an app (or open the one you already have) with:
   - Redirect URI: `au.sunrise.alarm://spotify-callback`
   - APIs used: **Android**
   - Android package: `au.sunrise.alarm`, SHA-1 fingerprint: `A0:00:4D:EE:71:42:49:C6:EB:0C:CF:24:2D:20:5D:3C:1B:94:D0:4A` (the app's release signing key)
2. Copy its **Client ID**. In this GitHub repo: **Settings → Secrets and variables → Actions → Variables → New repository variable**, name `SPOTIFY_CLIENT_ID`.
3. The next build has it built in. (Without it, Spotify still works but plays what you last played.)

### How the alarm stays reliable

- **The app keeps its own schedule.** The page sends the next 8 alarms to Android, which keeps them set even if the app is closed, the phone restarts, the app updates or the clock/time zone changes.
- **Backup alarm.** A minute after each alarm time, Android checks the app actually started ringing. If it didn't (no internet, the page stopped, battery saving froze it), the phone rings with its own alarm sound, fading up, with a Stop button on the lock screen.
- **No internet needed at alarm time.** Around an alarm the app opens its built-in copy straight away instead of waiting for the website.
- **Crashes don't block alarms.** If something goes wrong, the details are kept and shown on a normal launch later, never in place of the sunrise.

For best results, tap **Fix** next to anything the Settings → Status card flags (notifications, full-screen notifications, alarms & reminders, battery saving).

## Development

- `index.html` is the whole web app. `android/app/build.gradle` copies it (with `fonts.css`, `fonts/`, the manifest and icons) into the APK at build time; `LocalSite.java` serves it.
- Fonts are bundled from Fontsource (SIL Open Font License, see `fonts/`).
- The alarm schedule logic sits between the `schedule:start` and `schedule:end` markers and is tested by `tests/schedule.test.js`:
  `TZ=Australia/Melbourne node tests/schedule.test.js`
- GitHub Actions runs the tests and builds the APK on every push to `main` (published as the `latest` Release) and on pull requests (download the test APK from the workflow run's artifacts).
- Signing: set the `KEYSTORE_PASSWORD` secret. Optionally also set `KEYSTORE_BASE64` (`base64 -w0 android/release.jks`) and then `android/release.jks` can be deleted from the repo.
