package au.sunrise.alarm

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.format.DateFormat
import android.widget.RemoteViews
import java.time.ZonedDateTime

/** The home-screen widget and the Quick Settings tile: the next alarm, Goodnight, and Skip next. */
object Widgets {
    const val ACTION_SKIP = "au.sunrise.alarm.WIDGET_SKIP"

    /** What they show (unit-tested): the time, a line under it, and the skip button ("" = no button). */
    data class Texts(val time: String, val line: String, val skip: String)

    fun texts(s: AlarmSettings, now: ZonedDateTime, is24Hour: Boolean): Texts {
        val nowMs = now.toInstant().toEpochMilli()
        val skip = when {
            s.skip > nowMs && s.skip != s.once -> "Undo skip"
            s.nextAlarm(now) != null -> "Skip next"
            else -> ""
        }
        val a = s.upcoming(now) ?: return Texts("––:––", if (s.days.isEmpty()) "No alarm set" else "Alarms paused", skip)
        val day = when (a.toLocalDate()) {
            now.toLocalDate() -> "Today"
            now.toLocalDate().plusDays(1) -> "Tomorrow"
            else -> a.format(java.time.format.DateTimeFormatter.ofPattern("EEEE"))
        }
        val kind = if (a.toInstant().toEpochMilli() == s.once) "One-off · " else ""
        val holiday = s.skippedHoliday(now)?.let { " · not on ${it.second}" } ?: ""
        val skipped = if (skip == "Undo skip") " · one skipped" else ""
        return Texts(HomeText.time(AlarmSettings.formatTime(a.toLocalTime()), is24Hour), kind + day + skipped + holiday, skip)
    }

    /** Skip the next repeating alarm, or undo the skip. */
    fun toggleSkip(c: Context) {
        val s = AlarmSettings.from(SettingsStore.read(c))
        val now = ZonedDateTime.now()
        val n = if (s.skip > now.toInstant().toEpochMilli() && s.skip != s.once) s.copy(skip = 0)
                else s.nextAlarm(now)?.let { s.copy(skip = it.toInstant().toEpochMilli()) } ?: return
        SettingsStore.update(c) { n.into(it) }
        Alarms.refresh(c)    // reschedules, which refreshes the widget and tile
    }

    fun goodnight(c: Context): PendingIntent = PendingIntent.getActivity(c, 20,
        Intent(c, AlarmFlowActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("start", "goodnight"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(c, 21,
        Intent(c, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun skipIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(c, 22,
        Intent(c, AlarmWidget::class.java).setAction(ACTION_SKIP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun views(c: Context): RemoteViews {
        val t = texts(AlarmSettings.from(SettingsStore.read(c)), ZonedDateTime.now(), DateFormat.is24HourFormat(c))
        return RemoteViews(c.packageName, R.layout.widget_alarm).apply {
            setTextViewText(R.id.widget_time, t.time)
            setTextViewText(R.id.widget_line, t.line)
            setTextViewText(R.id.widget_skip, t.skip)
            setViewVisibility(R.id.widget_skip, if (t.skip.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
            setOnClickPendingIntent(R.id.widget_root, openApp(c))
            setOnClickPendingIntent(R.id.widget_goodnight, goodnight(c))
            setOnClickPendingIntent(R.id.widget_skip, skipIntent(c))
        }
    }

    /** Show the latest next alarm on every widget and on the tile. */
    @JvmStatic
    fun refresh(c: Context) {
        val wm = AppWidgetManager.getInstance(c) ?: return
        val ids = wm.getAppWidgetIds(ComponentName(c, AlarmWidget::class.java))
        if (ids.isNotEmpty()) wm.updateAppWidget(ids, views(c))
        runCatching { TileService.requestListeningState(c, ComponentName(c, AlarmTile::class.java)) }
    }
}

class AlarmWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, wm: AppWidgetManager, ids: IntArray) {
        runCatching { wm.updateAppWidget(ids, Widgets.views(c)) }.onFailure { App.save(c, "Widget", it) }
    }

    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action == Widgets.ACTION_SKIP) runCatching { Widgets.toggleSkip(c) }.onFailure { App.save(c, "Widget skip", it) }
        else super.onReceive(c, intent)
    }
}

/** Quick Settings: shows the next alarm; tap for the night screen. */
class AlarmTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val t = Widgets.texts(AlarmSettings.from(SettingsStore.read(this)), ZonedDateTime.now(), DateFormat.is24HourFormat(this))
        val on = t.time != "––:––"
        tile.label = "Sunrise"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "${t.line.substringBefore(" · ")} ${t.time}" else t.line
        tile.contentDescription = if (on) "Sunrise. Next alarm ${t.line} ${t.time}. Tap for Goodnight." else "Sunrise. ${t.line}."
        tile.icon = Icon.createWithResource(this, android.R.drawable.ic_lock_idle_alarm)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(Widgets.goodnight(this))
        else startActivityAndCollapse(Intent(this, AlarmFlowActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("start", "goodnight"))
    }
}
