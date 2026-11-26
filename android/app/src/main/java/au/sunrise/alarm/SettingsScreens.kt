package au.sunrise.alarm

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** ⚙ Settings: everything that isn't the alarm itself, in clear groups, each row saying what it's set to. */
@Composable
fun SettingsHomeScreen(refresh: Int, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val json = remember(refresh) { SettingsStore.read(context) }
    val issues = remember(refresh) { Readiness.issues(context) }
    val rehearsal = remember(refresh) { AlarmLog.lastRehearsal(AlarmLog.entries(context)) }
    SettingsScaffold("Settings", onBack) {
        ValueRow("Night", nightSummary(NightSettings.from(json)), icon = "🌙") { onOpen("night") }
        ValueRow("Look", HomeText.look(LookSettings.from(json)), icon = "🎨") { onOpen("look") }
        ValueRow("Morning", HomeText.morning(MorningSettings.from(json)), icon = "☀️") { onOpen("morning") }
        ValueRow("Phone check & rehearsal", when {
            issues.isNotEmpty() -> "⚠ ${issues.size} thing${if (issues.size > 1) "s" else ""} to fix"
            rehearsal != null && (rehearsal.rang > 0 || rehearsal.backup > 0) ->
                "All good · last rehearsal " + Instant.ofEpochMilli(rehearsal.alarm).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM"))
            else -> "All good · try a rehearsal"
        }, icon = "✅") { onOpen("checks") }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        ValueRow("Back up & restore", "Save your settings to a file", icon = "💾") { onOpen("backup") }
        ValueRow("About", "Version ${BuildConfig.VERSION_NAME} · check for updates", icon = "ℹ️") { onOpen("about") }
    }
}

/** The Night row's summary: what happens at bedtime. */
fun nightSummary(n: NightSettings): String = listOfNotNull(
    if (n.autoNight > 0) "Starts on the charger after ${n.autoNight - 12} pm" else null,
    NoisePlayer.NAMES[n.sleepSound]?.lowercase()?.let { "$it to sleep to" },
    if (n.bedtime > 0) "reminder ${n.bedtime} h before" else null,
).joinToString(" · ").replaceFirstChar { it.uppercase() }

/** Settings → Phone check & rehearsal: what could stop the alarm, the last rehearsal, and the two tests. */
@Composable
fun ChecksScreen(refresh: Int, is24Hour: Boolean, onRehearse: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val snack = rememberSnack()
    var bump by remember { mutableIntStateOf(0) }
    val problems = remember(refresh, bump) { runCatching { PhoneChecks.problems(context) }.getOrDefault(emptyList()) }
    val log = remember(refresh, bump) { AlarmLog.entries(context) }
    var dismissed by remember(refresh) { mutableStateOf(AlarmLog.dismissed(context)) }
    var rehearsing by remember { mutableStateOf(false) }
    var testingBackup by remember { mutableStateOf(false) }

    SettingsScaffold("Phone check", onBack) {
        SectionTitle("Phone settings")
        if (problems.isEmpty()) ListItem(headlineContent = { Text("✓ All set") },
            supportingContent = { Text("Notifications, full-screen alarms, exact alarms and battery saving are all fine.") })
        problems.forEach { p ->
            ListItem(
                headlineContent = { Text("⚠ " + PhoneChecks.describe(p), color = MaterialTheme.colorScheme.error) },
                trailingContent = { TextButton(onClick = { PhoneChecks.fix(context, p) }) { Text("Fix") } },
            )
        }
        Hint("Leave the phone on charge overnight. The screen stays on and its brightness is handled for you.")

        AlarmLog.problem(log, System.currentTimeMillis(), dismissed)?.let { e ->
            SectionTitle("Recent alarms")
            val at = Instant.ofEpochMilli(e.alarm).atZone(ZoneId.systemDefault())
            ListItem(
                headlineContent = { Text("⚠ The ${HomeText.time(AlarmSettings.formatTime(at.toLocalTime()), is24Hour)} alarm on " +
                    at.format(DateTimeFormatter.ofPattern("EEEE")) + " needed the backup alarm", color = MaterialTheme.colorScheme.error) },
                supportingContent = { Text("Sunrise didn't start ringing by itself, so the phone's own alarm sound rang. Battery saving is the usual cause.") },
                trailingContent = { TextButton(onClick = { AlarmLog.dismiss(context, e.alarm); dismissed = e.alarm; snack("Dismissed", null) {} }) { Text("Dismiss") } },
            )
        }

        SectionTitle("Rehearsal")
        val last = AlarmLog.lastRehearsal(log)?.takeIf { it.rang > 0 || it.backup > 0 }
        if (last == null) Hint("A real alarm in 2½ minutes, with the phone locked: the best way to know it works on this phone.")
        else {
            val day = Instant.ofEpochMilli(last.alarm).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM"))
            Text("Last rehearsal, $day", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            AlarmLog.rehearsalReport(last).forEach { (ok, what) ->
                Text((if (ok) "✓ " else "✗ ") + what, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            FilledTonalButton(onClick = { rehearsing = true }) { Text("Rehearse the alarm") }
            Spacer(Modifier.padding(4.dp))
            OutlinedButton(onClick = { testingBackup = true }) { Text("Test the backup alarm") }
        }
    }

    if (rehearsing) {
        AlertDialog(
            onDismissRequest = { rehearsing = false },
            title = { Text("Rehearse the alarm?") },
            text = { Text("A real alarm in 2½ minutes, with a 1½-minute sunrise. Lock the phone and leave it, as you would at night. " +
                "Sunrise should open by itself, then ring. Turn it off as usual and the morning screen shows how it went. (It replaces a quick alarm, if you set one.)") },
            confirmButton = { TextButton(onClick = { rehearsing = false; onRehearse() }) { Text("Start") } },
            dismissButton = { TextButton(onClick = { rehearsing = false }) { Text("Cancel") } },
        )
    }

    if (testingBackup) {
        AlertDialog(
            onDismissRequest = { testingBackup = false },
            title = { Text("Test the backup alarm?") },
            text = { Text("The phone's own alarm sound rings now, fading up, as it would if Sunrise ever failed to start. Press Stop in the notification to end it.") },
            confirmButton = { TextButton(onClick = {
                testingBackup = false
                runCatching { PlaybackService.backup(context, 0L) }
                snack("Ringing the backup alarm: press Stop in the notification", null) {}
            }) { Text("Ring now") } },
            dismissButton = { TextButton(onClick = { testingBackup = false }) { Text("Cancel") } },
        )
    }
}

/** Settings → Back up & restore: your settings to and from a file. */
@Composable
fun BackupScreen(onRestored: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val snack = rememberSnack()
    var includeToken by remember { mutableStateOf(false) }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) snack(runCatching {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(SettingsBackup.export(SettingsStore.read(context), includeToken).toByteArray()) }
            "Settings saved to the file"
        }.getOrElse { "Couldn't save the file" }, null) {}
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) snack(runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().take(200_000).toByteArray().decodeToString() }
            val restored = SettingsBackup.restore(SettingsStore.read(context), text) ?: return@runCatching "That isn't a Sunrise settings file"
            SettingsStore.write(context, restored.toString())
            Alarms.refresh(context)
            onRestored()
            "Settings restored"
        }.getOrElse { "Couldn't read the file" }, null) {}
    }
    SettingsScaffold("Back up & restore", onBack) {
        Hint("Save your settings to a file, for a new phone or before reinstalling. Restore puts them back.")
        if (MorningSettings.from(SettingsStore.read(context)).todoToken.isNotEmpty()) {
            SwitchRow("Include the Todoist token", "Off: you'll type it in again after restoring", includeToken) { includeToken = it }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            FilledTonalButton(onClick = { exportFile.launch("sunrise-settings.json") }) { Text("Back up") }
            Spacer(Modifier.padding(4.dp))
            OutlinedButton(onClick = { importFile.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Restore") }
        }
    }
}

/** Settings → About: the version, and checking for a new one. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val snack = rememberSnack()
    val main = remember { Handler(Looper.getMainLooper()) }
    var newVersion by remember { mutableIntStateOf(0) }
    SettingsScaffold("About", onBack) {
        ListItem(headlineContent = { Text("Sunrise Alarm") }, supportingContent = { Text("Version ${BuildConfig.VERSION_NAME} · works offline") })
        if (newVersion > 0) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("A new version is ready", style = MaterialTheme.typography.titleMedium)
                    Text("Tap Download, then open the file to install it over the top. Your settings stay.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = {
                        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(UpdateCheck.APK_URL))) }
                    }) { Text("Download update") }
                }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedButton(onClick = {
                snack("Checking…", null) {}
                UpdateCheck.check(context, true) { code -> main.post { newVersion = code } }
                main.postDelayed({ if (newVersion == 0) snack("You have the latest version", null) {} }, 10_000)
            }) { Text("Check for updates") }
        }
        Hint("The app also checks by itself, at most twice a day and never near an alarm.")
    }
}
