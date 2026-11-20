package au.sunrise.alarm

import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle


// ---------- Schedule: the repeating alarm in detail ----------

internal val DAY_LETTERS = listOf("S", "M", "T", "W", "T", "F", "S")
internal val DAY_NAMES = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
/** Monday first, the way most people read a week (the page numbers Sunday as 0). */
internal val WEEK_ORDER = listOf(1, 2, 3, 4, 5, 6, 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmScreen(initial: AlarmSettings, is24Hour: Boolean, onChange: (AlarmSettings) -> Unit, onBack: () -> Unit) {
    var s by remember { mutableStateOf(initial) }
    fun update(n: AlarmSettings) { s = n; onChange(n) }

    // Which time is being edited: -1 = the main alarm time, 0..6 = that day's own time, null = none
    var editingTime by remember { mutableStateOf<Int?>(null) }
    var pickingPause by remember { mutableStateOf(false) }

    SettingsScaffold("Schedule", onBack) {
            NextAlarmCard(s, is24Hour)

            ListItem(
                headlineContent = { Text("Alarm time") },
                supportingContent = { Text(if (s.perDay) "Used for days without their own time" else "Every alarm day") },
                trailingContent = { Text(showTime(s.time, is24Hour), style = MaterialTheme.typography.titleLarge) },
                modifier = Modifier.clickableRow("change the alarm time") { editingTime = -1 },
            )

            SectionTitle("Repeat")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                WEEK_ORDER.forEach { day ->
                    val on = day in s.days
                    FilterChip(
                        selected = on,
                        onClick = { update(s.copy(days = if (on) s.days - day else s.days + day)) },
                        label = { Text(DAY_LETTERS[day]) },
                        modifier = Modifier.weight(1f).semantics { contentDescription = DAY_NAMES[day] },
                    )
                }
            }
            Text(
                repeatSummary(s.days),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            SwitchRow(
                title = "Different time each day",
                subtitle = null,
                checked = s.perDay,
                enabled = s.days.isNotEmpty(),
                onCheckedChange = { on ->
                    // Start each day at the main time, as the page does
                    val times = if (on) s.days.associateWith { s.dayTimes[it] ?: s.time } else s.dayTimes
                    update(s.copy(perDay = on, dayTimes = times))
                },
            )
            if (s.perDay && s.days.isNotEmpty()) {
                WEEK_ORDER.filter { it in s.days }.forEach { day ->
                    ListItem(
                        headlineContent = { Text(DAY_NAMES[day]) },
                        trailingContent = { Text(showTime(s.timeFor(day), is24Hour), style = MaterialTheme.typography.titleMedium) },
                        modifier = Modifier.clickableRow("change ${DAY_NAMES[day]} time") { editingTime = day }.padding(start = 16.dp),
                    )
                }
            }

            SectionTitle("Pause alarms")
            val paused = pauseDate(s.pauseUntil)?.takeIf { !it.isBefore(LocalDate.now()) }
            ListItem(
                headlineContent = { Text(if (paused != null) "Paused until ${showDate(paused)}" else "Not paused") },
                supportingContent = { Text("For holidays. Alarms start again the day after.") },
                modifier = Modifier.clickableRow("choose the last day of the pause") { pickingPause = true },
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { update(s.copy(pauseUntil = LocalDate.now().plusDays(1).toString())) }, label = { Text("Tomorrow") })
                AssistChip(onClick = { update(s.copy(pauseUntil = LocalDate.now().plusDays(7).toString())) }, label = { Text("1 week") })
                if (paused != null) AssistChip(onClick = { update(s.copy(pauseUntil = "")) }, label = { Text("Clear") })
            }

            SectionTitle("Public holidays")
            val context = LocalContext.current
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (listOf("") + Holidays.STATES).forEach { st ->
                    FilterChip(
                        selected = s.holidayState == st,
                        onClick = { update(s.copy(holidayState = st)); if (st.isNotEmpty()) Holidays.refresh(context, force = true) },
                        label = { Text(if (st.isEmpty()) "Ring anyway" else st) },
                    )
                }
            }
            val coming = Holidays.upcoming(s, LocalDate.now())
            Hint(when {
                s.holidayState.isEmpty() -> "Choose your state and weekday alarms won't ring on its public holidays."
                coming.isEmpty() -> "No alarms on ${s.holidayState} public holidays. Getting the dates (needs the internet once)…"
                else -> "No alarms on ${s.holidayState} public holidays. Next: " +
                    coming.joinToString(", ") { (d, n) -> d.format(DateTimeFormatter.ofPattern("EEE d MMM")) + " " + n }
            })
    }

    editingTime?.let { which ->
        val current = AlarmSettings.parseTime(if (which < 0) s.time else s.timeFor(which)) ?: LocalTime.of(6, 30)
        TimeDialog(
            title = if (which < 0) "Alarm time" else DAY_NAMES[which],
            initial = current,
            is24Hour = is24Hour,
            onDismiss = { editingTime = null },
            onConfirm = { t ->
                val v = AlarmSettings.formatTime(t)
                update(if (which < 0) s.copy(time = v) else s.copy(dayTimes = s.dayTimes + (which to v)))
                editingTime = null
            },
        )
    }

    if (pickingPause) {
        PauseDialog(
            initial = pauseDate(s.pauseUntil),
            onDismiss = { pickingPause = false },
            onConfirm = { d -> update(s.copy(pauseUntil = d.toString())); pickingPause = false },
        )
    }
}

@Composable
private fun NextAlarmCard(s: AlarmSettings, is24Hour: Boolean) {
    val next = s.nextAlarm(ZonedDateTime.now())
    val text = when {
        next == null -> "No repeating alarm"
        else -> {
            val now = LocalDate.now()
            val day = when (next.toLocalDate()) {
                now -> "Today"
                now.plusDays(1) -> "Tomorrow"
                else -> next.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))
            }
            "$day at ${showTime(AlarmSettings.formatTime(next.toLocalTime()), is24Hour)}"
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Next alarm", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(4.dp))
            Text(text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (next != null) {
                Text(
                    "Sunrise starts ${s.dur} min before",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeDialog(title: String, initial: LocalTime, is24Hour: Boolean, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = is24Hour)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PauseDialog(initial: LocalDate?, onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: today.plusDays(1)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = !utcDate(utcTimeMillis).isBefore(today)
            override fun isSelectableYear(year: Int) = year >= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { onConfirm(utcDate(it)) } ?: onDismiss() }) { Text("Pause") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state, title = { Text("Pause alarms until", Modifier.padding(PaddingValues(start = 24.dp, top = 16.dp))) })
    }
}

// ---------- helpers ----------

private fun utcDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

internal fun pauseDate(s: String): LocalDate? = runCatching { if (s.isEmpty()) null else LocalDate.parse(s) }.getOrNull()

private fun showTime(hhmm: String, is24Hour: Boolean): String {
    val t = AlarmSettings.parseTime(hhmm) ?: return hhmm
    return if (is24Hour) AlarmSettings.formatTime(t)
    else "${if (t.hour % 12 == 0) 12 else t.hour % 12}:%02d %s".format(t.minute, if (t.hour < 12) "am" else "pm")
}

internal fun showDate(d: LocalDate): String = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

private fun repeatSummary(days: Set<Int>): String = when {
    days.isEmpty() -> "Off (no days chosen). For a one-off alarm, use Quick alarm on the home screen."
    days.size == 7 -> "Every day"
    days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
    days == setOf(0, 6) -> "Weekends"
    else -> WEEK_ORDER.filter { it in days }.joinToString(", ") { DAY_NAMES[it].take(3) }
}
