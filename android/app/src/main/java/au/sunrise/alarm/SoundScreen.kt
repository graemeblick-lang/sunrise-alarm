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
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.ExperimentalMaterial3Api
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
    "UserNotAuthorizedException" to "Spotify didn't accept Sunrise. Check the Spotify developer app's settings and User Management (see the README)",
    "AuthenticationFailedException" to "Spotify didn't accept Sunrise. Check the Spotify developer app's settings and User Management (see the README)",
    "OfflineModeException" to "Spotify is in offline mode",
    "UnsupportedFeatureVersionException" to "Update the Spotify app",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundScreen(
    s: SoundSettings, update: (SoundSettings) -> Unit,
    spotifyClientId: String, onFindStations: () -> Unit, onBack: () -> Unit,
) {
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

    /** After you agree in Spotify: connect App Remote (no pop-up needed now), with a time limit. */
    fun connectSpotify() {
        val waiting = "Connecting to Spotify…"
        spotifyStatus = waiting
        spotifyRemote?.disconnect()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (spotifyStatus == waiting) {
                spotifyRemote?.disconnect()
                spotifyStatus = "Spotify didn't answer. Open the Spotify app, check you're logged in, come back and tap Connect again."
            }
        }, 45_000)
        spotifyRemote = SpotifyRemote { ev ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (ev == "sr:connected") { spotifyStatus = null; update(latest.copy(spAppOk = true, src = "spotify")) }
                else if (ev.startsWith("sr:error:")) {
                    val code = ev.removePrefix("sr:error:")
                    spotifyStatus = SPOTIFY_ERRORS[code] ?: "Spotify couldn't connect ($code)"
                    update(latest.copy(spAppOk = false))
                }
            }
        }.also { it.connect(context, spotifyClientId, true, null, false) }
    }
    val spotifyAuth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val r = AuthorizationClient.getResponse(result.resultCode, result.data)
        when (r.type) {
            AuthorizationResponse.Type.CODE, AuthorizationResponse.Type.TOKEN -> connectSpotify()
            AuthorizationResponse.Type.ERROR -> spotifyStatus = when (r.error) {
                "INVALID_CLIENT", "invalid_client" -> "Spotify doesn't recognise Sunrise's Client ID. Check the developer app (see the README)"
                "INVALID_REDIRECT_URI", "invalid_redirect_uri" -> "Spotify says the Redirect URI doesn't match. It must be au.sunrise.alarm://spotify-callback"
                "access_denied", "AUTHENTICATION_SERVICE_UNAVAILABLE" -> "Spotify didn't allow it (${r.error}). Check User Management in the developer app"
                else -> "Spotify said: ${r.error}"
            }
            else -> spotifyStatus = "Cancelled. Tap Connect Spotify to try again."
        }
    }

    var editingWeekends by remember { mutableStateOf(false) }
    var editingEarly by remember { mutableStateOf(false) }

    SettingsScaffold("Sound", onBack) {
        // The source first (segmented buttons), then what's needed for it
        Spacer(Modifier.height(8.dp))
        Choice(SoundSettings.SOURCES.indices.toList(), SoundSettings.SOURCES.indexOf(s.src).coerceAtLeast(0),
            { listOf("Chimes", "Radio", "Spotify", "App")[it] }) { tester.stop(); update(s.copy(src = SoundSettings.SOURCES[it])) }
        Hint(SOURCE_LABELS.getValue(s.src).second)

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
                            // 1) Spotify's own agree screen (sign-in library), opened from here; 2) then App Remote connects
                            spotifyStatus = "Opening Spotify to agree…"
                            val req = AuthorizationRequest.Builder(spotifyClientId, AuthorizationResponse.Type.CODE, SpotifyRemote.REDIRECT_URI)
                                .setScopes(arrayOf("app-remote-control"))
                                .build()
                            runCatching { spotifyAuth.launch(AuthorizationClient.createLoginActivityIntent(context as Activity, req)) }
                                .onFailure { spotifyStatus = "Couldn't open Spotify (${it.javaClass.simpleName})" }
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

        SectionTitle("Volume")
        PercentSlider("Loudest", s.vol, 10..100) { update(s.copy(vol = it)); tester.stop() }
        ControlLabel("Fade in over")
        Choice(SoundSettings.FADES, s.fade, { "$it min" }) { update(s.copy(fade = it)) }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            FilledTonalButton(onClick = { if (tester.playing) tester.stop() else tester.start(s, spotifyClientId) }) {
                Text(if (tester.playing) "■ Stop" else "▶ Test sound")
            }
        }
        testStatus?.let { Hint(it) }

        SectionTitle("More")
        ValueRow("Weekends", when (s.weekendSrc) {
            "" -> "The same as weekdays"
            else -> listOf("Chimes", "Radio", "Spotify", "Music app")[SoundSettings.SOURCES.indexOf(s.weekendSrc)] + " instead"
        }) { editingWeekends = true }
        if (s.src != "chimes") {
            ValueRow("Music before the alarm", if (s.earlyMin == 0) "Off" else "Starts ${s.earlyMin} min early at ${s.earlyVol}%") { editingEarly = true }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (editingWeekends) {
        AlertDialog(
            onDismissRequest = { editingWeekends = false },
            title = { Text("Weekends") },
            text = {
                Column(Modifier.selectableGroup()) {
                    val options = listOf("" to "The same as weekdays") + SoundSettings.SOURCES.zip(listOf("Chimes", "Radio", "Spotify", "Music app"))
                    options.forEach { (key, name) ->
                        val selected = s.weekendSrc == key
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton) {
                                update(s.copy(weekendSrc = key)); editingWeekends = false
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(name)
                        }
                    }
                    Text("Saturdays and Sundays use the station, playlist or app chosen for weekdays.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { editingWeekends = false }) { Text("Close") } },
        )
    }

    if (editingEarly) {
        AlertDialog(
            onDismissRequest = { editingEarly = false },
            title = { Text("Music before the alarm") },
            text = {
                Column {
                    Text("Music starts quietly before alarm time, then rises to your loudest volume at alarm time.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SoundSettings.EARLY.forEachIndexed { i, v ->
                            SegmentedButton(
                                selected = s.earlyMin == v, onClick = { update(s.copy(earlyMin = v)) },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = SoundSettings.EARLY.size),
                            ) { Text(if (v == 0) "Off" else "$v min") }
                        }
                    }
                    if (s.earlyMin > 0) PercentSlider("Volume until alarm time", s.earlyVol, 5..100) { update(s.copy(earlyVol = it)) }
                }
            },
            confirmButton = { TextButton(onClick = { editingEarly = false }) { Text("Done") } },
        )
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
