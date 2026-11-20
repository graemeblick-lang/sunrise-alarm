package au.sunrise.alarm

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The words on the home screen. */
object HomeText {
    private val DAY_SHORT = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    fun time(hhmm: String, is24Hour: Boolean): String {
        val t = AlarmSettings.parseTime(hhmm) ?: return hhmm
        return if (is24Hour) AlarmSettings.formatTime(t)
        else "${if (t.hour % 12 == 0) 12 else t.hour % 12}:%02d %s".format(t.minute, if (t.hour < 12) "am" else "pm")
    }

    private fun paused(s: AlarmSettings, today: LocalDate): LocalDate? =
        runCatching { LocalDate.parse(s.pauseUntil) }.getOrNull()?.takeIf { !it.isBefore(today) }

    /** "in 5 min", "in 14h 5m". */
    fun until(ms: Long, now: ZonedDateTime): String {
        val mins = Math.round((ms - now.toInstant().toEpochMilli()) / 60_000.0).coerceAtLeast(0)
        return if (mins < 60) "in $mins min" else "in ${mins / 60}h ${mins % 60}m"
    }

    private fun dayWord(a: ZonedDateTime, now: ZonedDateTime) = when (a.toLocalDate()) {
        now.toLocalDate() -> "today"
        now.toLocalDate().plusDays(1) -> "tomorrow"
        else -> DAY_SHORT[a.dayOfWeek.value % 7]
    }

    /** The repeating alarm: "Paused until Tue 3 Dec · Alarm Wed at 6:30 am · in 14h 5m", or why there isn't one. */
    fun next(s: AlarmSettings, now: ZonedDateTime, is24Hour: Boolean): String {
        if (s.days.isEmpty()) return "Repeat is off: choose days below"
        val p = paused(s, now.toLocalDate())
        val prefix = if (p != null) "Paused until ${p.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))} · " else ""
        val a = s.nextAlarm(now) ?: return prefix.removeSuffix(" · ")
        val ms = a.toInstant().toEpochMilli()
        return prefix + "Alarm ${dayWord(a, now)} at ${time(AlarmSettings.formatTime(a.toLocalTime()), is24Hour)} · ${until(ms, now)}"
    }

    /** The one-off alarm: "One-off alarm today at 7:12 pm · in 5 min", or "" if there isn't one. */
    fun once(s: AlarmSettings, now: ZonedDateTime, is24Hour: Boolean): String {
        val a = s.onceAt(now) ?: return ""
        return "One-off alarm ${dayWord(a, now)} at ${time(AlarmSettings.formatTime(a.toLocalTime()), is24Hour)} · ${until(a.toInstant().toEpochMilli(), now)}"
    }

    fun days(days: Set<Int>): String = when {
        days.isEmpty() -> "One-off"
        days == (0..6).toSet() -> "Every day"
        days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
        days == setOf(0, 6) -> "Weekends"
        else -> listOf(1, 2, 3, 4, 5, 6, 0).filter { it in days }.joinToString(", ") { DAY_SHORT[it] }
    }

    fun sound(s: SoundSettings, snooze: Int): String {
        val src = when (s.src) {
            "radio" -> "📻 " + (s.stationNames().getOrNull(s.station) ?: "Radio").removeSuffix("…")
            "spotify" -> "Spotify" + if (s.spAppOk && SoundSettings.parseSpotify(s.spLink) != null) " · ${s.spName.ifEmpty { "your playlist" }}" else " · what you last played"
            "apps" -> "🎵 " + s.appName.ifEmpty { "Choose a music app" }
            else -> "Chimes"
        }
        return "$src · fade ${s.fade} min" + (if (s.earlyMin > 0 && s.src != "chimes") " · starts ${s.earlyMin} min early" else "") +
            " · snooze $snooze min"
    }

    private fun themeName(s: LookSettings) = if (s.theme == Themes.PHONE) "Phone colours" else Themes.byKey(s.theme).name

    fun night(s: LookSettings): String = "${themeName(s)} theme · clock brightness ${s.dim} of 6"

    fun sunrise(s: LookSettings, dur: Int): String =
        "$dur min · ${if (s.sunCustom) "own colours" else "theme colours"} · up to ${s.sunMax}% bright"

    fun morning(s: MorningSettings): String = "Weather for ${s.place}" + if (s.todoToken.isNotEmpty()) " · Todoist on" else ""
}
