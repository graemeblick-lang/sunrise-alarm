package au.sunrise.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Alarms are cleared when the phone restarts and can be lost when the app is updated,
 * so put them back. Also re-check them when the clock or time zone changes.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || Intent.ACTION_TIME_CHANGED.equals(a) || Intent.ACTION_TIMEZONE_CHANGED.equals(a)) {
            try { AlarmScheduler.reschedule(c); } catch (Throwable t) { App.save(c, "Restoring alarms (" + a + ")", t); }
        }
    }
}
