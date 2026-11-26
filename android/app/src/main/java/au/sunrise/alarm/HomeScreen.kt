package au.sunrise.alarm

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The home screen: only tonight's alarm. A status line (ready, or what to fix), the next-alarm card (time, days,
 * Skip, Pause, Edit), quick one-off alarms, Goodnight and Preview. Settings (⚙) and Your week (📈) are in the top bar.
 * Every action confirms itself with a snackbar, with Undo where it makes sense.
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
    val snack = rememberSnack()
    val json = remember(refresh) { SettingsStore.read(context) }
    var alarm by remember(refresh) { mutableStateOf(AlarmSettings.from(json)) }
    fun update(n: AlarmSettings) { alarm = n; save(n) }

    // The "in 6h 12m" text keeps up with the clock
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(refresh) { while (true) { now = ZonedDateTime.now(); delay(30_000) } }

    // Updates: the app checks GitHub Releases (at most twice a day, never near an alarm)
    var newVersion by remember { mutableIntStateOf(0) }
    val main = remember { Handler(Looper.getMainLooper()) }
    LaunchedEffect(Unit) { UpdateCheck.check(context, false) { code -> main.post { newVersion = code } } }

    // What needs fixing before tonight: phone settings, the last rehearsal, an alarm that needed the backup
    val toFix = remember(refresh) { Readiness.issues(context).size }

    var editingTime by remember { mutableStateOf(false) }
    var pickingOnce by remember { mutableStateOf(false) }
    var pickingPause by remember { mutableStateOf(false) }
    val crashFile = remember { java.io.File(context.filesDir, App.CRASH_FILE) }
    var crash by remember(refresh) { mutableStateOf(if (crashFile.exists()) runCatching { crashFile.readText() }.getOrNull() else null) }

    fun timeOf(ms: Long) = HomeText.time(AlarmSettings.formatTime(Instant.ofEpochMilli(ms).atZone(now.zone).toLocalTime()), is24Hour)
    fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(now.zone).format(DateTimeFormatter.ofPattern("EEEE"))

    SettingsScaffold("Sunrise", onBack = null, actions = {
        IconButton(onClick = { onOpen("week") }, modifier = Modifier.semantics { contentDescription = "Your week" }) {
            Text("📈", style = MaterialTheme.typography.titleLarge)
        }
        IconButton(onClick = { onOpen("settings") }, modifier = Modifier.semantics { contentDescription = "Settings" }) {
            Text("⚙", style = MaterialTheme.typography.titleLarge)
        }
    }) {
        // ----- a crash saved last time (App.java): offer to copy it, then forget it -----
        crash?.let { report ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Sunrise hit a problem last time", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text("Tap Copy and paste it to Claude.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    Row {
                        TextButton(onClick = {
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(android.content.ClipData.newPlainText("Sunrise crash", report))
                            snack("Copied", null) {}
                        }) { Text("Copy") }
                        TextButton(onClick = { crashFile.delete(); crash = null }) { Text("Dismiss") }
                    }
                }
            }
        }

        // ----- one status line: ready, or how many things to fix (opens Phone check) -----
        Surface(
            color = if (toFix == 0) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                .clickableRow("open Phone check") { onOpen("checks") },
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (toFix == 0) "✓ Ready for tonight" else "⚠ $toFix thing${if (toFix > 1) "s" else ""} to fix before tonight",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (toFix == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                Text("›", style = MaterialTheme.typography.titleLarge)
            }
        }

        // ----- a new version -----
        if (newVersion > 0) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("A new version is ready", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateCheck.APK_URL))) } }) {
                        Text("Download")
                    }
                }
            }
        }

        // ----- the next alarm: time, days, Skip, Pause, Edit -----
        val nowMs = now.toInstant().toEpochMilli()
        val next = alarm.nextAlarm(now)
        val paused = pauseDate(alarm.pauseUntil)?.takeIf { !it.isBefore(now.toLocalDate()) }
        val onCard = MaterialTheme.colorScheme.onPrimaryContainer
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 6.dp)) {
                Text("NEXT ALARM", style = MaterialTheme.typography.labelLarge, color = onCard)
                val bigTime = if (alarm.perDay && next != null) AlarmSettings.formatTime(next.toLocalTime()) else alarm.time
                Text(
                    HomeText.time(bigTime, is24Hour),
                    style = MaterialTheme.typography.displayLarge,
                    color = if (alarm.days.isEmpty() || paused != null) onCard.copy(alpha = 0.55f) else onCard,
                    modifier = Modifier.clickableRow(if (alarm.perDay) "change each day's time" else "change the alarm time") {
                        if (alarm.perDay) onOpen("perday") else editingTime = true
                    },
                )
                Text(HomeText.next(alarm, now, is24Hour), style = MaterialTheme.typography.bodyLarge, color = onCard)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        alarm.skip > nowMs && alarm.skip != alarm.once -> TextButton(onClick = {
                            val was = alarm.skip
                            update(alarm.copy(skip = 0)); snack("${dayOf(was)}'s alarm is back on", null) {}
                        }) { Text("Undo skip") }
                        next != null -> TextButton(onClick = {
                            val ms = next.toInstant().toEpochMilli()
                            update(alarm.copy(skip = ms))
                            snack("Skipped ${dayOf(ms)}'s alarm", "Undo") { update(alarm.copy(skip = 0)) }
                        }) { Text("Skip next") }
                    }
                    if (paused != null) TextButton(onClick = {
                        val was = alarm.pauseUntil
                        update(alarm.copy(pauseUntil = "")); snack("Alarms are back on", "Undo") { update(alarm.copy(pauseUntil = was)) }
                    }) { Text("Resume") }
                    else if (alarm.days.isNotEmpty()) TextButton(onClick = { pickingPause = true }) { Text("Pause…") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onOpen("alarm") }) { Text("Edit ›") }
                }
            }
        }

        // ----- a one-off alarm, e.g. in 5 minutes -----
        val onceText = HomeText.once(alarm, now, is24Hour)
        if (onceText.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(onceText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val was = alarm
                        update(alarm.copy(once = 0, onceStart = 0)); snack("One-off alarm cancelled", "Undo") { update(was) }
                    }) { Text("Cancel") }
                }
            }
        }
        SectionTitle("Quick alarm")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AlarmSettings.QUICK.forEach { m ->
                AssistChip(
                    onClick = {
                        val t = System.currentTimeMillis()
                        update(alarm.withOnce(t + m * 60_000L, t)); now = ZonedDateTime.now()
                        snack("One-off alarm in $m min, at ${timeOf(t + m * 60_000L)}", null) {}
                    },
                    label = { Text("$m min") },
                    modifier = Modifier.semantics { contentDescription = "One-off alarm in $m minutes" },
                )
            }
            AssistChip(onClick = { pickingOnce = true }, label = { Text("Pick a time…") })
        }

        // ----- tonight -----
        Column(Modifier.padding(horizontal = 16.dp, vertical = 20.dp)) {
            Button(onClick = { onStart("goodnight") }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Goodnight", style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = { onStart("preview") }, modifier = Modifier.fillMaxWidth()) { Text("Preview the sunrise") }
        }
    }

    if (editingTime) {
        TimeDialog(
            title = "Alarm time",
            initial = AlarmSettings.parseTime(alarm.time) ?: LocalTime.of(6, 30),
            is24Hour = is24Hour,
            onDismiss = { editingTime = false },
            onConfirm = { t ->
                update(alarm.copy(time = AlarmSettings.formatTime(t))); editingTime = false
                snack("Alarm set for ${HomeText.time(AlarmSettings.formatTime(t), is24Hour)}", null) {}
            },
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
                snack("One-off alarm at ${timeOf(at.toInstant().toEpochMilli())}", null) {}
            },
        )
    }

    if (pickingPause) {
        PauseDialog(
            initial = pauseDate(alarm.pauseUntil),
            onDismiss = { pickingPause = false },
            onConfirm = { d ->
                update(alarm.copy(pauseUntil = d.toString())); pickingPause = false
                snack("Alarms paused until ${showDate(d)}", "Undo") { update(alarm.copy(pauseUntil = "")) }
            },
        )
    }
}

/** Everything that could stop tonight's alarm working, in plain words (for Home's status line and Phone check). */
object Readiness {
    fun issues(c: android.content.Context): List<String> {
        val out = ArrayList<String>()
        runCatching { PhoneChecks.problems(c) }.getOrDefault(emptyList()).forEach { out += PhoneChecks.describe(it) }
        val log = AlarmLog.entries(c)
        AlarmLog.problem(log, System.currentTimeMillis(), AlarmLog.dismissed(c))?.let { out += "An alarm needed the backup alarm recently." }
        AlarmLog.lastRehearsal(log)?.takeIf { (it.rang > 0 || it.backup > 0) && System.currentTimeMillis() - it.alarm < 7 * 86_400_000L }
            ?.let { e -> AlarmLog.rehearsalReport(e).filter { !it.first }.forEach { out += "Last rehearsal: " + it.second } }
        return out
    }
}
