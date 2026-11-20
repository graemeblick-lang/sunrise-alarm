package au.sunrise.alarm;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * Media-app control the in-app browser can't do:
 * waking Spotify (or another music app) and controlling the phone's media volume for a gentle fade-in.
 * (Radio lives in PlaybackService.)
 */
final class NativeAudio {
    private final Context ctx;
    private final AudioManager am;
    private final SharedPreferences prefs;

    NativeAudio(Context c) {
        ctx = c;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        prefs = c.getSharedPreferences(AlarmScheduler.PREFS, 0);
    }

    /** Sends a "play" button press straight to Spotify (not to whatever app played last, like YouTube). */
    boolean spotifyKey(int keyCode) { return appKey("com.spotify.music", keyCode); }

    /** Sends a media button press to one specific app (e.g. YouTube Music), not whichever app played last. */
    boolean appKey(String pkg, int keyCode) {
        if (pkg == null || pkg.isEmpty()) return false;
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

    // ---------- Media volume, for fading music apps in ----------
    /**
     * Remember the current media volume and drop it low before music starts.
     * The saved volume is kept on disk, so it's put back even if the app was closed mid-fade.
     */
    void musicFadeStart() {
        if (prefs.getInt("savedMusicVol", -1) < 0) {
            prefs.edit().putInt("savedMusicVol", am.getStreamVolume(AudioManager.STREAM_MUSIC)).apply();
        }
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
        int saved = prefs.getInt("savedMusicVol", -1);
        if (saved >= 0) {
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0); } catch (Exception ignored) { }
            prefs.edit().remove("savedMusicVol").apply();
        }
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
}
