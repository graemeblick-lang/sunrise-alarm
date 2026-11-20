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
 * The app's native screens (Jetpack Compose, Material 3). The app opens here, on the home screen.
 * The web page only runs for the night, alarm and morning screens: Goodnight and Preview hand over to it,
 * and it hands back here when it's done.
 *
 * Screens: "home", "alarm" (Alarm & schedule), "look" (Look & brightness), "morning" (Morning screen),
 * "sound" (Wake-up sound) and "stations" (Find stations).
 * Every change is saved into the shared settings and the alarms are rescheduled straight away.
 */
class SettingsActivity : ComponentActivity() {
    companion object {
        val PAGES = setOf("home", "alarm", "look", "morning", "sound", "stations")
        /** Something the page should do when you come back (e.g. "preview" the sunrise). */
        const val PREF_ACTION = "settingsAction"
    }

    /** Which screen is showing. */
    private var route by mutableStateOf("home")
    /** Opened by the page for one screen (older flow): Back closes instead of going home. */
    private var single = false
    /** Bumped whenever the app comes back to the front, so the home screen re-reads everything. */
    private var resumes by mutableIntStateOf(0)
    private var themeKey by mutableStateOf("sunrise")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val page = intent.getStringExtra("page")?.takeIf { it in PAGES && it != "home" }
        single = page != null
        route = savedInstanceState?.getString("route") ?: page ?: "home"
        themeKey = SettingsStore.read(this).optString("theme", "sunrise")
        takeShare(intent)
        // Android 13+: notifications are how the app opens itself at sunrise
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1) }
        }

        setContent {
            SunriseTheme(themeKey) {
                // Back from a settings screen goes home ("stations" handles its own Back, to Wake-up sound)
                BackHandler(enabled = !single && route != "home" && route != "stations") { route = "home" }
                key(route) { Screen(route) }
            }
        }
    }

    @Composable
    private fun Screen(r: String) {
        val json = remember { SettingsStore.read(this) }
        val back: () -> Unit = { if (single || r == "home") finish() else route = "home" }
        when (r) {
            "home" -> HomeScreen(
                refresh = resumes,
                is24Hour = DateFormat.is24HourFormat(this),
                save = { s -> save { s.into(it) } },
                onOpen = { route = it },
                onStart = { action -> startNight(action) },
            )
            "look" -> LookScreen(
                initial = LookSettings.from(json),
                onChange = { s -> themeKey = s.theme; save { s.into(it) } },
                onPreview = { if (single) requestAction("preview") else startNight("preview") },
                onBack = back,
            )
            "sound", "stations" -> SoundFlow(
                startOnStations = r == "stations",
                initial = SoundSettings.from(json),
                save = { s -> save { s.into(it) } },
                spotifyClientId = BuildConfig.SPOTIFY_CLIENT_ID.ifEmpty { json.optString("spClient", "") },
                onClose = back,
            )
            "morning" -> MorningScreen(
                initial = MorningSettings.from(json),
                onChange = { s -> save { s.into(it) } },
                onBack = back,
            )
            else -> AlarmScreen(
                initial = AlarmSettings.from(json),
                is24Hour = DateFormat.is24HourFormat(this),
                onChange = { s -> save { s.into(it) } },
                onBack = back,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        resumes++
        reschedule()   // the page may have changed things (e.g. turned one alarm off)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeShare(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("route", route)
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

    /** Goodnight / Preview: the web page shows the night screen. */
    private fun startNight(action: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra("start", action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
        route = "sound"
    }
}

/** Wake-up sound and Find stations share one copy of the settings, so a station you add shows up straight away. */
@Composable
fun SoundFlow(startOnStations: Boolean, initial: SoundSettings, save: (SoundSettings) -> Unit, spotifyClientId: String, onClose: () -> Unit) {
    var s by remember { mutableStateOf(initial) }
    val update: (SoundSettings) -> Unit = { n -> s = n; save(n) }
    var onStations by rememberSaveable { mutableStateOf(startOnStations) }
    // Back from Find stations goes to Wake-up sound (unless that's where you came in)
    BackHandler(enabled = onStations && !startOnStations) { onStations = false }
    if (onStations) {
        StationsScreen(s, update, onBack = { if (startOnStations) onClose() else onStations = false })
    } else {
        SoundScreen(s, update, spotifyClientId, onFindStations = { onStations = true }, onBack = onClose)
    }
}
