package au.sunrise.alarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** What happened with one alarm. Times are epoch ms (0 = didn't happen). */
data class LogEntry(
    val alarm: Long,
    val rang: Long = 0,
    val snoozes: Int = 0,
    val stopped: Long = 0,
    /** The backup alarm had to ring (Sunrise didn't start ringing by itself). */
    val backup: Long = 0,
    /** When the night screen started (Goodnight) for this alarm. */
    val night: Long = 0,
    /** When Sunrise opened by itself for it (the sunrise starting), and whether the phone was locked then. */
    val opened: Long = 0,
    val locked: Boolean = false,
    /** The sound it should have played ("spotify", "radio", "apps", "chimes") and what actually played. */
    val wanted: String = "",
    val played: String = "",
    /** A rehearsal (Home → Rehearse the alarm), not a real alarm. */
    val rehearsal: Boolean = false,
)

/**
 * The wake-up log: when each alarm rang, how often it was snoozed, when you got up, and whether the backup
 * alarm had to step in. Shown on the morning screen, and on the home screen if an alarm needed the backup.
 */
object AlarmLog {
    private const val PREFS = "alarm-log"
    private const val KEEP = 30

    fun parse(json: String?): List<LogEntry> = runCatching {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            LogEntry(o.optLong("alarm"), o.optLong("rang"), o.optInt("snoozes"), o.optLong("stopped"), o.optLong("backup"), o.optLong("night"),
                o.optLong("opened"), o.optBoolean("locked"), o.optString("wanted"), o.optString("played"), o.optBoolean("rehearsal"))
        }
    }.getOrDefault(emptyList())

    fun serialize(list: List<LogEntry>): String = JSONArray(list.takeLast(KEEP).map {
        JSONObject().put("alarm", it.alarm).put("rang", it.rang).put("snoozes", it.snoozes).put("stopped", it.stopped).put("backup", it.backup).put("night", it.night)
            .put("opened", it.opened).put("locked", it.locked).put("wanted", it.wanted).put("played", it.played).put("rehearsal", it.rehearsal)
    }).toString()

    /** Change (or add) the entry for [alarm]. */
    fun change(list: List<LogEntry>, alarm: Long, f: (LogEntry) -> LogEntry): List<LogEntry> {
        val i = list.indexOfFirst { it.alarm == alarm }
        return if (i >= 0) list.toMutableList().also { it[i] = f(it[i]) } else list + f(LogEntry(alarm))
    }

    private fun edit(c: Context, alarm: Long, f: (LogEntry) -> LogEntry) {
        if (alarm <= 0) return
        val p = c.getSharedPreferences(PREFS, 0)
        p.edit().putString("entries", serialize(change(parse(p.getString("entries", null)), alarm, f))).apply()
    }

    fun entries(c: Context): List<LogEntry> = parse(c.getSharedPreferences(PREFS, 0).getString("entries", null))

    @JvmStatic fun rang(c: Context, alarm: Long, at: Long) = edit(c, alarm) { if (it.rang == 0L) it.copy(rang = at) else it }
    @JvmStatic fun snoozed(c: Context, alarm: Long) = edit(c, alarm) { it.copy(snoozes = it.snoozes + 1) }
    @JvmStatic fun stopped(c: Context, alarm: Long, at: Long) = edit(c, alarm) { if (it.rang > 0 && it.stopped == 0L) it.copy(stopped = at) else it }
    @JvmStatic fun opened(c: Context, alarm: Long, at: Long, locked: Boolean) = edit(c, alarm) { if (it.opened == 0L) it.copy(opened = at, locked = locked) else it }
    @JvmStatic fun sound(c: Context, alarm: Long, wanted: String, played: String) = edit(c, alarm) { it.copy(wanted = wanted, played = played) }
    fun rehearsal(c: Context, alarm: Long) = edit(c, alarm) { it.copy(rehearsal = true) }
    @JvmStatic fun night(c: Context, alarm: Long, at: Long) = edit(c, alarm) { if (it.night == 0L) it.copy(night = at) else it }
    @JvmStatic fun backup(c: Context, alarm: Long, at: Long) = edit(c, alarm) { if (it.backup == 0L) it.copy(backup = at) else it }

    /** The morning screen's line: "Rang at 6:30 · snoozed twice · up at 6:41". */
    fun line(e: LogEntry, is24Hour: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        if (e.rang == 0L && e.backup == 0L) return ""
        fun t(ms: Long) = HomeText.time(AlarmSettings.formatTime(Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()), is24Hour)
        val parts = ArrayList<String>()
        if (e.rang > 0) parts += "Rang at ${t(e.rang)}"
        if (e.backup > 0) parts += "backup alarm at ${t(e.backup)}"
        when (e.snoozes) { 0 -> {}; 1 -> parts += "snoozed once"; 2 -> parts += "snoozed twice"; else -> parts += "snoozed ${e.snoozes} times" }
        if (e.stopped > 0) parts += "up at ${t(e.stopped)}"
        return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
    }

    /** The Goodnight time for [e], if it's a real bedtime (older versions logged the sunrise opening by itself). */
    private fun bedtime(e: LogEntry): Long = e.night.takeIf { it > 0 && e.alarm - it > 60 * 60_000L } ?: 0L

    /** The last 7 days, newest first: one line per alarm ("Mon · rang 6:30 am · up 6:41 am · Goodnight 10:42 pm"). */
    fun weekRows(list: List<LogEntry>, nowMs: Long, is24Hour: Boolean, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        fun t(ms: Long) = HomeText.time(AlarmSettings.formatTime(Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()), is24Hour)
        return list.filter { !it.rehearsal && nowMs - it.alarm in 0..7 * 86_400_000L && (it.rang > 0 || it.backup > 0 || bedtime(it) > 0) }
            .sortedByDescending { it.alarm }
            .map { e ->
                val day = Instant.ofEpochMilli(e.alarm).atZone(zone).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
                val parts = mutableListOf(day)
                if (e.rang > 0) parts += "rang ${t(e.rang)}"
                if (e.backup > 0) parts += "backup alarm"
                if (e.snoozes > 0) parts += if (e.snoozes == 1) "snoozed once" else "snoozed ${e.snoozes}×"
                if (e.stopped > 0) parts += "up ${t(e.stopped)}"
                if (bedtime(e) > 0) parts += "Goodnight ${t(bedtime(e))}"
                parts.joinToString(" · ")
            }
    }

    /** "Up 9 min after the alarm on average · 0.7 snoozes · Goodnight around 10:40 pm, give or take 25 min", or "". */
    fun weekSummary(list: List<LogEntry>, nowMs: Long, is24Hour: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        val week = list.filter { !it.rehearsal && nowMs - it.alarm in 0..7 * 86_400_000L }
        val parts = ArrayList<String>()
        val up = week.filter { it.rang > 0 && it.stopped > 0 }
        if (up.isNotEmpty()) {
            parts += "Up ${Math.round(up.map { (it.stopped - it.alarm) / 60_000.0 }.average().coerceAtLeast(0.0))} min after the alarm on average"
            parts += "%.1f snoozes".format(java.util.Locale.ENGLISH, up.map { it.snoozes }.average())
        }
        val nights = week.filter { bedtime(it) > 0 }.map {
            val lt = Instant.ofEpochMilli(bedtime(it)).atZone(zone).toLocalTime()
            ((lt.hour * 60 + lt.minute) - 720 + 1440) % 1440            // minutes after midday, so midnight doesn't split them
        }
        if (nights.size >= 2) {
            val avg = nights.average()
            val spread = Math.round(nights.map { kotlin.math.abs(it - avg) }.average())
            val m = (Math.round(avg).toInt() + 720) % 1440
            parts += "Goodnight around ${HomeText.time(AlarmSettings.formatTime(java.time.LocalTime.of(m / 60, m % 60)), is24Hour)}" +
                (if (spread >= 5) ", give or take $spread min" else "")
        }
        return parts.joinToString(" · ")
    }

    /** How a rehearsal went: (passed, what) for each step. */
    fun rehearsalReport(e: LogEntry): List<Pair<Boolean, String>> {
        val names = mapOf("spotify" to "Spotify", "radio" to "the radio", "apps" to "your music app", "app" to "your music app", "chimes" to "the chimes")
        val out = ArrayList<Pair<Boolean, String>>()
        out += if (e.opened > 0) true to ("Opened by itself" + if (e.locked) ", over the lock screen" else "")
               else false to "Didn't open by itself at sunrise. Fix anything under Phone check (full-screen notifications, battery saving)."
        out += when {
            e.rang == 0L -> false to "Didn't ring"
            e.rang - e.alarm > 90_000 -> false to "Rang late (${(e.rang - e.alarm) / 60_000} min)"
            else -> true to "Rang on time"
        }
        if (e.wanted.isNotEmpty()) {
            val wanted = if (e.wanted == "apps") "app" else e.wanted
            out += when {
                e.played.isEmpty() -> false to "No sound was playing"
                e.played == wanted || (wanted == "chimes" && e.played == "chimes") -> true to "Played ${names[e.played] ?: e.played}"
                else -> false to "Played ${names[e.played] ?: e.played} instead of ${names[wanted] ?: wanted}"
            }
        }
        out += if (e.backup > 0) false to "The backup alarm had to ring" else true to "The backup alarm wasn't needed"
        return out
    }

    /** The latest rehearsal, if any. */
    fun lastRehearsal(list: List<LogEntry>): LogEntry? = list.filter { it.rehearsal }.maxByOrNull { it.alarm }

    /** The latest alarm in the last 3 days that needed the backup alarm, unless already dismissed. */
    fun problem(list: List<LogEntry>, nowMs: Long, dismissed: Long): LogEntry? =
        list.filter { it.backup > 0 && it.alarm > dismissed && nowMs - it.alarm < 3 * 86_400_000L }.maxByOrNull { it.alarm }

    fun dismissed(c: Context) = c.getSharedPreferences(PREFS, 0).getLong("dismissed", 0)
    fun dismiss(c: Context, alarm: Long) = c.getSharedPreferences(PREFS, 0).edit().putLong("dismissed", alarm).apply()
}

/** Back up the settings to a file and restore them (e.g. on a new phone). */
object SettingsBackup {
    /** Belong to this phone or this moment, so they're not backed up. */
    private val SKIP = setOf("skip", "handled", "once", "onceStart", "holidayDates", "spAppOk", "spClient")

    /** The backup file. The Todoist token only if [withToken] (decrypted, since the phone's key can't move). */
    fun export(settings: JSONObject, withToken: Boolean = false): String {
        val out = JSONObject().put("sunriseBackup", 1)
        settings.keys().forEach { k -> if (k !in SKIP && k != "todoToken") out.put(k, settings.get(k)) }
        if (withToken) MorningSettings.from(settings).todoToken.takeIf { it.isNotEmpty() }?.let { out.put("todoToken", it) }
        return out.toString(2)
    }

    /** The settings with the backup's applied, or null if it isn't a Sunrise backup. */
    fun restore(current: JSONObject, backup: String): JSONObject? {
        val b = runCatching { JSONObject(backup) }.getOrNull() ?: return null
        if (b.optInt("sunriseBackup", 0) != 1) return null
        val out = JSONObject(current.toString())
        b.keys().forEach { k -> if (k !in SKIP && k != "sunriseBackup") out.put(k, b.get(k)) }
        MorningSettings.from(out).into(out)          // encrypt a restored token again
        return out
    }
}
