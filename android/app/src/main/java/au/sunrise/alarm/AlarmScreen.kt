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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
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

/**
 * The Alarm screen: everything about when and how the alarm rings, in the order you think about it.
 *  - When: time, days, public holidays, pause, different time each day.
 *  - Waking up: sound (its own screen), sunrise length, snooze, extra alarms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmScreen(
    initial: AlarmSettings, soundSummary: String, is24Hour: Boolean,
    onChange: (AlarmSettings) -> Unit, onOpen: (String) -> Unit, onBack: () -> Unit,
) {
    var s by remember { mutableStateOf(initial) }
    fun update(n: AlarmSettings) { s = n; onChange(n) }
    val snack = rememberSnack()
    val context = LocalContext.current
    var editingTime by remember { mutableStateOf(false) }
    var pickingPause by remember { mutableStateOf(false) }
    var pickingHolidays by remember { mutableStateOf(false) }

    SettingsScaffold("Alarm", onBack) {
        NextAlarmCard(s, is24Hour)

        // ----- when -----
        SectionTitle("When")
        ListItem(
            headlineContent = { Text("Time") },
            supportingContent = { Text(if (s.perDay) "Days without their own time" else repeatSummary(s.days)) },
            trailingContent = { Text(showTime(s.time, is24Hour), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickableRow("change the alarm time") { editingTime = true },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
        val coming = Holidays.upcoming(s, LocalDate.now(), 1).firstOrNull()
        ValueRow("Public holidays", when {
            s.holidayState.isEmpty() -> "Rings on public holidays"
            coming != null -> "No alarm on ${s.holidayState} holidays · next: ${coming.first.format(DateTimeFormatter.ofPattern("EEE d MMM"))}, ${coming.second}"
            else -> "No alarm on ${s.holidayState} holidays (getting the dates…)"
        }) { pickingHolidays = true }
        val paused = pauseDate(s.pauseUntil)?.takeIf { !it.isBefore(LocalDate.now()) }
        ValueRow("Pause for a holiday", if (paused != null) "Paused until ${showDate(paused)}" else "Not paused") { pickingPause = true }
        if (paused != null) Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = {
                val was = s.pauseUntil
                update(s.copy(pauseUntil = "")); snack("Alarms are back on", "Undo") { update(s.copy(pauseUntil = was)) }
            }) { Text("Resume now") }
        }
        ValueRow("Different time each day", if (s.perDay) WEEK_ORDER.filter { it in s.days }.joinToString(", ") {
            DAY_NAMES[it].take(3) + " " + showTime(s.timeFor(it), is24Hour) } else "Off") { onOpen("perday") }

        // ----- waking up -----
        SectionTitle("Waking up")
        ValueRow("Sound", soundSummary, icon = "🔊") { onOpen("sound") }
        ControlLabel("🌅  Sunrise", "Starts this long before the alarm")
        Choice(AlarmSettings.SUNRISE_LENGTHS, s.dur, { "$it min" }) { update(s.copy(dur = it)) }
        ControlLabel("😴  Snooze")
        Choice(AlarmSettings.SNOOZE_LENGTHS, s.snooze, { "$it min" }) { update(s.copy(snooze = it)) }
        ControlLabel("🔁  Extra alarms", "Rings again in case you fall back asleep")
        Choice(listOf(0, 1, 2, 3), s.extra, { if (it == 0) "Off" else "+$it" }) { update(s.copy(extra = it)) }
        if (s.extra > 0) {
            ControlLabel("Every")
            Choice(AlarmSettings.EXTRA_GAPS, s.extraGap, { "$it min" }) { update(s.copy(extraGap = it)) }
            Hint("After each alarm it rings ${s.extra} more time${if (s.extra > 1) "s" else ""}, even if you turned it off. " +
                "Once you're up, tap \"cancel the extra alarms\" on the morning screen.")
        }
        Spacer(Modifier.height(16.dp))
    }

    if (editingTime) {
        TimeDialog(
            title = "Alarm time",
            initial = AlarmSettings.parseTime(s.time) ?: LocalTime.of(6, 30),
            is24Hour = is24Hour,
            onDismiss = { editingTime = false },
            onConfirm = { t ->
                update(s.copy(time = AlarmSettings.formatTime(t))); editingTime = false
                snack("Alarm set for ${showTime(AlarmSettings.formatTime(t), is24Hour)}", null) {}
            },
        )
    }

    if (pickingPause) {
        PauseDialog(
            initial = pauseDate(s.pauseUntil),
            onDismiss = { pickingPause = false },
            onConfirm = { d ->
                update(s.copy(pauseUntil = d.toString())); pickingPause = false
                snack("Alarms paused until ${showDate(d)}", "Undo") { update(s.copy(pauseUntil = "")) }
            },
        )
    }

    if (pickingHolidays) {
        AlertDialog(
            onDismissRequest = { pickingHolidays = false },
            title = { Text("Public holidays") },
            text = {
                Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                    (listOf("") + Holidays.STATES).forEach { st ->
                        val selected = s.holidayState == st
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton) {
                                update(s.copy(holidayState = st)); pickingHolidays = false
                                if (st.isNotEmpty()) {
                                    Holidays.refresh(context, force = true)
                                    snack("No alarm on $st public holidays", null) {}
                                } else snack("Rings on public holidays", null) {}
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(if (st.isEmpty()) "Ring anyway" else "Skip $st holidays")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingHolidays = false }) { Text("Close") } },
        )
    }
}

/** Different time each day: a switch, then each alarm day's own time. */
@Composable
fun PerDayScreen(initial: AlarmSettings, is24Hour: Boolean, onChange: (AlarmSettings) -> Unit, onBack: () -> Unit) {
    var s by remember { mutableStateOf(initial) }
    fun update(n: AlarmSettings) { s = n; onChange(n) }
    var editing by remember { mutableStateOf<Int?>(null) }
    SettingsScaffold("Different time each day", onBack) {
        SwitchRow(
            title = "Different time each day",
            subtitle = if (s.days.isEmpty()) "Choose the alarm days first" else null,
            checked = s.perDay,
            enabled = s.days.isNotEmpty(),
            onCheckedChange = { on ->
                // Start each day at the main time
                val times = if (on) s.days.associateWith { s.dayTimes[it] ?: s.time } else s.dayTimes
                update(s.copy(perDay = on, dayTimes = times))
            },
        )
        if (s.perDay) WEEK_ORDER.filter { it in s.days }.forEach { day ->
            ListItem(
                headlineContent = { Text(DAY_NAMES[day]) },
                trailingContent = { Text(showTime(s.timeFor(day), is24Hour), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickableRow("change ${DAY_NAMES[day]} time") { editing = day },
            )
        }
        Hint("Days without their own time use the main alarm time.")
    }
    editing?.let { day ->
        TimeDialog(
            title = DAY_NAMES[day],
            initial = AlarmSettings.parseTime(s.timeFor(day)) ?: LocalTime.of(6, 30),
            is24Hour = is24Hour,
            onDismiss = { editing = null },
            onConfirm = { t -> update(s.copy(dayTimes = s.dayTimes + (day to AlarmSettings.formatTime(t)))); editing = null },
        )
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

// ---------- Your week: the wake-up log ----------

@Composable
fun WeekScreen(entries: List<LogEntry>, is24Hour: Boolean, onBack: () -> Unit) {
    val now = System.currentTimeMillis()
    val rows = AlarmLog.weekRows(entries, now, is24Hour)
    val summary = AlarmLog.weekSummary(entries, now, is24Hour)
    SettingsScaffold("Your week", onBack) {
        if (summary.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text(summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(20.dp))
            }
        }
        SectionTitle("The last 7 days")
        if (rows.isEmpty()) Hint("Nothing yet. After a few alarms (and Goodnights), they show up here.")
        rows.forEach { ListItem(headlineContent = { Text(it) }) }
        Hint("Kept only on this phone.")
    }
}
