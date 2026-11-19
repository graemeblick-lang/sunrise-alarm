package au.sunrise.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Schedules the moment the sunrise should start, as a system alarm clock (shown in the status bar). */
public final class AlarmScheduler {
    private static final String PREFS = "sunrise";

    public static void schedule(Context c, long sunriseMs, long alarmMs) {
        c.getSharedPreferences(PREFS, 0).edit().putLong("sunrise", sunriseMs).putLong("alarm", alarmMs).apply();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent show = PendingIntent.getActivity(c, 2,
                new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(sunriseMs, show), receiverIntent(c));
        } catch (SecurityException e) {
            // Exact alarms not allowed: fall back to a slightly less precise alarm that still wakes the phone
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, sunriseMs, receiverIntent(c));
        }
    }

    public static void cancel(Context c) {
        c.getSharedPreferences(PREFS, 0).edit().remove("sunrise").remove("alarm").apply();
        ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(receiverIntent(c));
    }

    /** After a restart, put back whatever was scheduled. */
    public static void restore(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, 0);
        long s = p.getLong("sunrise", 0), a = p.getLong("alarm", 0);
        if (a > System.currentTimeMillis()) schedule(c, Math.max(s, System.currentTimeMillis() + 5000), a);
    }

    private static PendingIntent receiverIntent(Context c) {
        return PendingIntent.getBroadcast(c, 1, new Intent(c, AlarmReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
