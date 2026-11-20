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
import android.media.AudioFocusRequest;
import android.media.AudioManager;
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
    static final String ACTION_PLAY_SONGS = "au.sunrise.alarm.PLAY_SONGS";
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
    // Your songs: shuffled playlist (uris and names in play order), and how many failed in a row
    private java.util.List<Uri> songUris = null;
    private java.util.List<String> songNames = null;
    private int songIndex = 0, songFailures = 0;
    private boolean songStarted = false;
    private long backupStarted = 0, backupAlarm = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioFocusRequest focusReq;
    /** 1 normally; lower while another app briefly needs the sound (navigation directions). */
    private float duck = 1f;
    private boolean pausedForFocus = false;

    // ---------- called from the app ----------
    /** Your songs (content:// uris from Android's file picker), shuffled, one after another. */
    static void playSongs(Context c, java.util.ArrayList<String> uris, java.util.ArrayList<String> names, float volume) {
        c.startForegroundService(new Intent(c, PlaybackService.class).setAction(ACTION_PLAY_SONGS)
                .putStringArrayListExtra("uris", uris).putStringArrayListExtra("names", names).putExtra("volume", volume));
    }

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
            if (ACTION_PLAY_SONGS.equals(action)) {
                stopBackupTimers();
                backupMode = false;
                java.util.ArrayList<String> uris = intent.getStringArrayListExtra("uris");
                java.util.ArrayList<String> names = intent.getStringArrayListExtra("names");
                if (uris == null || uris.isEmpty()) { send("error"); shutdown(); return START_NOT_STICKY; }
                // Shuffle together so names stay with their songs
                java.util.List<Integer> order = new java.util.ArrayList<>();
                for (int i = 0; i < uris.size(); i++) order.add(i);
                java.util.Collections.shuffle(order);
                songUris = new java.util.ArrayList<>(); songNames = new java.util.ArrayList<>();
                for (int i : order) {
                    songUris.add(Uri.parse(uris.get(i)));
                    songNames.add(names != null && i < names.size() ? names.get(i) : "Your songs");
                }
                songIndex = 0; songFailures = 0; songStarted = false;
                volume = intent.getFloatExtra("volume", 0.05f);
                playSong();
            } else if (ACTION_PLAY.equals(action)) {
                songUris = null;
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
                    applyVolume();
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
            applyVolume();
            handler.postDelayed(this, 2000);
        }
    };

    private void stopBackupTimers() { handler.removeCallbacks(backupRamp); }

    private void applyVolume() {
        try { if (player != null) player.setVolume(volume * duck, volume * duck); } catch (Exception ignored) { }
    }

    // ---------- audio focus: other apps pause for the alarm; a phone call pauses the alarm until it ends ----------
    private void requestFocus() {
        if (focusReq != null) return;
        try {
            focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(alarmAttributes())
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(this::onFocusChange, handler)
                    .build();
            getSystemService(AudioManager.class).requestAudioFocus(focusReq);
            // Even if it's refused (e.g. during a call) the alarm still plays: waking you matters more
        } catch (Exception ignored) { }
    }

    private void abandonFocus() {
        if (focusReq == null) return;
        try { getSystemService(AudioManager.class).abandonAudioFocusRequest(focusReq); } catch (Exception ignored) { }
        focusReq = null;
    }

    private void onFocusChange(int change) {
        if (player == null) return;
        try {
            switch (change) {
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:            // phone call: pause, carry on after
                    if (player.isPlaying()) { player.pause(); pausedForFocus = true; }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:   // e.g. navigation speaking: quieter for a moment
                    duck = 0.3f; applyVolume();
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    duck = 1f; applyVolume();
                    if (pausedForFocus) { pausedForFocus = false; player.start(); }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS:
                    // You started something else. The radio stops (and the app is told); the backup alarm keeps ringing.
                    if (!backupMode) stopFromUser();
                    break;
            }
        } catch (Exception ignored) { }
    }

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
            duck = 1f; pausedForFocus = false;
            mp.setVolume(volume, volume);
            requestFocus();
            mp.setOnPreparedListener(p -> {
                if (p != player) return;
                p.start(); setState(PlaybackState.STATE_PLAYING);
                if (songUris != null) {
                    songFailures = 0;
                    if (!songStarted) { songStarted = true; send("playing"); }
                    send("song:" + songIndex);
                } else if (!backupMode) send("playing");
            });
            mp.setOnErrorListener((p, w, e) -> {
                if (p != player) return true;
                if (songUris != null) { songFailures++; nextSong(); }    // skip a song that won't play
                else { if (!backupMode) send("error"); shutdown(); }
                return true;
            });
            mp.setOnCompletionListener(p -> {
                if (p != player) return;
                if (songUris != null) nextSong();
                else { if (!backupMode) send("ended"); shutdown(); }
            });
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

    // ---------- your songs ----------
    private void playSong() {
        if (songUris == null || songUris.isEmpty()) return;
        title = songNames.get(songIndex);
        goForeground();
        startPlayer(songUris.get(songIndex), false);
    }

    private void nextSong() {
        if (songUris == null) return;
        if (songFailures >= songUris.size()) {      // none of them will play (deleted, or no longer allowed)
            send("error");
            shutdown();
            return;
        }
        songIndex = (songIndex + 1) % songUris.size();   // round and round until you stop it
        playSong();
    }

    private void shutdown() {
        stopBackupTimers();
        abandonFocus();
        backupMode = false;
        songUris = null;
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
        abandonFocus();
        releasePlayer();
        try { session.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
