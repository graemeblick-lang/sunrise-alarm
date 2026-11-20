package au.sunrise.alarm

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One of today's events from the phone's calendar. */
data class CalEvent(val title: String, val begin: Long, val end: Long, val allDay: Boolean)

/** Today's events from the calendars shown in the phone's calendar app (read only, never leaves the phone). */
object CalendarData {
    fun allowed(c: Context) = c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Blocking: call off the main thread. Empty without permission. */
    fun today(c: Context, zone: ZoneId = ZoneId.systemDefault()): List<CalEvent> {
        if (!allowed(c)) return emptyList()
        val day = LocalDate.now(zone)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start); ContentUris.appendId(it, end)
        }.build()
        val cols = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY)
        val out = ArrayList<CalEvent>()
        c.contentResolver.query(uri, cols, "${CalendarContract.Instances.VISIBLE} = 1", null,
            "${CalendarContract.Instances.ALL_DAY} DESC, ${CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
            while (cur.moveToNext()) {
                out += CalEvent(cur.getString(0) ?: "(No title)", cur.getLong(1), cur.getLong(2), cur.getInt(3) == 1)
            }
        }
        return pick(out, System.currentTimeMillis(), day, zone)
    }

    /** All-day events that are today (stored in UTC), then timed ones not yet over, at most [max]. */
    fun pick(events: List<CalEvent>, nowMs: Long, day: LocalDate, zone: ZoneId, max: Int = 5): List<CalEvent> {
        val allDay = events.filter { it.allDay && Instant.ofEpochMilli(it.begin).atZone(ZoneId.of("UTC")).toLocalDate() == day }
        val timed = events.filter { !it.allDay && it.end > nowMs && Instant.ofEpochMilli(it.begin).atZone(zone).toLocalDate() <= day }
            .sortedBy { it.begin }
        return (allDay + timed).take(max)
    }

    /** "All day" or "9:30 am". */
    fun timeText(e: CalEvent, is24Hour: Boolean, zone: ZoneId = ZoneId.systemDefault()): String =
        if (e.allDay) "All day" else HomeText.time(AlarmSettings.formatTime(Instant.ofEpochMilli(e.begin).atZone(zone).toLocalTime()), is24Hour)
}
