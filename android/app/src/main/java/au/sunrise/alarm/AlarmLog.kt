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
            LogEntry(o.optLong("alarm"), o.optLong("rang"), o.optInt("snoozes"), o.optLong("stopped"), o.optLong("backup"))
        }
    }.getOrDefault(emptyList())

    fun serialize(list: List<LogEntry>): String = JSONArray(list.takeLast(KEEP).map {
        JSONObject().put("alarm", it.alarm).put("rang", it.rang).put("snoozes", it.snoozes).put("stopped", it.stopped).put("backup", it.backup)
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

    fun export(settings: JSONObject): String {
        val out = JSONObject().put("sunriseBackup", 1)
        settings.keys().forEach { k -> if (k !in SKIP) out.put(k, settings.get(k)) }
        return out.toString(2)
    }

    /** The settings with the backup's applied, or null if it isn't a Sunrise backup. */
    fun restore(current: JSONObject, backup: String): JSONObject? {
        val b = runCatching { JSONObject(backup) }.getOrNull() ?: return null
        if (b.optInt("sunriseBackup", 0) != 1) return null
        val out = JSONObject(current.toString())
        b.keys().forEach { k -> if (k !in SKIP && k != "sunriseBackup") out.put(k, b.get(k)) }
        return out
    }
}
