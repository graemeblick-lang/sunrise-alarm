package au.sunrise.alarm

import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Native settings screens (Jetpack Compose, Material 3), opened from the page's settings list:
 * "alarm" (Alarm & schedule), "look" (Look & brightness), "morning" (Morning screen).
 * Changes are saved straight into the shared settings; the page reloads them (and reschedules the alarms)
 * when you come back.
 */
class SettingsActivity : ComponentActivity() {
    companion object {
        val PAGES = setOf("alarm", "look", "morning")
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
