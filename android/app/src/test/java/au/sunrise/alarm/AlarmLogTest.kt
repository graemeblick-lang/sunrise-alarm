package au.sunrise.alarm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmLogTest {
    private val mel = ZoneId.of("Australia/Melbourne")
    private fun ms(h: Int, mi: Int, d: Int = 23) = ZonedDateTime.of(2026, 11, d, h, mi, 0, 0, mel).toInstant().toEpochMilli()

    @Test fun logRoundTripAndChanges() {
        var log = AlarmLog.change(emptyList(), ms(6, 30)) { it.copy(rang = ms(6, 30)) }
        log = AlarmLog.change(log, ms(6, 30)) { it.copy(snoozes = it.snoozes + 1) }
        log = AlarmLog.change(log, ms(6, 30)) { it.copy(stopped = ms(6, 41)) }
        assertEquals(listOf(LogEntry(ms(6, 30), ms(6, 30), 1, ms(6, 41), 0)), AlarmLog.parse(AlarmLog.serialize(log)))
        assertEquals(emptyList<LogEntry>(), AlarmLog.parse("not json"))
    }

    @Test fun morningLine() {
        assertEquals("Rang at 6:30 am · snoozed twice · up at 6:41 am", AlarmLog.line(LogEntry(ms(6, 30), ms(6, 30), 2, ms(6, 41)), false, mel))
        assertEquals("Backup alarm at 06:31", AlarmLog.line(LogEntry(ms(6, 30), backup = ms(6, 31)), true, mel))
        assertEquals("", AlarmLog.line(LogEntry(ms(6, 30)), false, mel))
    }

    @Test fun problemIsTheLatestBackupUnlessDismissed() {
        val log = listOf(LogEntry(ms(6, 30, 21), backup = ms(6, 31, 21)), LogEntry(ms(6, 30, 22), rang = ms(6, 30, 22)))
        assertEquals(ms(6, 30, 21), AlarmLog.problem(log, ms(9, 0), 0)!!.alarm)
        assertNull(AlarmLog.problem(log, ms(9, 0), ms(6, 30, 21)))
        assertNull(AlarmLog.problem(log, ms(9, 0, 25), 0))          // more than 3 days ago
    }

    @Test fun yourWeek() {
        val log = listOf(
            LogEntry(ms(6, 30, 21), rang = ms(6, 30, 21), snoozes = 1, stopped = ms(6, 45, 21), night = ms(22, 30, 20)),
            LogEntry(ms(6, 30, 22), rang = ms(6, 30, 22), snoozes = 0, stopped = ms(6, 35, 22), night = ms(23, 10, 21)),
            LogEntry(ms(6, 30, 23), night = ms(0, 10, 23)),      // went to bed after midnight; alarm not yet rung
        )
        val now = ms(7, 0, 23)
        assertEquals(listOf("Mon · Goodnight 12:10 am", "Sun · rang 6:30 am · up 6:35 am · Goodnight 11:10 pm",
            "Sat · rang 6:30 am · snoozed once · up 6:45 am · Goodnight 10:30 pm"), AlarmLog.weekRows(log, now, false, mel))
        assertEquals("Up 10 min after the alarm on average · 0.5 snoozes · Goodnight around 11:17 pm, give or take 36 min",
            AlarmLog.weekSummary(log, now, false, mel))
        assertEquals("", AlarmLog.weekSummary(emptyList(), now, false, mel))
    }

    @Test fun backupAndRestore() {
        val s = JSONObject("""{"time":"06:10","theme":"ocean","skip":5,"once":7,"holidayDates":{"2026-01-26":"Australia Day"},"spAppOk":true}""")
        val file = SettingsBackup.export(s)
        val b = JSONObject(file)
        assertEquals(setOf("sunriseBackup", "time", "theme"), b.keys().asSequence().toSet())
        val restored = SettingsBackup.restore(JSONObject("""{"time":"07:00","skip":9,"spAppOk":true}"""), file)!!
        assertEquals("06:10", restored.getString("time")); assertEquals("ocean", restored.getString("theme"))
        assertEquals(9, restored.getInt("skip")); assertEquals(true, restored.getBoolean("spAppOk"))
        assertNull(SettingsBackup.restore(JSONObject(), """{"time":"06:10"}"""))
        assertNull(SettingsBackup.restore(JSONObject(), "hello"))
    }
}
