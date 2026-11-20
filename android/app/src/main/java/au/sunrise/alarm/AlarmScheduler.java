package au.sunrise.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps two system alarms set, entirely on the Android side, so they survive the app's screen being closed,
 * the phone restarting and the web page failing to load:
 *
 *  1. OPEN: when the sunrise should start, open the app over the lock screen.
 *  2. BACKUP: a minute after the alarm time, check the app really started ringing. If it didn't
 *     (page didn't load, Android froze it, the phone killed it), ring natively with the phone's alarm sound.
 *
 * The page sends a plan of the next few alarms, so the next one is set even if the app never opens again.
 */
public final class AlarmScheduler {
    static final String PREFS = "sunrise";
    static final String ACTION_OPEN = "au.sunrise.alarm.OPEN";
    static final String ACTION_BACKUP = "au.sunrise.alarm.BACKUP";
    static final String EXTRA_ALARM = "alarmMs";
    /** How long after the alarm time the backup waits for the app to ring. */
    static final long BACKUP_DELAY = 60_000;
    /** After this long the alarm gives up (same as the app). */
    static final long RING_WINDOW = 30 * 60_000;

    private AlarmScheduler() { }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, 0); }

    // ---------- called from the page ----------

    /** The upcoming alarms, as JSON [[sunriseMs, alarmMs], ...] in time order. "[]" turns automatic alarms off. */
    public static void setPlan(Context c, String json) {
        prefs(c).edit().putString("plan", json == null ? "[]" : json).remove("sunrise").remove("alarm").apply();
        reschedule(c);
    }

    /** Older pages send one alarm at a time. */
    public static void schedule(Context c, long sunriseMs, long alarmMs) {
        setPlan(c, "[[" + sunriseMs + "," + alarmMs + "]]");
    }

    public static void cancel(Context c) { setPlan(c, "[]"); }

    /** A backup for an alarm the app is handling itself (Goodnight without automatic opening, or a snooze). */
    public static void armBackup(Context c, long alarmMs) {
        prefs(c).edit().putLong("manual", alarmMs).apply();
        reschedule(c);
    }

    public static void cancelBackup(Context c) {
        prefs(c).edit().remove("manual").apply();
        reschedule(c);
    }

    /** The app is ringing (or snoozing): no need for the backup this time. */
    public static void markRinging(Context c) {
        prefs(c).edit().putLong("lastRing", System.currentTimeMillis()).apply();
    }

    // ---------- state ----------

    static List<long[]> plan(Context c) {
        SharedPreferences p = prefs(c);
        List<long[]> out = new ArrayList<>();
        String json = p.getString("plan", null);
        if (json == null) {
            // Saved by an older version of the app
            long s = p.getLong("sunrise", 0), a = p.getLong("alarm", 0);
            if (a > 0) out.add(new long[]{s, a});
            return out;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONArray e = arr.getJSONArray(i);
                long s = e.getLong(0), a = e.getLong(1);
                if (a > 0) out.add(new long[]{Math.min(s, a), a});
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** True if the app ringing was reported recently enough to cover the alarm at alarmMs. */
    static boolean rangFor(Context c, long alarmMs) {
        return prefs(c).getLong("lastRing", 0) >= alarmMs - 2 * 60_000;
    }

    static void markOpened(Context c, long alarmMs) { prefs(c).edit().putLong("opened", alarmMs).apply(); }

    static void clearManualIf(Context c, long alarmMs) {
        if (prefs(c).getLong("manual", 0) == alarmMs) prefs(c).edit().remove("manual").apply();
    }

    /** The alarm currently between its sunrise (or a little before) and the end of its ringing window, or 0. */
    static long activeAlarm(Context c, long beforeMs) {
        long now = System.currentTimeMillis();
        List<long[]> targets = plan(c);
        long manual = prefs(c).getLong("manual", 0);
        if (manual > 0) targets.add(new long[]{manual, manual});
        for (long[] t : targets) {
            if (now >= t[0] - beforeMs && now <= t[1] + RING_WINDOW) return t[1];
        }
        return 0;
    }

    // ---------- scheduling ----------

    /** Set (or clear) both system alarms from the saved plan. Safe to call any time. */
    public static void reschedule(Context c) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        long opened = p.getLong("opened", 0);
        List<long[]> plan = plan(c);

        // 1) Next sunrise to open. If we're already past its start (e.g. after a restart), open in a few seconds.
        long[] open = null;
        for (long[] e : plan) if (e[1] > now && e[1] != opened) { open = e; break; }
        if (open != null) setClock(c, Math.max(open[0], now + 5000), intent(c, ACTION_OPEN, 1, open[1]));
        else alarmManager(c).cancel(intent(c, ACTION_OPEN, 1, 0));

        // 2) Next backup check: planned alarms plus a manual one
        long target = 0;
        long manual = p.getLong("manual", 0);
        List<Long> candidates = new ArrayList<>();
        for (long[] e : plan) candidates.add(e[1]);
        if (manual > 0) candidates.add(manual);
        for (long t : candidates) if (t + BACKUP_DELAY > now && (target == 0 || t < target)) target = t;
        if (target > 0) setClock(c, target + BACKUP_DELAY, intent(c, ACTION_BACKUP, 4, target));
        else alarmManager(c).cancel(intent(c, ACTION_BACKUP, 4, 0));

        // 3) The evening: bedtime reminder and auto-Goodnight; and the widget and tile show the new next alarm
        try { Evening.reschedule(c); } catch (Throwable t) { App.save(c, "Evening reminders", t); }
        try { Widgets.refresh(c); } catch (Throwable t) { App.save(c, "Widget and tile", t); }
    }

    private static void setClock(Context c, long atMs, PendingIntent pi) {
        AlarmManager am = alarmManager(c);
        PendingIntent show = PendingIntent.getActivity(c, 2,
                new Intent(c, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(atMs, show), pi);
        } catch (SecurityException e) {
            // Exact alarms not allowed: fall back to a slightly less precise alarm that still wakes the phone
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi);
        }
    }

    private static PendingIntent intent(Context c, String action, int code, long alarmMs) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(action).putExtra(EXTRA_ALARM, alarmMs);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static AlarmManager alarmManager(Context c) { return (AlarmManager) c.getSystemService(Context.ALARM_SERVICE); }
}
