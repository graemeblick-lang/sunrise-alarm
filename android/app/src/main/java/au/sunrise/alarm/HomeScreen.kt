package au.sunrise.alarm

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * The home screen: the alarm card (time, days, skip, pause), quick one-off alarms, Goodnight and Preview,
 * the settings in the order the night goes (Night, Sunrise, Wake-up sound, Morning), phone checks, updates.
 * [refresh] changes whenever the app comes back to the front, so everything is re-read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    refresh: Int,
    is24Hour: Boolean,
    save: (AlarmSettings) -> Unit,
    onOpen: (String) -> Unit,
    onStart: (String) -> Unit,
) {
    val context = LocalContext.current
    val json = remember(refresh) { SettingsStore.read(context) }
    var alarm by remember(refresh) { mutableStateOf(AlarmSettings.from(json)) }
    fun update(n: AlarmSettings) { alarm = n; save(n) }
    val problems = remember(refresh) { PhoneChecks.problems(context) }

    // The "in 6h 12m" text keeps up with the clock
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(refresh) { while (true) { now = ZonedDateTime.now(); delay(30_000) } }

    // Updates: the app checks GitHub Releases (at most twice a day, never near an alarm)
    var newVersion by remember { mutableIntStateOf(0) }
    var updateNote by remember { mutableStateOf<String?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }
    LaunchedEffect(Unit) { UpdateCheck.check(context, false) { code -> main.post { newVersion = code } } }

    var editingTime by remember { mutableStateOf(false) }
    var pickingOnce by remember { mutableStateOf(false) }
    var pickingPause by remember { mutableStateOf(false) }
    // A crash saved last time (App.java): offer to copy it, then forget it
    val crashFile = remember { java.io.File(context.filesDir, App.CRASH_FILE) }
    var crash by remember(refresh) { mutableStateOf(if (crashFile.exists()) runCatching { crashFile.readText() }.getOrNull() else null) }

    SettingsScaffold("Sunrise", onBack = null) {
        crash?.let { report ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Sunrise hit a problem last time", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text("Tap Copy and paste it to Claude.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    Row {
                        TextButton(onClick = {
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(android.content.ClipData.newPlainText("Sunrise crash", report))
                        }) { Text("Copy") }
                        TextButton(onClick = { crashFile.delete(); crash = null }) { Text("Dismiss") }
                    }
                }
            }
        }

        // ----- the alarm: time, days, skip and pause, all on one card -----
        val nowMs = now.toInstant().toEpochMilli()
        val next = alarm.nextAlarm(now)
        val paused = pauseDate(alarm.pauseUntil)?.takeIf { !it.isBefore(now.toLocalDate()) }
        val onCard = MaterialTheme.colorScheme.onPrimaryContainer
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
                Text("Alarm", style = MaterialTheme.typography.labelLarge, color = onCard)
                val bigTime = if (alarm.perDay && next != null) AlarmSettings.formatTime(next.toLocalTime()) else alarm.time
                Text(
                    HomeText.time(bigTime, is24Hour),
                    style = MaterialTheme.typography.displayLarge,
                    color = if (alarm.days.isEmpty() || paused != null) onCard.copy(alpha = 0.55f) else onCard,
                    modifier = Modifier.clickableRow(if (alarm.perDay) "change each day's time" else "change the alarm time") {
                        if (alarm.perDay) onOpen("alarm") else editingTime = true
                    },
                )
                Text(HomeText.next(alarm, now, is24Hour), style = MaterialTheme.typography.bodyLarge, color = onCard)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WEEK_ORDER.forEach { day ->
                        val on = day in alarm.days
                        FilterChip(
                            selected = on,
                            onClick = { update(alarm.copy(days = if (on) alarm.days - day else alarm.days + day)) },
                            label = { Text(DAY_LETTERS[day], modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                            modifier = Modifier.weight(1f).semantics { contentDescription = DAY_NAMES[day] },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    when {
                        alarm.skip > nowMs && alarm.skip != alarm.once -> TextButton(onClick = { update(alarm.copy(skip = 0)) }) { Text("Undo skip") }
                        next != null -> TextButton(onClick = { update(alarm.copy(skip = next.toInstant().toEpochMilli())) }) { Text("Skip next") }
                    }
                    if (paused != null) TextButton(onClick = { update(alarm.copy(pauseUntil = "")) }) { Text("Resume") }
                    else if (alarm.days.isNotEmpty()) TextButton(onClick = { pickingPause = true }) { Text("Pause…") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onOpen("alarm") }) { Text("Schedule") }
                }
            }
        }

        // ----- a one-off alarm, e.g. in 5 minutes -----
        SectionTitle("Quick alarm")
        val onceText = HomeText.once(alarm, now, is24Hour)
        if (onceText.isNotEmpty()) {
            ListItem(
                headlineContent = { Text(onceText) },
                trailingContent = { TextButton(onClick = { update(alarm.copy(once = 0, onceStart = 0)) }) { Text("Cancel") } },
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AlarmSettings.QUICK.forEach { m ->
                AssistChip(
                    onClick = { val t = System.currentTimeMillis(); update(alarm.withOnce(t + m * 60_000L, t)); now = ZonedDateTime.now() },
                    label = { Text("$m min") },
                    modifier = Modifier.semantics { contentDescription = "One-off alarm in $m minutes" },
                )
            }
            AssistChip(onClick = { pickingOnce = true }, label = { Text("Pick a time…") })
        }
        Hint("Rings once. For a short one the sunrise starts straight away.")

        // ----- a new version -----
        if (newVersion > 0) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("A new version is ready", style = MaterialTheme.typography.titleMedium)
                    Text("Tap Download, then open the file to install it over the top. Your settings stay.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateCheck.APK_URL))) }
                    }) { Text("Download update") }
                }
            }
        }

        // ----- tonight -----
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Button(onClick = { onStart("goodnight") }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Goodnight", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onStart("preview") }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Preview the sunrise (1 minute)")
            }
        }

        // ----- settings, in the order the night goes -----
        val sound = SoundSettings.from(json)
        val look = LookSettings.from(json)
        val morning = MorningSettings.from(json)
        SectionTitle("Through the night")
        SettingsRow("🌙", "Night", HomeText.night(look, NightSettings.from(json))) { onOpen("night") }
        SettingsRow("🌅", "Sunrise", HomeText.sunrise(look, alarm.dur)) { onOpen("sunrise") }
        SettingsRow("🔊", "Wake-up sound", HomeText.sound(sound, alarm.snooze)) { onOpen("sound") }
        SettingsRow("☀️", "Morning", HomeText.morning(morning)) { onOpen("morning") }

        // ----- phone check -----
        SectionTitle("Phone check")
        problems.forEach { p ->
            ListItem(
                headlineContent = { Text("⚠ " + PhoneChecks.describe(p), color = MaterialTheme.colorScheme.error) },
                trailingContent = { TextButton(onClick = { PhoneChecks.fix(context, p) }) { Text("Fix") } },
            )
        }
        Hint("✓ Screen stays on and brightness is handled for you.")
        Hint("✓ Backup alarm: if the app ever fails to start ringing, the phone rings with its own alarm sound a minute later.")
        Hint("Leave the phone on charge overnight.")

        HorizontalDivider(Modifier.padding(16.dp))
        Row(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "Sunrise Alarm ${BuildConfig.VERSION_NAME} · works offline",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(top = 14.dp),
            )
            TextButton(onClick = {
                updateNote = "Checking…"
                UpdateCheck.check(context, true) { code -> main.post { newVersion = code; updateNote = null } }
                main.postDelayed({ if (newVersion == 0) updateNote = "You have the latest version" }, 10_000)
            }) { Text("Check for updates") }
        }
        updateNote?.let { Hint(it) }
    }

    if (editingTime) {
        TimeDialog(
            title = "Alarm time",
            initial = AlarmSettings.parseTime(alarm.time) ?: LocalTime.of(6, 30),
            is24Hour = is24Hour,
            onDismiss = { editingTime = false },
            onConfirm = { t -> update(alarm.copy(time = AlarmSettings.formatTime(t))); editingTime = false },
        )
    }

    if (pickingOnce) {
        TimeDialog(
            title = "One-off alarm",
            initial = LocalTime.now().plusMinutes(60).withSecond(0).withNano(0),
            is24Hour = is24Hour,
            onDismiss = { pickingOnce = false },
            onConfirm = { t ->
                val n = ZonedDateTime.now()
                var at = n.toLocalDate().atTime(t).atZone(n.zone)
                if (!at.isAfter(n)) at = at.plusDays(1)
                update(alarm.withOnce(at.toInstant().toEpochMilli(), System.currentTimeMillis()))
                pickingOnce = false
            },
        )
    }

    if (pickingPause) {
        PauseDialog(
            initial = pauseDate(alarm.pauseUntil),
            onDismiss = { pickingPause = false },
            onConfirm = { d -> update(alarm.copy(pauseUntil = d.toString())); pickingPause = false },
        )
    }
}

@Composable
private fun SettingsRow(icon: String, title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, modifier = Modifier.semantics { heading() }) },
        supportingContent = { Text(summary) },
        leadingContent = { Text(icon, style = MaterialTheme.typography.headlineSmall) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(),
        modifier = Modifier.clickableRow("open $title", onClick),
    )
}
