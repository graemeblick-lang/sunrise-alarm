package au.sunrise.alarm

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * The alarm settings, read from and written into the settings JSON (same keys and formats the web page uses).
 *
 * Two kinds of alarm, both always automatic (the app opens itself at sunrise):
 *  - the repeating alarm: [time] on the chosen [days] (no days = no repeating alarm);
 *  - a one-off alarm at [once] (epoch ms), e.g. "in 5 minutes", whose sunrise starts no earlier than [onceStart].
 *
 * Days are numbered like the page: 0 = Sunday … 6 = Saturday.
 */
data class AlarmSettings(
    val time: String = "06:30",
    val days: Set<Int> = setOf(1, 2, 3, 4, 5),
    val perDay: Boolean = false,
    val dayTimes: Map<Int, String> = emptyMap(),
    val dur: Int = 30,
    val snooze: Int = 9,
    val pauseUntil: String = "",
    val skip: Long = 0,
    val once: Long = 0,
    val onceStart: Long = 0,
    /** Your state ("VIC"), so repeating alarms don't ring on its public holidays. "" = ring on holidays. */
    val holidayState: String = "",
    /** That state's public holidays, ISO date → name (fetched by [Holidays]). */
    val holidays: Map<String, String> = emptyMap(),
) {
    /** The time used on a given day (0 = Sunday). */
    fun timeFor(day: Int): String =
        if (perDay && days.isNotEmpty()) dayTimes[day] ?: time else time

    /**
     * The next repeating alarm after [from], following the same rules as the page's nextAlarm():
     * per-day times, the skipped alarm and the holiday pause (inclusive). Null when no days are chosen.
     */
    fun nextAlarm(from: ZonedDateTime): ZonedDateTime? {
        if (days.isEmpty()) return null
        val pause = runCatching { if (pauseUntil.isEmpty()) null else LocalDate.parse(pauseUntil) }.getOrNull()
        val startDay = from.toLocalDate()
        for (d in 0..400) {
            val date = startDay.plusDays(d.toLong())
            val dow = date.dayOfWeek.value % 7                 // Monday=1 … Sunday=7 → Sunday=0
            val t = parseTime(timeFor(dow)) ?: continue
            val at = date.atTime(t).atZone(from.zone)
            if (!at.isAfter(from)) continue
            if (skip != 0L && at.toInstant().toEpochMilli() == skip) continue
            if (pause != null && !date.isAfter(pause)) continue
            if (holidayState.isNotEmpty() && holidays.containsKey(date.toString())) continue
            if (dow in days) return at
        }
        return null
    }

    /** A public holiday the repeating alarm skips before its next alarm (date, name), e.g. to say so on the home screen. */
    fun skippedHoliday(from: ZonedDateTime): Pair<LocalDate, String>? {
        if (holidayState.isEmpty() || days.isEmpty()) return null
        val next = nextAlarm(from)?.toLocalDate() ?: return null
        var d = from.toLocalDate()
        while (d.isBefore(next)) {
            val name = holidays[d.toString()]
            val t = parseTime(timeFor(d.dayOfWeek.value % 7))
            if (name != null && d.dayOfWeek.value % 7 in days && t != null && d.atTime(t).atZone(from.zone).isAfter(from)) return d to name
            d = d.plusDays(1)
        }
        return null
    }

    /** The one-off alarm, if it's still to come (and wasn't skipped). */
    fun onceAt(from: ZonedDateTime): ZonedDateTime? =
        once.takeIf { it > from.toInstant().toEpochMilli() && it != skip }
            ?.let { java.time.Instant.ofEpochMilli(it).atZone(from.zone) }

    /** Whichever comes first: the one-off alarm or the next repeating one. */
    fun upcoming(from: ZonedDateTime): ZonedDateTime? {
        val a = nextAlarm(from)
        val o = onceAt(from)
        return if (a == null) o else if (o == null || a.isBefore(o)) a else o
    }

    /** When the sunrise starts for the alarm at [alarmMs]: the sunrise length before it (shorter for a quick one-off). */
    fun sunriseFor(alarmMs: Long): Long {
        val full = alarmMs - dur * 60_000L
        return if (once > 0 && alarmMs == once) maxOf(full, minOf(onceStart, alarmMs)) else full
    }

    /** A one-off alarm at [alarmMs], set at [nowMs] (so a quick alarm's sunrise starts straight away). */
    fun withOnce(alarmMs: Long, nowMs: Long): AlarmSettings = copy(once = alarmMs, onceStart = nowMs, skip = if (skip == alarmMs) 0 else skip)

    /**
     * The next [count] alarms as (sunrise start, alarm) epoch ms, like the page's alarmPlan(): what Android keeps
     * scheduled by itself. The one-off alarm is included in time order.
     */
    fun plan(from: ZonedDateTime, count: Int = 8): List<Pair<Long, Long>> {
        val alarms = ArrayList<Long>()
        onceAt(from)?.let { alarms += it.toInstant().toEpochMilli() }
        var t = from
        while (alarms.size < count + 1) {
            val a = nextAlarm(t) ?: break
            alarms += a.toInstant().toEpochMilli()
            t = a.plusSeconds(1)
        }
        return alarms.distinct().sorted().take(count).map { sunriseFor(it) to it }
    }

    /** The plan as the JSON the alarm scheduler takes: [[sunriseMs, alarmMs], ...]. */
    fun planJson(from: ZonedDateTime): String =
        plan(from).joinToString(",", "[", "]") { (s, a) -> "[$s,$a]" }

    fun into(j: JSONObject) {
        j.put("time", time)
        j.put("days", JSONArray(days.sorted()))
        j.put("perDay", perDay)
        j.put("dayTimes", JSONObject().apply { dayTimes.toSortedMap().forEach { (k, v) -> put(k.toString(), v) } })
        j.put("dur", dur)
        j.put("snooze", snooze)
        j.put("pauseUntil", pauseUntil)
        j.put("skip", skip)
        j.put("once", once)
        j.put("onceStart", onceStart)
        j.put("holidayState", holidayState)
        j.remove("auto")   // alarms are always automatic now
    }

    companion object {
        val SUNRISE_LENGTHS = listOf(15, 20, 30, 45)
        val SNOOZE_LENGTHS = listOf(5, 9, 15)
        /** The quick one-off alarms on the home screen, in minutes from now. */
        val QUICK = listOf(5, 10, 20, 30)

        fun from(j: JSONObject): AlarmSettings {
            val d = AlarmSettings()
            val days = j.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.optInt(it, -1) }.filter { it in 0..6 }.toSet() } ?: d.days
            val dayTimes = j.optJSONObject("dayTimes")?.let { o ->
                o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.takeIf { it in 0..6 }?.let { it to o.optString(k) } }
                    .filter { parseTime(it.second) != null }.toMap()
            } ?: emptyMap()
            return AlarmSettings(
                time = j.optString("time", d.time).takeIf { parseTime(it) != null } ?: d.time,
                days = days,
                perDay = j.optBoolean("perDay", d.perDay),
                dayTimes = dayTimes,
                dur = j.optInt("dur", d.dur),
                snooze = j.optInt("snooze", d.snooze),
                pauseUntil = j.optString("pauseUntil", ""),
                skip = j.optLong("skip", 0),
                once = j.optLong("once", 0),
                onceStart = j.optLong("onceStart", 0),
                holidayState = j.optString("holidayState", "").takeIf { it in Holidays.STATES } ?: "",
                holidays = j.optJSONObject("holidayDates")?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } } ?: emptyMap(),
            )
        }

        fun parseTime(s: String?): LocalTime? = runCatching { LocalTime.parse(s) }.getOrNull()

        fun formatTime(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)
    }
}
