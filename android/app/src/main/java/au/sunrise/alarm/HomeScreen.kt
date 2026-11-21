package au.sunrise.alarm

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
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
    onRestored: () -> Unit = {},
) {
    val context = LocalContext.current
    val json = remember(refresh) { SettingsStore.read(context) }
    var alarm by remember(refresh) { mutableStateOf(AlarmSettings.from(json)) }
    fun update(n: AlarmSettings) { alarm = n; save(n) }
    val problems = remember(refresh) { runCatching { PhoneChecks.problems(context) }.getOrDefault(emptyList()) }

    // The "in 6h 12m" text keeps up with the clock
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(refresh) { while (true) { now = ZonedDateTime.now(); delay(30_000) } }

    // Updates: the app checks GitHub Releases (at most twice a day, never near an alarm)
    var newVersion by remember { mutableIntStateOf(0) }
    var updateNote by remember { mutableStateOf<String?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }
    LaunchedEffect(Unit) { UpdateCheck.check(context, false) { code -> main.post { newVersion = code } } }

    var editingTime by remember { mutableStateOf(false) }
    var testingBackup by remember { mutableStateOf(false) }
    var rehearsing by remember { mutableStateOf(false) }
    var rehearsalNote by remember { mutableStateOf<String?>(null) }
    var backupNote by remember { mutableStateOf<String?>(null) }
    var includeToken by remember { mutableStateOf(false) }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) backupNote = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(SettingsBackup.export(SettingsStore.read(context), includeToken).toByteArray()) }
            "Settings saved."
        }.getOrElse { "Couldn't save the file." }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backupNote = runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().take(200_000).toByteArray().decodeToString() }
            val restored = SettingsBackup.restore(SettingsStore.read(context), text) ?: return@runCatching "That isn't a Sunrise settings file."
            SettingsStore.write(context, restored.toString())
            Alarms.refresh(context)
            onRestored()
            "Settings restored."
        }.getOrElse { "Couldn't read the file." }
    }
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

        // ----- an alarm that needed the backup alarm (the wake-up log) -----
        var dismissedLog by remember(refresh) { mutableStateOf(AlarmLog.dismissed(context)) }
        AlarmLog.problem(AlarmLog.entries(context), System.currentTimeMillis(), dismissedLog)?.let { e ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    val day = java.time.Instant.ofEpochMilli(e.alarm).atZone(now.zone)
                    Text("The ${HomeText.time(AlarmSettings.formatTime(day.toLocalTime()), is24Hour)} alarm on " +
                        day.format(java.time.format.DateTimeFormatter.ofPattern("EEEE")) + " needed the backup alarm",
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text("Sunrise didn't start ringing by itself, so the phone's own alarm sound rang. Check Phone check below " +
                        "(battery saving is the usual cause).", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = { AlarmLog.dismiss(context, e.alarm); dismissedLog = e.alarm }) { Text("Dismiss") }
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
        SettingsRow("📈", "Your week", AlarmLog.weekSummary(AlarmLog.entries(context), System.currentTimeMillis(), is24Hour)
            .ifEmpty { "When you got up, snoozes and Goodnight times" }) { onOpen("week") }

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
        AlarmLog.lastRehearsal(AlarmLog.entries(context))?.let { e ->
            val report = AlarmLog.rehearsalReport(e)
            val day = java.time.Instant.ofEpochMilli(e.alarm).atZone(now.zone).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))
            val bad = report.filter { !it.first }
            if (e.rang > 0 || e.backup > 0) Hint(if (bad.isEmpty()) "✓ Last rehearsal ($day): everything worked."
                                                 else "⚠ Last rehearsal ($day): " + bad.joinToString("; ") { it.second })
        }
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { rehearsing = true }) { Text("Rehearse the alarm") }
            TextButton(onClick = { testingBackup = true }) { Text("Test the backup alarm") }
        }
        rehearsalNote?.let { Hint(it) }

        // ----- settings backup -----
        SectionTitle("Your settings")
        Hint("Save them to a file (for a new phone, or before reinstalling).")
        if (MorningSettings.from(json).todoToken.isNotEmpty()) {
            SwitchRow("Include the Todoist token", "Off: you'll type it in again after restoring", includeToken) { includeToken = it }
        }
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { exportFile.launch("sunrise-settings.json") }) { Text("Back up") }
            TextButton(onClick = { importFile.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Restore") }
        }
        backupNote?.let { Hint(it) }

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

    if (rehearsing) {
        AlertDialog(
            onDismissRequest = { rehearsing = false },
            title = { Text("Rehearse the alarm?") },
            text = {
                Text("A real alarm in 2½ minutes, with a 1½-minute sunrise. Lock the phone and leave it, as you would at night. " +
                    "Sunrise should open by itself, then ring. Turn it off as usual and the morning screen shows how it went." +
                    if (alarm.onceAt(now) != null) "\n\nThis replaces your quick alarm." else "")
            },
            confirmButton = {
                TextButton(onClick = {
                    rehearsing = false
                    val t = System.currentTimeMillis()
                    val at = t + 150_000
                    update(alarm.copy(once = at, onceStart = t + 60_000, skip = if (alarm.skip == at) 0 else alarm.skip))
                    AlarmLog.rehearsal(context, at)
                    rehearsalNote = "Rehearsal set: lock the phone now. The sunrise starts in about a minute and it rings at " +
                        HomeText.time(AlarmSettings.formatTime(java.time.Instant.ofEpochMilli(at).atZone(now.zone).toLocalTime()), is24Hour) + "."
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { rehearsing = false }) { Text("Cancel") } },
        )
    }

    if (testingBackup) {
        AlertDialog(
            onDismissRequest = { testingBackup = false },
            title = { Text("Test the backup alarm?") },
            text = { Text("The phone's own alarm sound rings now, fading up, as it would if Sunrise ever failed to start. Press Stop in the notification to end it.") },
            confirmButton = { TextButton(onClick = { testingBackup = false; runCatching { PlaybackService.backup(context, 0L) } }) { Text("Ring now") } },
            dismissButton = { TextButton(onClick = { testingBackup = false }) { Text("Cancel") } },
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
