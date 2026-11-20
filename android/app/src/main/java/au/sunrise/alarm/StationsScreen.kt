package au.sunrise.alarm

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Find stations (Radio Browser directory): search, listen, add; and your added stations. */
@Composable
fun StationsScreen(s: SoundSettings, update: (SoundSettings) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var australiaOnly by remember { mutableStateOf(true) }
    var quick by remember { mutableStateOf(RadioBrowser.QUICK[0]) }
    var title by remember { mutableStateOf(RadioBrowser.QUICK[0].third) }
    var results by remember { mutableStateOf<List<FoundStation>?>(null) }
    var message by remember { mutableStateOf<String?>("Searching…") }

    // Listening to a station (one at a time, stopped when you leave)
    var listening by remember { mutableStateOf<String?>(null) }
    var listenStatus by remember { mutableStateOf<String?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val previous = remember { PlaybackService.listener }
    fun stopListening() {
        if (listening != null) PlaybackService.stop(context)
        listening = null; listenStatus = null
        PlaybackService.listener = previous
    }
    DisposableEffect(Unit) { onDispose { stopListening() } }
    fun listen(st: FoundStation) {
        if (listening == st.url) { stopListening(); return }
        stopListening()
        listening = st.url; listenStatus = "Tuning in to ${st.name}…"
        PlaybackService.listener = PlaybackService.Listener { ev ->
            main.post {
                if (listening != st.url) return@post
                when (ev) {
                    "playing" -> listenStatus = "Listening to ${st.name}"
                    "error", "ended" -> { stopListening(); listenStatus = "${st.name} isn't playing right now" }
                }
            }
        }
        PlaybackService.play(context, st.url, st.name, maxOf(0.15f, s.vol / 100f * 0.6f))
    }

    // Search when the words, quick pick or Australia-only change (waiting until you stop typing)
    LaunchedEffect(query, quick, australiaOnly) {
        val words = query.trim()
        val params = if (words.length >= 2) mapOf("name" to words) else quick.second
        title = if (words.length >= 2) "Results for “$words”" else quick.third
        if (words.isNotEmpty() && words.length < 2) return@LaunchedEffect
        if (words.length >= 2) delay(450)
        message = "Searching…"; results = null
        val found = withContext(Dispatchers.IO) { runCatching { RadioBrowser.search(params, australiaOnly) } }
        found.onSuccess { list ->
            results = list
            message = if (list.isEmpty()) "No stations found. Try another name, or turn off Australia only." else null
        }.onFailure { message = "Couldn't reach the station directory. Check the internet and try again." }
    }

    SettingsScaffold("Find stations", onBack) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Station name, e.g. Gold, Smooth, 3AW") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioBrowser.QUICK.forEach { q ->
                AssistChip(onClick = { query = ""; quick = q }, label = { Text(q.first) })
            }
        }
        SwitchRow("Australia only", null, australiaOnly) { australiaOnly = it }

        SectionTitle(title)
        listenStatus?.let { Hint(it) }
        message?.let { Hint(it) }
        results?.forEach { st ->
            val added = s.myStations.any { it.url == st.url }
            ListItem(
                headlineContent = { Text(st.name) },
                supportingContent = if (st.info.isNotEmpty()) ({ Text(st.info) }) else null,
                trailingContent = {
                    Row {
                        TextButton(onClick = { listen(st) }) { Text(if (listening == st.url) "Stop" else "Listen") }
                        TextButton(onClick = { update(s.addStation(st.toMine())) }, enabled = !added) { Text(if (added) "Added ✓" else "Add") }
                    }
                },
            )
        }

        SectionTitle("Your stations")
        if (s.myStations.isEmpty()) Hint("None yet. Stations you add appear here and in the station list.")
        s.myStations.forEachIndexed { i, st ->
            val idx = Stations.BUILT_IN.size + i
            val using = s.station == idx && s.src == "radio"
            ListItem(
                headlineContent = { Text(st.name) },
                supportingContent = { Text(if (using) "Your wake-up station" else "Added from search") },
                trailingContent = {
                    Row {
                        TextButton(onClick = { update(s.copy(station = idx, src = "radio")) }, enabled = !using) { Text(if (using) "Using ✓" else "Use") }
                        IconButton(onClick = { update(s.removeStation(i)) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove ${st.name}") }
                    }
                },
            )
        }
    }
}
