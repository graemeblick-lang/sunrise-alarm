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
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Plays audio as a proper Android media service, so it keeps going if you switch apps
 * or Android closes the app's screen. Shows a notification and lock-screen controls with a Stop button,
 * and headphone/Bluetooth buttons work.
 *
 * Two jobs:
 *  - radio streams for the app (ACTION_PLAY)
 *  - the backup alarm (ACTION_BACKUP): the phone's own alarm sound, used when the app didn't start ringing
 */
public class PlaybackService extends Service {
    static final String ACTION_PLAY = "au.sunrise.alarm.PLAY";
    static final String ACTION_BACKUP = "au.sunrise.alarm.BACKUP_RING";
    static final String ACTION_VOLUME = "au.sunrise.alarm.VOLUME";
    static final String ACTION_STOP = "au.sunrise.alarm.STOP";
    static final String ACTION_STOP_BACKUP = "au.sunrise.alarm.STOP_BACKUP";
    static final String CHANNEL = "playback";
    static final String CHANNEL_ALARM = "backup-alarm";
    static final int NOTIF_ID = 11;

    /** Where to send "playing" / "error" / "ended" / "stopped" events (the app screen, when it's open). */
    interface Listener { void send(String event); }
    static volatile Listener listener;

    private MediaPlayer player;
    private MediaSession session;
    private float volume = 0.05f;
    private String title = "Radio";
    private boolean backupMode = false;
    private long backupStarted = 0, backupAlarm = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // ---------- called from the app ----------
    static void play(Context c, String url, String title, float volume) {
        Intent i = new Intent(c, PlaybackService.class).setAction(ACTION_PLAY)
                .putExtra("url", url).putExtra("title", title).putExtra("volume", volume);
        c.startForegroundService(i);
    }

    /** Ring the phone's alarm sound because the app didn't. Throws if Android won't allow it. */
    static void backup(Context c, long alarmMs) {
        c.startForegroundService(new Intent(c, PlaybackService.class).setAction(ACTION_BACKUP).putExtra("alarm", alarmMs));
    }

    static void setVolume(Context c, float volume) {
        if (running) c.startService(new Intent(c, PlaybackService.class).setAction(ACTION_VOLUME).putExtra("volume", volume));
    }

    static void stop(Context c) {
        if (running) c.startService(new Intent(c, PlaybackService.class).setAction(ACTION_STOP).putExtra("quiet", true));
    }

    /** The app has taken over ringing: stop the backup sound (leaves radio alone). */
    static void stopBackup(Context c) {
        if (running) c.startService(new Intent(c, PlaybackService.class).setAction(ACTION_STOP_BACKUP));
    }

    private static volatile boolean running = false;

    // ---------- service ----------
    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        session = new MediaSession(this, "SunriseRadio");
        session.setPlaybackToLocal(alarmAttributes());
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPause() { stopFromUser(); }
            @Override public void onStop() { stopFromUser(); }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        try {
            if (ACTION_PLAY.equals(action)) {
                stopBackupTimers();
                backupMode = false;
                title = intent.getStringExtra("title") != null ? intent.getStringExtra("title") : "Radio";
                volume = intent.getFloatExtra("volume", 0.05f);
                goForeground();
                startPlayer(Uri.parse(intent.getStringExtra("url")), false);
            } else if (ACTION_BACKUP.equals(action)) {
                if (player != null) {
                    // Already ringing, or the app's radio is playing (that's the alarm): nothing to add.
                    // Android still needs startForeground after every startForegroundService.
                    goForeground();
                    setState(PlaybackState.STATE_PLAYING);
                    return START_NOT_STICKY;
                }
                backupMode = true;
                backupStarted = System.currentTimeMillis();
                backupAlarm = intent.getLongExtra("alarm", 0);
                title = "Alarm";
                volume = 0.25f;
                goForeground();
                startPlayer(AlarmReceiver.alarmSound(), true);
                handler.post(backupRamp);
            } else if (ACTION_VOLUME.equals(action)) {
                if (!backupMode) {
                    volume = intent.getFloatExtra("volume", volume);
                    try { if (player != null) player.setVolume(volume, volume); } catch (Exception ignored) { }
                }
            } else if (ACTION_STOP_BACKUP.equals(action)) {
                if (backupMode) shutdown();
            } else if (ACTION_STOP.equals(action)) {
                if (intent.getBooleanExtra("quiet", false)) shutdown(); else stopFromUser();
            } else {
                // Restarted by Android with nothing to play
                shutdown();
            }
        } catch (Throwable t) {
            App.save(this, "Playback service (" + action + ")", t);
            shutdown();
        }
        return START_NOT_STICKY;
    }

    /** Backup alarm: fade up to full over a minute and a half, give up after 30 minutes. */
    private final Runnable backupRamp = new Runnable() {
        @Override public void run() {
            if (!backupMode || player == null) return;
            long t = System.currentTimeMillis() - backupStarted;
            if (t > AlarmScheduler.RING_WINDOW) { shutdown(); return; }
            volume = Math.min(1f, 0.25f + 0.75f * t / 90_000f);
            try { player.setVolume(volume, volume); } catch (Exception ignored) { }
            handler.postDelayed(this, 2000);
        }
    };

    private void stopBackupTimers() { handler.removeCallbacks(backupRamp); }

    private static AudioAttributes alarmAttributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)      // alarm volume, plays through Do Not Disturb
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        Notification.Builder b;
        if (backupMode) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ALARM, "Backup alarm", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Rings with the phone's alarm sound if the sunrise alarm didn't start");
            ch.setSound(null, null);   // the player makes the sound
            nm.createNotificationChannel(ch);
            PendingIntent wake = PendingIntent.getActivity(this, 22, AlarmReceiver.wakeIntent(this, backupAlarm),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b = new Notification.Builder(this, CHANNEL_ALARM)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("Alarm")
                    .setContentText("The sunrise app didn't start, so this is your backup alarm")
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setFullScreenIntent(wake, true)
                    .setContentIntent(wake);
        } else {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Music playing", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shows while the alarm's radio is playing");
            nm.createNotificationChannel(ch);
            PendingIntent open = PendingIntent.getActivity(this, 20, new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
            b = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(title)
                    .setContentText("Sunrise Alarm")
                    .setContentIntent(open);
        }
        PendingIntent stop = PendingIntent.getService(this, 21, new Intent(this, PlaybackService.class)
                .setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = b
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0))
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIF_ID, n);
        setState(PlaybackState.STATE_BUFFERING);
    }

    private void startPlayer(Uri uri, boolean loop) {
        releasePlayer();
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioAttributes(alarmAttributes());
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);   // keep playing with the screen off
            mp.setDataSource(this, uri);
            mp.setLooping(loop);
            mp.setVolume(volume, volume);
            mp.setOnPreparedListener(p -> { if (p == player) { p.start(); setState(PlaybackState.STATE_PLAYING); if (!backupMode) send("playing"); } });
            mp.setOnErrorListener((p, w, e) -> { if (p == player) { if (!backupMode) send("error"); shutdown(); } return true; });
            mp.setOnCompletionListener(p -> { if (p == player) { if (!backupMode) send("ended"); shutdown(); } });
            mp.prepareAsync();
        } catch (Exception e) {
            if (!backupMode) send("error");
            shutdown();
        }
    }

    /** Stop pressed in the notification, on the lock screen or on headphones: tell the app too. */
    private void stopFromUser() {
        if (backupMode) AlarmScheduler.markRinging(this);   // the person is awake: don't ring again for this alarm
        else send("stopped");
        shutdown();
    }

    private void shutdown() {
        stopBackupTimers();
        backupMode = false;
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
        stopBackupTimers();
        releasePlayer();
        try { session.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
