package au.sunrise.alarm

import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Native settings screens (Jetpack Compose, Material 3), opened from the page's settings list:
 * "alarm" (Alarm & schedule), "look" (Look & brightness), "morning" (Morning screen),
 * "sound" (Wake-up sound) and "stations" (Find stations).
 * Changes are saved straight into the shared settings; the page reloads them (and reschedules the alarms)
 * when you come back.
 */
class SettingsActivity : ComponentActivity() {
    companion object {
        val PAGES = setOf("alarm", "look", "morning", "sound", "stations")
        /** Something the page should do when you come back (e.g. "preview" the sunrise). */
        const val PREF_ACTION = "settingsAction"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val json = SettingsStore.read(this)
        val page = intent.getStringExtra("page")
        // The colour theme can change on the Look screen, so it's state: the screen recolours straight away
        var themeKey by mutableStateOf(json.optString("theme", "sunrise"))
        setContent {
            SunriseTheme(themeKey) {
                when (page) {
                    "look" -> LookScreen(
                        initial = LookSettings.from(json),
                        onChange = { s -> themeKey = s.theme; SettingsStore.update(this) { s.into(it) } },
                        onPreview = { requestAction("preview") },
                        onBack = { finish() },
                    )
                    "sound", "stations" -> SoundFlow(
                        startOnStations = page == "stations",
                        initial = SoundSettings.from(json),
                        save = { s -> SettingsStore.update(this) { s.into(it) } },
                        spotifyClientId = BuildConfig.SPOTIFY_CLIENT_ID.ifEmpty { json.optString("spClient", "") },
                        onClose = { finish() },
                    )
                    "morning" -> MorningScreen(
                        initial = MorningSettings.from(json),
                        onChange = { s -> SettingsStore.update(this) { s.into(it) } },
                        onBack = { finish() },
                    )
                    else -> AlarmScreen(
                        initial = AlarmSettings.from(json),
                        is24Hour = DateFormat.is24HourFormat(this),
                        onChange = { s -> SettingsStore.update(this) { s.into(it) } },
                        onBack = { finish() },
                    )
                }
            }
        }
    }

    /** Close and have the page do something (it asks for it with takeSettingsAction()). */
    private fun requestAction(action: String) {
        getSharedPreferences(AlarmScheduler.PREFS, 0).edit().putString(PREF_ACTION, action).apply()
        finish()
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
