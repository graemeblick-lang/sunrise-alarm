package au.sunrise.alarm

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.ZonedDateTime
import kotlin.math.min
import kotlin.random.Random

/**
 * The night, alarm (ring and snooze) and morning screens, natively (they were the web page).
 * Shows over the lock screen and keeps the screen on. Opened by:
 *  - "start" = "goodnight" / "preview" (the home screen's buttons)
 *  - "wake" with the alarm time (AlarmReceiver: sunrise is starting, or the backup alarm)
 *  - nothing: carries on with whatever was armed (after a restart), or goes home.
 *
 * The flow is the page's state machine: night → (sunrise) → ring ⇄ snooze → morning → home.
 */
class AlarmFlowActivity : ComponentActivity() {
    companion object {
        private const val PREFS = "flow"
        const val RING_LIMIT = 30 * 60_000L
    }

    // ----- state (Compose reads these) -----
    var mode by mutableStateOf("")            // night | ring | snooze | morning
        private set
    var alarmAt by mutableLongStateOf(0L)
        private set
    var sunriseStart by mutableLongStateOf(0L)
        private set
    var snoozeUntil by mutableLongStateOf(0L)
        private set
    var preview by mutableStateOf(false)
        private set
    var now by mutableLongStateOf(System.currentTimeMillis())
        private set
    var status by mutableStateOf("")
        private set
    var nightLight by mutableIntStateOf(0)
        private set
    var controlsUntil by mutableLongStateOf(0L)
        private set
    var clockShift by mutableStateOf(0 to 0)
        private set
    var volumeHudUntil by mutableLongStateOf(0L)
        private set
    var morningRefresh by mutableIntStateOf(0)
        private set
    /** Bumps when the sound starts or stops, so the screen re-reads [sound]. */
    var soundChanged by mutableIntStateOf(0)
        private set

    lateinit var sound: WakeSound
        private set
    /** The shared settings (re-read when a night starts and when sound starts). */
    var settings = org.json.JSONObject()
        private set

    private val main = Handler(Looper.getMainLooper())
    private var earlyStopped = false
    private var ringEnteredAt = 0L
    private var morningSince = 0L
    private var lastShift = 0L
    private var weatherPrefetched = false
    private var nightLightOffAt = 0L
    private var lastBrightness = -1f

    val alarm get() = AlarmSettings.from(settings)
    val look get() = LookSettings.from(settings)
    val soundSettings get() = SoundSettings.from(settings)
    val morning get() = MorningSettings.from(settings)
    val is24Hour get() = DateFormat.is24HourFormat(this)
    val theme: FullTheme get() = Sunrise.theme(look.theme, phoneColours())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runCatching { fullscreen() }

        settings = SettingsStore.read(this)
        sound = WakeSound(this, { mode }, object : WakeSound.Events {
            override fun status(text: String) { status = text; soundChanged++ }
            override fun userStopped() = onUserStoppedMusic()
        })

        setContent { AlarmFlowScreens(this) }

        if (!handle(intent)) resumeOrHome()
        main.post(ticker)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() { super.onResume(); runCatching { fullscreen() } }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        // Closing normally stops the sound. If Android closes the screen by itself mid-alarm, the radio keeps
        // going in its service and the alarm carries on when the app reopens (it's still armed).
        if (isFinishing) sound.stop()
        super.onDestroy()
    }

    // ---------- how we were opened ----------

    /** Goodnight / Preview / an alarm. False if the intent asked for nothing. */
    private fun handle(i: Intent?): Boolean {
        if (i == null) return false
        when (i.getStringExtra("start")) {
            "goodnight" -> { i.removeExtra("start"); enterNight(false); return true }
            "preview" -> { i.removeExtra("start"); enterNight(true); return true }
        }
        if (i.getBooleanExtra("wake", false)) {
            i.removeExtra("wake")
            wake(i.getLongExtra(AlarmScheduler.EXTRA_ALARM, 0L))
            return true
        }
        return false
    }

    /** Sunrise is starting (or the backup alarm went off) for the alarm at [ms] (the page's nativeWake). */
    private fun wake(ms: Long) {
        val a = alarm
        val handled = settings.optLong("handled", 0L)
        if (ms > 0 && (ms == a.skip || ms <= handled)) { if (mode.isEmpty()) goHome(); return }   // already dealt with
        val t = System.currentTimeMillis()
        if (ms > 0 && t >= ms && t < ms + RING_LIMIT) {
            if (mode == "ring" || mode == "snooze") return
            sound.stop()
            preview = false; alarmAt = ms; sunriseStart = ms - a.dur * 60_000L
            saveArmed(); enterRing(); return
        }
        if (mode.isEmpty() || mode == "morning") enterNight(false, ms)
    }

    /** Reopened with nothing to do: carry on with an armed alarm (ringing if its time has come), or go home. */
    private fun resumeOrHome() {
        val p = getSharedPreferences(PREFS, 0)
        val at = p.getLong("alarmAt", 0L)
        val start = p.getLong("sunriseStart", 0L)
        val t = System.currentTimeMillis()
        when {
            at > 0 && t < at -> {
                preview = false; alarmAt = at; sunriseStart = start
                mode = "night"; AlarmScheduler.armBackup(this, at)
            }
            at > 0 && t < at + RING_LIMIT -> { preview = false; alarmAt = at; sunriseStart = start; enterRing() }
            else -> { if (at > 0) p.edit().clear().apply(); goHome() }
        }
    }

    // ---------- the flow ----------

    fun enterNight(isPreview: Boolean, targetMs: Long = 0L) {
        sound.stop(); soundChanged++
        settings = SettingsStore.read(this)
        preview = isPreview
        val t = System.currentTimeMillis()
        if (preview) { alarmAt = t + 60_000; sunriseStart = t + 2_000 }
        else {
            val next = if (targetMs > t) targetMs else alarm.nextAlarm(ZonedDateTime.now())?.toInstant()?.toEpochMilli()
            if (next == null) { toast("Pick a time first"); goHome(); return }
            alarmAt = next; sunriseStart = next - alarm.dur * 60_000L
        }
        earlyStopped = false; weatherPrefetched = false; sound.earlyPlay = false
        if (!preview) { saveArmed(); AlarmScheduler.armBackup(this, alarmAt) }
        mode = "night"
        tick()
    }

    private fun enterRing() {
        mode = "ring"; status = ""; ringEnteredAt = System.currentTimeMillis()
        if (!preview) { AlarmScheduler.markRinging(this); PlaybackService.stopBackup(this) }
        if (sound.earlyPlay && sound.using != null) sound.continueFromEarly()
        else { sound.earlyPlay = false; startSound() }
        brightness(1f)
    }

    fun snooze() {
        sound.stop(); soundChanged++
        snoozeUntil = System.currentTimeMillis() + if (preview) 15_000L else alarm.snooze * 60_000L
        mode = "snooze"; status = ""
        if (!preview) { AlarmScheduler.markRinging(this); AlarmScheduler.armBackup(this, snoozeUntil) }   // if the app stalls, the phone still rings
    }

    /** "I'm up" on the alarm screen (keeps the music playing), or after the alarm gives up. */
    fun enterMorning(keepMusic: Boolean) {
        if (!(keepMusic && sound.musicOn)) { sound.stop(); soundChanged++ }
        sound.earlyPlay = false
        disarm()
        mode = "morning"; morningSince = System.currentTimeMillis(); morningRefresh++
        brightness(1f)
    }

    /** "I'm up" on the night screen: this alarm won't go off later. */
    fun upFromNight() {
        nightLight = 0
        if (!preview && alarmAt > 0) skip(alarmAt)
        enterMorning(true)
    }

    /** "Change alarm" on the night screen. */
    fun changeAlarm() { nightLight = 0; disarm(); sound.stop(); goHome() }

    /** "Turn off alarm" on the night screen: this one is skipped, repeats stay on. */
    fun turnOff() {
        nightLight = 0; disarm(); sound.stop()
        if (!preview && alarmAt > 0) skip(alarmAt)
        toast(if (alarm.days.isNotEmpty()) "This alarm is off. Repeats stay on." else "Alarm off")
        goHome()
    }

    /** "Play" / "Stop" on the night screen (music before the alarm, by hand). */
    fun toggleNightMusic() {
        if (sound.earlyPlay && sound.using != null) { sound.stop(); sound.earlyPlay = false; earlyStopped = true }
        else { sound.earlyPlay = true; startSound() }
        soundChanged++
    }

    fun done() { sound.stop(); goHome() }

    fun stopMusic() { sound.stop(); status = ""; soundChanged++ }

    fun volume(up: Boolean) {
        if (sound.nudge(up, step = 0.08f, floor = 0.03f)) { volumeHudUntil = System.currentTimeMillis() + 1_500; soundChanged++ }
    }

    // ---------- night light and controls ----------

    fun showControls() { controlsUntil = System.currentTimeMillis() + 5_000 }

    fun chooseNightLight(level: Int) {
        nightLight = level; controlsUntil = 0
        nightLightOffAt = if (level > 0) System.currentTimeMillis() + 10 * 60_000L else 0L
        tick()
    }

    // ---------- every second ----------

    private val ticker = object : Runnable {
        override fun run() { runCatching { tick() }.onFailure { App.save(this@AlarmFlowActivity, "Alarm screen tick", it) }; main.postDelayed(this, 1_000) }
    }

    private fun tick() {
        val t = System.currentTimeMillis()
        now = t
        if (nightLightOffAt in 1..t) { nightLight = 0; nightLightOffAt = 0 }
        when (mode) {
            "night" -> {
                val p = progress(t)
                brightness(Sunrise.frame(theme, look, p, nightLight).brightness.toFloat())
                if (p == 0.0 && t - lastShift > 120_000) {   // move the clock a little so it doesn't burn into the screen
                    lastShift = t; clockShift = Random.nextInt(-20, 21) to Random.nextInt(-30, 31)
                }
                if (p > 0) {
                    if (nightLight > 0) nightLight = 0
                    clockShift = 0 to 0
                    if (!weatherPrefetched) { weatherPrefetched = true; prefetchWeather() }
                }
                val s = soundSettings
                if (!preview && s.earlyMin > 0 && s.src != "chimes" && sound.using == null && !earlyStopped &&
                    t < alarmAt && t >= alarmAt - s.earlyMin * 60_000L) { sound.earlyPlay = true; startSound() }
                if (sound.using != null) sound.ramp()
                if (t >= alarmAt) enterRing()
            }
            "ring" -> {
                sound.ramp()
                if (t - ringEnteredAt > RING_LIMIT) enterMorning(false)   // give up after 30 minutes
            }
            "snooze" -> if (t >= snoozeUntil) enterRing()
            "morning" -> if (!sound.musicOn && t - morningSince > 30 * 60_000L) {
                // Back to the night clock for the next alarm (or home if there isn't one)
                if (alarm.nextAlarm(ZonedDateTime.now()) != null) enterNight(false) else goHome()
            }
        }
    }

    /** 0 before the sunrise starts, 1 at alarm time. */
    fun progress(t: Long): Double =
        if (t < sunriseStart) 0.0 else min(1.0, (t - sunriseStart).toDouble() / (alarmAt - sunriseStart).coerceAtLeast(1))

    // ---------- helpers ----------

    private fun startSound() {
        settings = SettingsStore.read(this)
        sound.start(soundSettings, BuildConfig.SPOTIFY_CLIENT_ID.ifEmpty { settings.optString("spClient", "") })
        soundChanged++
    }

    private fun onUserStoppedMusic() {
        when (mode) {
            "ring" -> enterMorning(false)
            "night" -> { sound.stop(); sound.earlyPlay = false; earlyStopped = true }
            else -> sound.stop()
        }
        status = ""; soundChanged++
    }

    private fun saveArmed() {
        getSharedPreferences(PREFS, 0).edit().putLong("alarmAt", alarmAt).putLong("sunriseStart", sunriseStart).apply()
    }

    /** Not armed any more. If this alarm's sunrise had started, remember it was dealt with (so a late wake is ignored). */
    private fun disarm() {
        getSharedPreferences(PREFS, 0).edit().clear().apply()
        if (!preview && alarmAt > 0 && System.currentTimeMillis() >= alarmAt - alarm.dur * 60_000L) {
            SettingsStore.update(this) { it.put("handled", alarmAt) }
        }
        AlarmScheduler.cancelBackup(this)
        reschedule()
    }

    private fun skip(ms: Long) {
        SettingsStore.update(this) { it.put("skip", ms) }
        settings = SettingsStore.read(this)
        reschedule()
    }

    private fun reschedule() = runCatching {
        AlarmScheduler.setPlan(this, AlarmSettings.from(SettingsStore.read(this)).planJson(ZonedDateTime.now()))
    }

    private fun prefetchWeather() {
        val m = morning
        Thread { runCatching { WeatherData.get(applicationContext, m.lat, m.lon, true) } }.start()
    }

    private fun goHome() {
        startActivity(Intent(this, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()

    private fun brightness(v: Float) {
        if (kotlin.math.abs(v - lastBrightness) < 0.003f) return
        lastBrightness = v
        val lp = window.attributes
        lp.screenBrightness = v.coerceIn(0.002f, 1f)
        window.attributes = lp
    }

    private fun fullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    /** Android 12+'s wallpaper palette, for the "Phone colours" theme. */
    private fun phoneColours(): Map<String, Int>? {
        if (Build.VERSION.SDK_INT < 31) return null
        val ids = mapOf(
            "a1_100" to android.R.color.system_accent1_100, "a1_200" to android.R.color.system_accent1_200,
            "a1_300" to android.R.color.system_accent1_300, "a1_500" to android.R.color.system_accent1_500,
            "a1_700" to android.R.color.system_accent1_700, "a1_800" to android.R.color.system_accent1_800,
            "a1_900" to android.R.color.system_accent1_900, "a2_700" to android.R.color.system_accent2_700,
            "n1_900" to android.R.color.system_neutral1_900,
        )
        return ids.mapValues { (_, id) -> getColor(id) and 0xFFFFFF }
    }

    // Phone volume buttons control the alarm's sound while it plays
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val up = keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if ((up || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && sound.nudge(up)) {
            volumeHudUntil = System.currentTimeMillis() + 1_500; soundChanged++
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && sound.using != null) return true
        return super.onKeyUp(keyCode, event)
    }
}
