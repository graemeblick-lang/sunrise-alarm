package au.sunrise.alarm

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.time.ZonedDateTime

/**
 * The app's home and settings screens (Jetpack Compose, Material 3). The app opens here, on the home screen.
 * Goodnight and Preview open the night screen (AlarmFlowActivity), which comes back here when it's done.
 *
 * Screens, in the order the night goes: "home", "alarm" (Schedule), "night", "sunrise", "sound" (Wake-up sound,
 * with "stations", Find stations) and "morning".
 * Every change is saved into the shared settings and the alarms are rescheduled straight away.
 */
class SettingsActivity : ComponentActivity() {
    companion object {
        val PAGES = setOf("home", "alarm", "perday", "sound", "stations", "week", "settings", "night", "look", "colours",
            "morning", "checks", "backup", "about")
        /** Something the page should do when you come back (e.g. "preview" the sunrise). */
        const val PREF_ACTION = "settingsAction"
    }

    /** The screens open, oldest first: Back goes to the one before ("home" at the bottom). */
    private val stack = mutableStateListOf("home")
    private val route get() = stack.last()
    /** Opened for one screen only: Back closes instead of going home. */
    private var single = false
    /** Bumped whenever the app comes back to the front, so the screens re-read everything. */
    private var resumes by mutableIntStateOf(0)
    private var themeKey by mutableStateOf("sunrise")
    private var snack: Snack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val page = intent.getStringExtra("page")?.takeIf { it in PAGES && it != "home" }
        single = page != null
        val saved = savedInstanceState?.getString("stack")?.split(",")?.filter { it in PAGES }
        stack.clear(); stack.addAll(saved?.takeIf { it.isNotEmpty() } ?: listOf(page ?: "home"))
        themeKey = SettingsStore.read(this).optString("theme", "sunrise")
        // A Todoist token saved by an older version is encrypted now
        runCatching { SettingsStore.update(this) { j -> if (!Secret.isSealed(j.optString("todoToken", ""))) MorningSettings.from(j).into(j) } }
        takeShare(intent)
        // Android 13+: notifications are how the app opens itself at sunrise
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1) }
        }

        setContent {
            SunriseTheme(themeKey) {
                val host = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                val s = remember { Snack(host, scope) }
                snack = s
                CompositionLocalProvider(LocalSnack provides s) {
                    // Back goes to the screen before ("stations" handles its own Back, to Sound)
                    BackHandler(enabled = stack.size > 1 && route != "stations") { goBack() }
                    key(route, stack.size) { Screen(route) }
                }
            }
        }
    }

    private fun open(r: String) { if (r in PAGES) stack.add(r) }

    private fun goBack() { if (stack.size > 1) stack.removeAt(stack.size - 1) else finish() }

    @Composable
    private fun Screen(r: String) {
        val json = remember { SettingsStore.read(this) }
        val is24 = DateFormat.is24HourFormat(this)
        val back: () -> Unit = { goBack() }
        val preview: () -> Unit = { if (single) requestAction("preview") else startNight("preview") }
        when (r) {
            "home" -> HomeScreen(
                refresh = resumes,
                is24Hour = is24,
                save = { s -> save { s.into(it) } },
                onOpen = { open(it) },
                onStart = { action -> startNight(action) },
            )
            "alarm" -> AlarmScreen(
                initial = AlarmSettings.from(json),
                soundSummary = HomeText.soundRow(SoundSettings.from(json)),
                is24Hour = is24,
                onChange = { s -> save { s.into(it) } },
                onOpen = { open(it) },
                onBack = back,
            )
            "perday" -> PerDayScreen(AlarmSettings.from(json), is24, onChange = { s -> save { s.into(it) } }, onBack = back)
            "sound", "stations" -> SoundFlow(
                startOnStations = r == "stations",
                initial = SoundSettings.from(json),
                save = { s -> save { s.into(it) } },
                spotifyClientId = BuildConfig.SPOTIFY_CLIENT_ID.ifEmpty { json.optString("spClient", "") },
                onClose = back,
            )
            "week" -> WeekScreen(AlarmLog.entries(this), is24, onBack = back)
            "settings" -> SettingsHomeScreen(resumes, onOpen = { open(it) }, onBack = back)
            "night" -> NightScreen(NightSettings.from(json), onNightChange = { n -> save { n.into(it) } }, onBack = back)
            "look" -> LookScreen(
                initial = LookSettings.from(json),
                onChange = { s -> themeKey = s.theme; save { s.into(it) } },
                onOpen = { open(it) },
                onPreview = preview,
                onBack = back,
            )
            "colours" -> ColoursScreen(LookSettings.from(json), onChange = { s -> save { s.into(it) } }, onPreview = preview, onBack = back)
            "morning" -> MorningScreen(
                initial = MorningSettings.from(json),
                onChange = { s -> save { s.into(it) } },
                onBack = back,
            )
            "checks" -> ChecksScreen(resumes, is24, onRehearse = { startRehearsal() }, onBack = back)
            "backup" -> BackupScreen(onRestored = { themeKey = SettingsStore.read(this).optString("theme", "sunrise"); resumes++ }, onBack = back)
            "about" -> AboutScreen(onBack = back)
            else -> HomeScreen(resumes, is24, save = { s -> save { s.into(it) } }, onOpen = { open(it) }, onStart = { startNight(it) })
        }
    }

    /** Rehearse the alarm: a real one-off alarm in 2½ minutes with a 1½-minute sunrise (opens in a minute). */
    private fun startRehearsal() {
        val a = AlarmSettings.from(SettingsStore.read(this))
        val t = System.currentTimeMillis()
        val at = t + 150_000
        save { a.copy(once = at, onceStart = t + 60_000, skip = if (a.skip == at) 0 else a.skip).into(it) }
        AlarmLog.rehearsal(this, at)
        resumes++
        val time = HomeText.time(AlarmSettings.formatTime(java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalTime()),
            DateFormat.is24HourFormat(this))
        snack?.show("Rehearsal set: lock the phone now. The sunrise opens in about a minute and it rings at $time.")
    }

    override fun onResume() {
        super.onResume()
        resumes++
        reschedule()
        Holidays.refresh(this)     // at most once a week, in the background
        // A music fade-in that was cut short (e.g. the phone restarted mid-alarm) left the media volume low: put it back
        if (AlarmScheduler.activeAlarm(this, 0) == 0L) runCatching { NativeAudio(this).musicRestore() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeShare(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("stack", stack.joinToString(","))
    }

    /** Save a change and reschedule the alarms from it, straight away. */
    private fun save(change: (JSONObject) -> Unit) {
        SettingsStore.update(this, change)
        reschedule()
    }

    /** Android keeps the next alarms scheduled itself (the same plan the page makes). */
    private fun reschedule() {
        runCatching {
            AlarmScheduler.setPlan(this, AlarmSettings.from(SettingsStore.read(this)).planJson(ZonedDateTime.now()))
        }.onFailure { App.save(this, "Scheduling alarms from the settings", it) }
    }

    /** Goodnight / Preview: the night screen (AlarmFlowActivity). */
    private fun startNight(action: String) {
        startActivity(Intent(this, AlarmFlowActivity::class.java).putExtra("start", action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    /** Close and have the page do something (it asks for it with takeSettingsAction()). */
    private fun requestAction(action: String) {
        getSharedPreferences(AlarmScheduler.PREFS, 0).edit().putString(PREF_ACTION, action).apply()
        finish()
    }

    /** Spotify → Share → Sunrise: make it the wake-up music (like the page's setSpotifyLink). */
    private fun takeShare(i: Intent?) {
        if (i == null || i.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT)?.take(1000) ?: return
        val link = SoundSettings.parseSpotify(text)
        if (link == null) {
            Toast.makeText(this, "That isn't a Spotify playlist, album or song link", Toast.LENGTH_LONG).show()
            return
        }
        val url = Regex("https?://open\\.spotify\\.com/\\S+").find(text)?.value ?: text.trim()
        val name = (i.getStringExtra(Intent.EXTRA_SUBJECT) ?: "").replace(Regex("\\s+"), " ").trim().take(60)
        save { it.put("spLink", url).put("spName", name).put("src", "spotify") }
        i.action = null   // handle it once
        val connected = SettingsStore.read(this).optBoolean("spAppOk", false)
        Toast.makeText(this,
            if (!connected && BuildConfig.SPOTIFY_CLIENT_ID.isNotEmpty()) "Wake-up ${link.second} saved. Tap Connect Spotify so Sunrise can start it."
            else "Wake-up ${link.second} saved", Toast.LENGTH_LONG).show()
        single = false
        stack.clear(); stack.addAll(listOf("home", "alarm", "sound"))
    }
}

/** Wake-up sound and Find stations share one copy of the settings, so a station you add shows up straight away. */
@Composable
fun SoundFlow(
    startOnStations: Boolean, initial: SoundSettings, save: (SoundSettings) -> Unit,
    spotifyClientId: String, onClose: () -> Unit,
) {
    var s by remember { mutableStateOf(initial) }
    val update: (SoundSettings) -> Unit = { n -> s = n; save(n) }
    var onStations by rememberSaveable { mutableStateOf(startOnStations) }
    // Back from Find stations goes to Sound (unless that's where you came in)
    BackHandler(enabled = onStations && !startOnStations) { onStations = false }
    if (onStations) {
        StationsScreen(s, update, onBack = { if (startOnStations) onClose() else onStations = false })
    } else {
        SoundScreen(s, update, spotifyClientId, onFindStations = { onStations = true }, onBack = onClose)
    }
}
