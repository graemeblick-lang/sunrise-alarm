package au.sunrise.alarm;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Phone settings that would stop the alarm working properly, and where to fix each one.
 * Shown on the home screen (and to the page in the browser-style status card).
 */
final class PhoneChecks {
    private PhoneChecks() { }

    /** "notifications", "fullscreen", "exact", "battery": whichever are a problem right now. */
    static List<String> problems(Context c) {
        List<String> out = new ArrayList<>();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (!nm.areNotificationsEnabled()) out.add("notifications");
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) out.add("fullscreen");
        if (Build.VERSION.SDK_INT >= 31 && !c.getSystemService(AlarmManager.class).canScheduleExactAlarms()) out.add("exact");
        if (!c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName())) out.add("battery");
        return out;
    }

    /** What each problem means, in plain words. */
    static String describe(String what) {
        switch (what) {
            case "notifications": return "Notifications are off, so the app can't open itself at sunrise.";
            case "fullscreen": return "\"Full screen notifications\" is off, so the app can't open over the lock screen.";
            case "exact": return "\"Alarms & reminders\" is off, so the alarm may go off late.";
            case "battery": return "Battery saving can freeze the app overnight. Allow it to run in the background.";
            default: return what;
        }
    }

    /** Opens the Android setting that fixes it (or the app's settings page if that one isn't available). */
    static void fix(Context c, String what) {
        Uri pkg = Uri.parse("package:" + c.getPackageName());
        Intent i;
        if ("fullscreen".equals(what) && Build.VERSION.SDK_INT >= 34) {
            i = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg);
        } else if ("exact".equals(what) && Build.VERSION.SDK_INT >= 31) {
            i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg);
        } else if ("battery".equals(what)) {
            i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg);
        } else {
            i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); } catch (Exception e) {
            try { c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
        }
    }
}
