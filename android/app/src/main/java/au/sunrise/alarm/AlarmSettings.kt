package au.sunrise.alarm

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * The "Alarm & schedule" settings, read from and written into the shared settings JSON
 * (same keys and formats the web page uses).
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
    val auto: Boolean = true,
    val skip: Long = 0,
) {
    /** The time used on a given day (0 = Sunday). */
    fun timeFor(day: Int): String =
        if (perDay && days.isNotEmpty()) dayTimes[day] ?: time else time

    /**
     * The next alarm after [from], following the same rules as the page's nextAlarm():
     * per-day times, the skipped alarm, the holiday pause (inclusive) and no days = one-off.
     */
    fun nextAlarm(from: ZonedDateTime): ZonedDateTime? {
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
            if (days.isEmpty() || dow in days) return at
        }
        return null
    }

    /**
     * The next [count] alarms as (sunrise start, alarm) epoch ms, like the page's alarmPlan(): what Android keeps
     * scheduled by itself. Empty if "Wake me automatically" is off.
     */
    fun plan(from: ZonedDateTime, count: Int = 8): List<Pair<Long, Long>> {
        if (!auto) return emptyList()
        val out = ArrayList<Pair<Long, Long>>()
        var t = from
        while (out.size < count) {
            val a = nextAlarm(t) ?: break
            val ms = a.toInstant().toEpochMilli()
            out += (ms - dur * 60_000L) to ms
            if (days.isEmpty()) break                 // one-off alarm
            t = a.plusSeconds(1)
        }
        return out
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
        j.put("auto", auto)
    }

    companion object {
        val SUNRISE_LENGTHS = listOf(15, 20, 30, 45)
        val SNOOZE_LENGTHS = listOf(5, 9, 15)

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
                auto = j.optBoolean("auto", d.auto),
                skip = j.optLong("skip", 0),
            )
        }

        fun parseTime(s: String?): LocalTime? = runCatching { LocalTime.parse(s) }.getOrNull()

        fun formatTime(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)
    }
}
