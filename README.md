# Sunrise Alarm

A sunrise wake-up light and alarm for an old phone on a bedside stand.

- Sunrise glow on the screen before the alarm
- Wake to Spotify, internet radio, your own songs or gentle chimes
- Morning screen with weather (Open-Meteo), daylight times and Todoist tasks

Open https://graemeblick-lang.github.io/sunrise-alarm/ in Chrome on Android, then ⋮ → Add to home screen → Install.

## Android app

The `android/` folder is a small Android shell around the web app. It adds what a web page can't do: it opens itself at sunrise (even over the lock screen), keeps the screen on and controls brightness.

GitHub builds it automatically. Download `SunriseAlarm.apk` from the latest Release on your phone and open it to install.

### How the alarm stays reliable

- **The app keeps its own schedule.** The page sends the next 8 alarms to Android, which keeps them set even if the app is closed, the phone restarts, the app updates or the clock/time zone changes.
- **Backup alarm.** A minute after each alarm time, Android checks the app actually started ringing. If it didn't (no internet, the page stopped, battery saving froze it), the phone rings with its own alarm sound, fading up, with a Stop button on the lock screen.
- **No internet needed at alarm time.** Around an alarm the app opens its built-in copy straight away instead of waiting for the website.
- **Crashes don't block alarms.** If something goes wrong, the details are kept and shown on a normal launch later, never in place of the sunrise.

For best results, tap **Fix** next to anything the Settings → Status card flags (notifications, full-screen notifications, alarms & reminders, battery saving).

## Development

- `index.html` is the whole web app. The alarm schedule logic sits between the `schedule:start` and `schedule:end` markers and is tested by `tests/schedule.test.js`:
  `TZ=Australia/Melbourne node tests/schedule.test.js`
- GitHub Actions runs the tests and builds the APK on every push to `main` (published as the `latest` Release) and on pull requests (download the test APK from the workflow run's artifacts).
- Signing: set the `KEYSTORE_PASSWORD` secret. Optionally also set `KEYSTORE_BASE64` (`base64 -w0 android/release.jks`) and then `android/release.jks` can be deleted from the repo.
