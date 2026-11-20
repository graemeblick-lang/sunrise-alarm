package au.sunrise.alarm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private val SOURCE_LABELS = mapOf(
    "chimes" to ("Chimes" to "Gentle bells. No internet needed."),
    "radio" to ("Radio" to "ABC stations, or any station you find."),
    "spotify" to ("Spotify" to "Your playlist in the Spotify app."),
    "apps" to ("Another music app" to "Presses play in an app such as Podcast Addict."),
)

/** Spotify App Remote errors, as on the page (SR_MSG). */
private val SPOTIFY_ERRORS = mapOf(
    "CouldNotFindSpotifyApp" to "The Spotify app isn't installed",
    "NotLoggedInException" to "Log in to the Spotify app first",
    "UserNotAuthorizedException" to "Spotify didn't accept Sunrise. The one-time Spotify developer setup isn't finished (see the README)",
    "AuthenticationFailedException" to "Spotify didn't accept Sunrise. The one-time Spotify developer setup isn't finished (see the README)",
    "OfflineModeException" to "Spotify is in offline mode",
    "UnsupportedFeatureVersionException" to "Update the Spotify app",
)

@Composable
fun SoundScreen(
    s: SoundSettings, update: (SoundSettings) -> Unit, snooze: Int, onSnooze: (Int) -> Unit,
    spotifyClientId: String, onFindStations: () -> Unit, onBack: () -> Unit,
) {
    var snoozeLen by remember { mutableStateOf(snooze) }
    val context = LocalContext.current
    // Callbacks that arrive later (Spotify) must change the latest settings, not the ones from when they started
    val latest by rememberUpdatedState(s)
    var testStatus by remember { mutableStateOf<String?>(null) }
    val tester = remember { SoundTester(context) { testStatus = it } }
    DisposableEffect(Unit) { onDispose { tester.stop() } }
    val musicApps = remember { MediaApps.list(context).map { it[0] to it[1] } }
    var spotifyStatus by remember { mutableStateOf<String?>(null) }
    var spotifyRemote by remember { mutableStateOf<SpotifyRemote?>(null) }
    DisposableEffect(Unit) { onDispose { spotifyRemote?.disconnect() } }
    var pasting by remember { mutableStateOf(false) }

    SettingsScaffold("Wake-up sound", onBack) {
        SectionTitle("Play")
        Column(Modifier.selectableGroup()) {
            SoundSettings.SOURCES.forEach { src ->
                val (title, sub) = SOURCE_LABELS.getValue(src)
                val selected = s.src == src
                ListItem(
                    headlineContent = { Text(title) },
                    supportingContent = { Text(sub) },
                    leadingContent = { RadioButton(selected = selected, onClick = null) },
                    modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) { tester.stop(); update(s.copy(src = src)) },
                )
            }
        }

        when (s.src) {
            "radio" -> {
                SectionTitle("Station")
                Column(Modifier.selectableGroup()) {
                    s.stationNames().forEachIndexed { i, name ->
                        val selected = s.station == i
                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = if (i >= Stations.BUILT_IN.size && i < s.customIndex) ({ Text("Added from search") }) else null,
                            leadingContent = { RadioButton(selected = selected, onClick = null) },
                            modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) { update(s.copy(station = i)) },
                        )
                    }
                }
                if (s.station == s.customIndex) {
                    OutlinedTextField(
                        value = s.customUrl,
                        onValueChange = { update(s.copy(customUrl = it.trim())) },
                        label = { Text("Stream link (https://…)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedButton(onClick = onFindStations) { Text("Find more stations") }
                }
                Hint("Needs the internet. If the station won't play, the chimes play instead.")
            }

            "spotify" -> {
                SectionTitle("Spotify")
                val canConnect = spotifyClientId.isNotEmpty()
                ListItem(
                    headlineContent = { Text(if (s.spAppOk) "Connected" else "Not connected") },
                    supportingContent = {
                        Text(spotifyStatus ?: when {
                            s.spAppOk -> "Sunrise can start your wake-up music in the Spotify app on this phone."
                            canConnect -> "Connect once so Sunrise can start your wake-up music. Spotify asks you to agree, then you're done."
                            else -> "This version of the app can't choose music in Spotify, so it presses play and you hear what you last played."
                        })
                    },
                )
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.spAppOk) {
                        OutlinedButton(onClick = { update(s.copy(spAppOk = false)) }) { Text("Disconnect") }
                    } else if (canConnect) {
                        FilledTonalButton(onClick = {
                            spotifyStatus = "Connecting… (Spotify may ask you to agree)"
                            spotifyRemote?.disconnect()
                            spotifyRemote = SpotifyRemote { ev ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    if (ev == "sr:connected") { spotifyStatus = null; update(latest.copy(spAppOk = true, src = "spotify")) }
                                    else if (ev.startsWith("sr:error:")) {
                                        val code = ev.removePrefix("sr:error:")
                                        spotifyStatus = SPOTIFY_ERRORS[code] ?: "Spotify couldn't connect ($code)"
                                        update(latest.copy(spAppOk = false))
                                    }
                                }
                            }.also { it.connect(context.applicationContext, spotifyClientId, true, null, false) }
                        }) { Text("Connect Spotify") }
                    }
                }

                SectionTitle("Wake-up music")
                val link = SoundSettings.parseSpotify(s.spLink)
                ListItem(
                    headlineContent = { Text(if (link != null) s.spName.ifEmpty { "Your ${link.second}" } else "What you last played") },
                    supportingContent = { Text("To choose music: in Spotify, open a playlist, album or song, tap Share, then pick Sunrise.") },
                )
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (link != null) OutlinedButton(onClick = { update(s.copy(spLink = "", spName = "")) }) { Text("Use what I last played") }
                    if (!pasting) OutlinedButton(onClick = { pasting = true }) { Text("Paste a link instead") }
                }
                if (pasting) {
                    var text by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { v ->
                            text = v
                            if (SoundSettings.parseSpotify(v) != null) {
                                val url = Regex("https?://open\\.spotify\\.com/\\S+").find(v)?.value ?: v.trim()
                                update(s.copy(spLink = url, spName = "", src = "spotify")); pasting = false
                            }
                        },
                        label = { Text("Spotify playlist, album or song link") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (link != null && link.second != "song") {
                    SwitchRow("Shuffle", null, s.spShuffle) { update(s.copy(spShuffle = it)) }
                }
                Hint("If your music can't start, Sunrise presses play in Spotify instead, and if nothing plays, the chimes play.")
            }

            "apps" -> {
                SectionTitle("Music app")
                if (musicApps.isEmpty()) Hint("No music apps found on this phone.")
                Column(Modifier.selectableGroup()) {
                    musicApps.forEach { (pkg, label) ->
                        val selected = s.appPkg == pkg
                        ListItem(
                            headlineContent = { Text(label) },
                            leadingContent = { RadioButton(selected = selected, onClick = null) },
                            modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) { update(s.copy(appPkg = pkg, appName = label)) },
                        )
                    }
                }
                Hint("Presses play in that app and fades it in, so it plays whatever you last had on there. Some apps, like YouTube Music, don't let other apps start them, so you'll get chimes.")
            }
        }

        Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            FilledTonalButton(onClick = { if (tester.playing) tester.stop() else tester.start(s, spotifyClientId) }) {
                Text(if (tester.playing) "■ Stop" else "▶ Test sound")
            }
        }
        testStatus?.let { Hint(it) }

        SectionTitle("Volume")
        PercentSlider("Maximum volume", s.vol, 10..100) { update(s.copy(vol = it)); tester.stop() }
        Text("Fade in over", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Choice(SoundSettings.FADES, s.fade, { "$it min" }) { update(s.copy(fade = it)) }

        if (s.src != "chimes") {
            SectionTitle("Music before the alarm")
            Choice(SoundSettings.EARLY, s.earlyMin, { if (it == 0) "Off" else "$it min" }) { update(s.copy(earlyMin = it)) }
            if (s.earlyMin > 0) {
                PercentSlider("Volume before the alarm", s.earlyVol, 5..100) { update(s.copy(earlyVol = it)) }
            }
            Hint("Music started before alarm time (automatically, or by tapping the screen) fades in to this volume and stays there. At alarm time it rises to your maximum volume.")
        }

        SectionTitle("Snooze")
        Choice(AlarmSettings.SNOOZE_LENGTHS, snoozeLen, { "$it min" }) { snoozeLen = it; onSnooze(it) }
    }
}

/** A labelled 0–100 % slider that saves when you let go. */
@Composable
private fun PercentSlider(label: String, value: Int, range: IntRange, onDone: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("$label: ${v.toInt()}%", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onDone(v.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.semantics { contentDescription = label; stateDescription = "${v.toInt()}%" },
        )
    }
}
