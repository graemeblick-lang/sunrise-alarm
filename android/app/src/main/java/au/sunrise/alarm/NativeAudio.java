package au.sunrise.alarm;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * Audio the in-app browser can't do well:
 *  - radio streams (including the ABC's HLS streams) through Android's own player, on the alarm channel
 *  - waking the Spotify app and controlling the phone's media volume for a gentle fade-in
 */
final class NativeAudio {
    interface Events { void send(String event); }

    private final Context ctx;
    private final Events events;
    private final AudioManager am;
    private MediaPlayer radio;
    private float radioVol = 0.05f;
    private int savedMusicVol = -1;

    NativeAudio(Context c, Events e) {
        ctx = c; events = e;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    // ---------- Radio ----------
    void radioPlay(String url, float volume) {
        radioStop();
        radioVol = clamp(volume);
        try {
            MediaPlayer mp = new MediaPlayer();
            radio = mp;
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)            // plays at alarm volume, even in Do Not Disturb
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(url);
            mp.setVolume(radioVol, radioVol);
            mp.setOnPreparedListener(p -> { if (p == radio) { p.start(); events.send("playing"); } });
            mp.setOnErrorListener((p, what, extra) -> { if (p == radio) events.send("error"); return true; });
            mp.setOnCompletionListener(p -> { if (p == radio) events.send("ended"); });
            mp.prepareAsync();
        } catch (Exception ex) {
            events.send("error");
        }
    }

    void radioVolume(float v) {
        radioVol = clamp(v);
        try { if (radio != null) radio.setVolume(radioVol, radioVol); } catch (Exception ignored) { }
    }

    void radioStop() {
        MediaPlayer mp = radio;
        radio = null;
        if (mp != null) { try { mp.stop(); } catch (Exception ignored) { } try { mp.release(); } catch (Exception ignored) { } }
    }

    // ---------- Spotify ----------
    /** Sends a "play" button press straight to Spotify (not to whatever app played last, like YouTube). */
    boolean spotifyKey(int keyCode) { return appKey("com.spotify.music", keyCode); }

    /** Sends a media button press to one specific app (e.g. YouTube Music), not whichever app played last. */
    boolean appKey(String pkg, int keyCode) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
        } catch (Exception notInstalled) { return false; }
        long t = SystemClock.uptimeMillis();
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setPackage(pkg)
                    .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(t, t, action, keyCode, 0));
            ctx.sendOrderedBroadcast(i, null);
        }
        return true;
    }

    // ---------- Media volume, for fading Spotify in ----------
    /** Remember the current media volume and drop it low before music starts. */
    void musicFadeStart() {
        if (savedMusicVol < 0) savedMusicVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        setMusic(0.07f);
    }

    /** 0..1 of the phone's maximum media volume. */
    void setMusic(float f) {
        try {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(1, Math.round(clamp(f) * max)), 0);
        } catch (Exception ignored) { }
    }

    /** Put the media volume back the way it was. */
    void musicRestore() {
        if (savedMusicVol >= 0) {
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVol, 0); } catch (Exception ignored) { }
            savedMusicVol = -1;
        }
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
}
