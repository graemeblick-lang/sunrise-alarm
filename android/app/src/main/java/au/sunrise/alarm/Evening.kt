package au.sunrise.alarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import java.time.Instant
import java.time.ZonedDateTime

/**
 * The evening before an alarm:
 *  - Bedtime reminder: a notification [NightSettings.bedtime] hours before the alarm.
 *  - Auto-Goodnight: on the charger after [NightSettings.autoNight] o'clock, the night screen starts by itself.
 *    Checked when the phone is plugged in (while the app is running) and every 10 minutes through the evening.
 */
object Evening {
    const val ACTION_BEDTIME = "au.sunrise.alarm.BEDTIME"
    const val ACTION_CHECK = "au.sunrise.alarm.AUTO_NIGHT"
    private const val PREFS = "evening"
    private const val CHANNEL_BEDTIME = "bedtime"
    private const val NOTIF_BEDTIME = 10
    private const val NOTIF_NIGHT = 11
    private const val CHECK_EVERY = 10 * 60_000L

    /** When to remind and when to check, for the next alarm. All 0 when there's nothing to do. */
    data class Plan(val bedtimeAt: Long, val checkFrom: Long, val windowEnd: Long)

    /**
     * Pure planning (unit-tested). [alarmMs]/[sunriseMs]: the next alarm, or 0. [remindedFor]/[nightFor]:
     * the alarm the reminder was shown for / the night screen was started for, so each happens once.
     */
    fun plan(now: ZonedDateTime, alarmMs: Long, sunriseMs: Long, n: NightSettings, remindedFor: Long, nightFor: Long): Plan {
        if (alarmMs <= 0) return Plan(0, 0, 0)
        val nowMs = now.toInstant().toEpochMilli()
        val bedtimeAt = if (n.bedtime > 0 && remindedFor != alarmMs) (alarmMs - n.bedtime * 3_600_000L).takeIf { it > nowMs } ?: 0 else 0
        var checkFrom = 0L
        var windowEnd = 0L
        if (n.autoNight > 0 && nightFor != alarmMs) {
            val end = sunriseMs - 5 * 60_000L                         // never take over once the sunrise is close
            val endAt = Instant.ofEpochMilli(end).atZone(now.zone)
            var start = endAt.toLocalDate().atTime(n.autoNight, 0).atZone(now.zone)
            if (start.toInstant().toEpochMilli() > end) start = start.minusDays(1)
            val startMs = start.toInstant().toEpochMilli()
            if (end > nowMs && end - startMs <= 18 * 3_600_000L) { checkFrom = maxOf(startMs, nowMs); windowEnd = end }
        }
        return Plan(bedtimeAt, checkFrom, windowEnd)
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, 0)

    /** The next alarm and its sunrise start, from the settings. */
    private fun next(c: Context): Pair<Long, Long> {
        val a = AlarmSettings.from(SettingsStore.read(c))
        val ms = a.upcoming(ZonedDateTime.now())?.toInstant()?.toEpochMilli() ?: return 0L to 0L
        return ms to a.sunriseFor(ms)
    }

    private fun currentPlan(c: Context): Pair<Plan, Long> {
        val (alarm, sunrise) = next(c)
        val p = prefs(c)
        return plan(ZonedDateTime.now(), alarm, sunrise, NightSettings.from(SettingsStore.read(c)),
            p.getLong("remindedFor", 0), p.getLong("nightFor", 0)) to alarm
    }

    /** Set (or clear) the reminder and the next check. Called whenever the alarms are rescheduled. */
    @JvmStatic
    fun reschedule(c: Context) {
        val (plan, _) = currentPlan(c)
        val am = c.getSystemService(AlarmManager::class.java)
        val remind = intent(c, ACTION_BEDTIME, 6)
        if (plan.bedtimeAt > 0) set(am, plan.bedtimeAt, remind) else am.cancel(remind)
        val check = intent(c, ACTION_CHECK, 7)
        if (plan.checkFrom > 0) {
            val at = maxOf(plan.checkFrom, prefs(c).getLong("checkAfter", 0), System.currentTimeMillis() + 1_000)
            if (at < plan.windowEnd) set(am, at, check) else am.cancel(check)
        } else am.cancel(check)
    }

    private fun set(am: AlarmManager, at: Long, pi: PendingIntent) {
        // Doesn't need to be exact: within a few minutes is fine for these
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun intent(c: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(c, code, Intent(c, EveningReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun charging(c: Context): Boolean {
        val s = c.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
    }

    /** Is it time for the night screen? [plugged]: the phone was just plugged in. */
    @JvmStatic
    fun check(c: Context, plugged: Boolean) {
        val (plan, alarm) = currentPlan(c)
        val now = System.currentTimeMillis()
        if (plan.checkFrom == 0L || now < plan.checkFrom || now >= plan.windowEnd) { reschedule(c); return }
        if (AlarmFlowActivity.armed(c)) { prefs(c).edit().putLong("nightFor", alarm).apply(); reschedule(c); return }
        if (plugged || charging(c)) {
            prefs(c).edit().putLong("nightFor", alarm).apply()
            openNight(c)
        } else {
            prefs(c).edit().putLong("checkAfter", now + CHECK_EVERY).apply()
        }
        reschedule(c)
    }

    private fun goodnightIntent(c: Context) = Intent(c, AlarmFlowActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("start", "goodnight")

    private fun openNight(c: Context) {
        val open = goodnightIntent(c)
        val pi = PendingIntent.getActivity(c, 8, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val nm = c.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(AlarmReceiver.CHANNEL, "Sunrise", NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(null, null)
        nm.createNotificationChannel(ch)
        nm.notify(NOTIF_NIGHT, Notification.Builder(c, AlarmReceiver.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Goodnight")
            .setContentText("Tap for the night clock")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .build())
        runCatching { c.startActivity(open) }
    }

    /** The bedtime reminder, with a Goodnight button. */
    fun remind(c: Context) {
        val (alarm, _) = next(c)
        if (alarm <= 0) return
        prefs(c).edit().putLong("remindedFor", alarm).apply()
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_BEDTIME, "Bedtime reminder", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "A reminder to go to bed before your alarm" })
        val mins = Math.round((alarm - System.currentTimeMillis()) / 60_000.0)
        val time = HomeText.time(AlarmSettings.formatTime(Instant.ofEpochMilli(alarm).atZone(ZonedDateTime.now().zone).toLocalTime()),
            android.text.format.DateFormat.is24HourFormat(c))
        val pi = PendingIntent.getActivity(c, 9, goodnightIntent(c), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTIF_BEDTIME, Notification.Builder(c, CHANNEL_BEDTIME)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Time to wind down")
            .setContentText("${mins / 60}h ${mins % 60}m until your $time alarm")
            .setContentIntent(pi)
            .addAction(Notification.Action.Builder(null, "Goodnight", pi).build())
            .setAutoCancel(true)
            .setTimeoutAfter(2 * 3_600_000L)
            .build())
    }
}

class EveningReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        runCatching {
            when (intent.action) {
                Evening.ACTION_BEDTIME -> { Evening.remind(c); Evening.reschedule(c) }
                Evening.ACTION_CHECK -> Evening.check(c, false)
                Intent.ACTION_POWER_CONNECTED -> Evening.check(c, true)
            }
        }.onFailure { App.save(c, "Evening (${intent.action})", it) }
    }
}
