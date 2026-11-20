package au.sunrise.alarm

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The words on the home screen, matching the page (nextText, describeAlarm, daysText, paintSummaries). */
object HomeText {
    private val DAY_SHORT = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    fun time(hhmm: String, is24Hour: Boolean): String {
        val t = AlarmSettings.parseTime(hhmm) ?: return hhmm
        return if (is24Hour) AlarmSettings.formatTime(t)
        else "${if (t.hour % 12 == 0) 12 else t.hour % 12}:%02d %s".format(t.minute, if (t.hour < 12) "am" else "pm")
    }

    private fun paused(s: AlarmSettings, today: LocalDate): LocalDate? =
        runCatching { LocalDate.parse(s.pauseUntil) }.getOrNull()?.takeIf { !it.isBefore(today) }

    /** "Paused until Tue 3 Dec · One-off · Alarm tomorrow at 6:30 am · in 14h 5m" */
    fun next(s: AlarmSettings, now: ZonedDateTime, is24Hour: Boolean): String {
        val p = paused(s, now.toLocalDate())
        val prefix = (if (p != null) "Paused until ${p.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))} · " else "") +
            (if (s.days.isEmpty()) "One-off · " else "")
        val a = s.nextAlarm(now) ?: return prefix.removeSuffix(" · ")
        val mins = Math.round((a.toInstant().toEpochMilli() - now.toInstant().toEpochMilli()) / 60_000.0)
        val day = when (a.toLocalDate()) {
            now.toLocalDate() -> "today"
            now.toLocalDate().plusDays(1) -> "tomorrow"
            else -> DAY_SHORT[a.dayOfWeek.value % 7]
        }
        return prefix + "Alarm $day at ${time(AlarmSettings.formatTime(a.toLocalTime()), is24Hour)} · in ${mins / 60}h ${mins % 60}m"
    }

    fun days(days: Set<Int>): String = when {
        days.isEmpty() -> "One-off"
        days == (0..6).toSet() -> "Every day"
        days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
        days == setOf(0, 6) -> "Weekends"
        else -> listOf(1, 2, 3, 4, 5, 6, 0).filter { it in days }.joinToString(", ") { DAY_SHORT[it] }
    }

    fun alarm(s: AlarmSettings, today: LocalDate): String =
        (if (paused(s, today) != null) "Paused · " else "") + days(s.days) + (if (s.perDay) " (own times)" else "") +
            " · sunrise ${s.dur} min · snooze ${s.snooze} min"

    fun sound(s: SoundSettings): String {
        val src = when (s.src) {
            "radio" -> "📻 " + (s.stationNames().getOrNull(s.station) ?: "Radio").removeSuffix("…")
            "spotify" -> "Spotify" + if (s.spAppOk && SoundSettings.parseSpotify(s.spLink) != null) " · ${s.spName.ifEmpty { "your playlist" }}" else " · what you last played"
            "apps" -> "🎵 " + s.appName.ifEmpty { "Choose a music app" }
            else -> "Chimes"
        }
        return "$src · fade ${s.fade} min" + if (s.earlyMin > 0 && s.src != "chimes") " · starts ${s.earlyMin} min early" else ""
    }

    fun look(s: LookSettings): String {
        val theme = if (s.theme == Themes.PHONE) "Phone colours" else Themes.byKey(s.theme).name
        return "$theme theme · ${if (s.sunCustom) "own sunrise colours" else "theme sunrise"} · ${s.sunMax}%"
    }

    fun morning(s: MorningSettings): String = "Weather for ${s.place}" + if (s.todoToken.isNotEmpty()) " · Todoist on" else ""
}
