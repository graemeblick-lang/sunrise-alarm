package au.sunrise.alarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Plays radio streams as a proper Android media service, so the music keeps going if you switch apps
 * or Android closes the app's screen. Shows a notification and lock-screen controls with a Stop button,
 * and headphone/Bluetooth buttons work.
 */
public class PlaybackService extends Service {
    static final String ACTION_PLAY = "au.sunrise.alarm.PLAY";
    static final String ACTION_VOLUME = "au.sunrise.alarm.VOLUME";
    static final String ACTION_STOP = "au.sunrise.alarm.STOP";
    static final String CHANNEL = "playback";
    static final int NOTIF_ID = 11;

    /** Where to send "playing" / "error" / "ended" / "stopped" events (the app screen, when it's open). */
    interface Listener { void send(String event); }
    static volatile Listener listener;

    private MediaPlayer player;
    private MediaSession session;
    private float volume = 0.05f;
    private String title = "Radio";

    // ---------- called from the app ----------
    static void play(Context c, String url, String title, float volume) {
        Intent i = new Intent(c, PlaybackService.class).setAction(ACTION_PLAY)
                .putExtra("url", url).putExtra("title", title).putExtra("volume", volume);
        c.startForegroundService(i);
    }

    static void setVolume(Context c, float volume) {
        if (running) c.startService(new Intent(c, PlaybackService.class).setAction(ACTION_VOLUME).putExtra("volume", volume));
    }

    static void stop(Context c) {
        if (running) c.startService(new Intent(c, PlaybackService.class).setAction(ACTION_STOP).putExtra("quiet", true));
    }

    private static volatile boolean running = false;

    // ---------- service ----------
    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        session = new MediaSession(this, "SunriseRadio");
        session.setPlaybackToLocal(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPause() { stopFromUser(); }
            @Override public void onStop() { stopFromUser(); }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PLAY.equals(action)) {
            title = intent.getStringExtra("title") != null ? intent.getStringExtra("title") : "Radio";
            volume = intent.getFloatExtra("volume", 0.05f);
            goForeground();
            startPlayer(intent.getStringExtra("url"));
        } else if (ACTION_VOLUME.equals(action)) {
            volume = intent.getFloatExtra("volume", volume);
            try { if (player != null) player.setVolume(volume, volume); } catch (Exception ignored) { }
        } else if (ACTION_STOP.equals(action)) {
            if (intent.getBooleanExtra("quiet", false)) shutdown(); else stopFromUser();
        } else {
            // Restarted by Android with nothing to play
            shutdown();
        }
        return START_NOT_STICKY;
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Music playing", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Shows while the alarm's radio is playing");
        nm.createNotificationChannel(ch);

        PendingIntent open = PendingIntent.getActivity(this, 20, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 21, new Intent(this, PlaybackService.class)
                .setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText("Sunrise Alarm")
                .setContentIntent(open)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0))
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIF_ID, n);
        setState(PlaybackState.STATE_BUFFERING);
    }

    private void startPlayer(String url) {
        releasePlayer();
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)      // alarm volume, plays through Do Not Disturb
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);   // keep playing with the screen off
            mp.setDataSource(url);
            mp.setVolume(volume, volume);
            mp.setOnPreparedListener(p -> { if (p == player) { p.start(); setState(PlaybackState.STATE_PLAYING); send("playing"); } });
            mp.setOnErrorListener((p, w, e) -> { if (p == player) { send("error"); shutdown(); } return true; });
            mp.setOnCompletionListener(p -> { if (p == player) { send("ended"); shutdown(); } });
            mp.prepareAsync();
        } catch (Exception e) {
            send("error");
            shutdown();
        }
    }

    /** Stop pressed in the notification, on the lock screen or on headphones: tell the app too. */
    private void stopFromUser() {
        send("stopped");
        shutdown();
    }

    private void shutdown() {
        releasePlayer();
        setState(PlaybackState.STATE_STOPPED);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releasePlayer() {
        MediaPlayer mp = player;
        player = null;
        if (mp != null) { try { mp.stop(); } catch (Exception ignored) { } try { mp.release(); } catch (Exception ignored) { } }
    }

    private void setState(int state) {
        try {
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_STOP | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build());
        } catch (Exception ignored) { }
    }

    private static void send(String ev) { Listener l = listener; if (l != null) l.send(ev); }

    @Override
    public void onDestroy() {
        running = false;
        releasePlayer();
        try { session.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
