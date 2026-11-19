package au.sunrise.alarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires when the sunrise should start: brings the app to the front, over the lock screen. */
public class AlarmReceiver extends BroadcastReceiver {
    static final String CHANNEL = "sunrise";
    static final int NOTIF_ID = 7;

    @Override
    public void onReceive(Context c, Intent intent) {
        Intent open = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("wake", true);
        PendingIntent pi = PendingIntent.getActivity(c, 3, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
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
}
