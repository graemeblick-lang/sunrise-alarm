package au.sunrise.alarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;

/**
 * OPEN: the sunrise should start, so bring the app to the front, over the lock screen.
 * BACKUP: a minute after the alarm time. If the app hasn't said it's ringing, ring natively.
 */
public class AlarmReceiver extends BroadcastReceiver {
    static final String CHANNEL = "sunrise";
    static final String CHANNEL_SOUND = "alarm-sound";
    static final int NOTIF_ID = 7;
    static final int BACKUP_NOTIF_ID = 8;

    @Override
    public void onReceive(Context c, Intent intent) {
        long alarmMs = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM, 0);
        try {
            if (AlarmScheduler.ACTION_BACKUP.equals(intent.getAction())) backup(c, alarmMs);
            else open(c, alarmMs);   // also alarms set by older versions, which had no action
        } catch (Throwable t) {
            App.save(c, "Alarm receiver", t);
        } finally {
            AlarmScheduler.reschedule(c);   // set the next one straight away, whatever happened
        }
    }

    private void open(Context c, long alarmMs) {
        if (alarmMs > 0) AlarmScheduler.markOpened(c, alarmMs);
        Intent open = wakeIntent(c, alarmMs);
        PendingIntent pi = PendingIntent.getActivity(c, 3, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Sunrise", NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null, null);
        ch.setDescription("Opens the sunrise screen before your alarm");
        nm.createNotificationChannel(ch);

        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Sunrise is starting")
                .setContentText("Tap to open")
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true)   // opens the app when the screen is off or locked
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIF_ID, n);

        // When Android allows it, open the app straight away too
        try { c.startActivity(open); } catch (Exception ignored) { }
    }

    private void backup(Context c, long alarmMs) {
        AlarmScheduler.clearManualIf(c, alarmMs);
        if (alarmMs <= 0 || AlarmScheduler.rangFor(c, alarmMs)) return;                    // the app rang: all good
        if (System.currentTimeMillis() > alarmMs + AlarmScheduler.RING_WINDOW) return;     // far too late (phone was off)

        try {
            PlaybackService.backup(c, alarmMs);
        } catch (Throwable t) {
            // Android wouldn't start the player: ring with an insistent alarm notification instead
            App.save(c, "Starting the backup alarm sound", t);
            soundNotification(c, alarmMs);
        }
        // Also try to bring the app up so its own alarm takes over
        try { c.startActivity(wakeIntent(c, alarmMs)); } catch (Exception ignored) { }
    }

    static Intent wakeIntent(Context c, long alarmMs) {
        return new Intent(c, AlarmFlowActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("wake", true)
                .putExtra(AlarmScheduler.EXTRA_ALARM, alarmMs);
    }

    static Uri alarmSound() {
        Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        return u;
    }

    private static void soundNotification(Context c, long alarmMs) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL_SOUND, "Backup alarm", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Rings if the sunrise alarm didn't start");
        ch.setSound(alarmSound(), new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        ch.setBypassDnd(true);
        nm.createNotificationChannel(ch);
        PendingIntent pi = PendingIntent.getActivity(c, 5, wakeIntent(c, alarmMs),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL_SOUND)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Alarm")
                .setContentText("Tap to open Sunrise")
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        n.flags |= Notification.FLAG_INSISTENT;   // keeps repeating the sound until it's opened or dismissed
        nm.notify(BACKUP_NOTIF_ID, n);
    }
}
