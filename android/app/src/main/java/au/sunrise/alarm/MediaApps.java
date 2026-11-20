package au.sunrise.alarm;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.Collections;
import java.util.List;

/**
 * Starts and stops one specific music app (Spotify, YouTube Music, Podcast Addict…) the way Android Auto
 * and watches do: through that app's media session, never "whichever app played last".
 *
 * In order:
 *  1. The app is running: tell its media session to play.
 *  2. It isn't: connect to the app's media service (this starts it) and tell it to play. Some apps only
 *     accept connections from apps they know, so a direct play press is sent as well.
 *  3. Still nothing: a headphone-style press through Android, which can start an app from cold, but only
 *     if that app is the one that played last (otherwise it would start the wrong app).
 *
 * 1, 3's check and knowing the app really is playing need "Notification access" (see MediaListener).
 */
final class MediaApps {
    private final Context ctx;
    private final ComponentName listener;
    private final NativeAudio audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaBrowser browser;

    MediaApps(Context c, NativeAudio audio) {
        ctx = c.getApplicationContext();
        listener = new ComponentName(ctx, MediaListener.class);
        this.audio = audio;
    }

    /** Has the person allowed Notification access (needed to see and control other apps' media)? */
    boolean hasAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                return ctx.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(listener);
            }
            String s = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
            return s != null && (s.contains(listener.flattenToString()) || s.contains(listener.flattenToShortString()));
        } catch (Exception e) { return false; }
    }

    /** Opens the Android setting where Notification access for Sunrise is switched on. */
    Intent accessSettings() {
        if (Build.VERSION.SDK_INT >= 30) {
            return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString());
        }
        return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
    }

    private List<MediaController> sessions() {
        if (!hasAccess()) return Collections.emptyList();
        try { return ctx.getSystemService(MediaSessionManager.class).getActiveSessions(listener); }
        catch (Exception e) { return Collections.emptyList(); }
    }

    private MediaController controller(String pkg) {
        for (MediaController c : sessions()) if (pkg.equals(c.getPackageName())) return c;
        return null;
    }

    /** "playing", "not" (we can see it isn't), or "unknown" (no Notification access). */
    String state(String pkg) {
        if (!hasAccess()) return "unknown";
        MediaController c = controller(pkg);
        PlaybackState s = c == null ? null : c.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PLAYING ? "playing" : "not";
    }

    boolean installed(String pkg) {
        try { ctx.getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    /** Steps 1–2. Returns false if the app isn't installed. */
    boolean start(String pkg) {
        if (pkg == null || pkg.isEmpty() || !installed(pkg)) return false;
        audio.musicFadeStart();
        MediaController c = controller(pkg);
        if (c != null) {
            try { c.getTransportControls().play(); return true; } catch (Exception ignored) { }
        }
        audio.appKey(pkg, android.view.KeyEvent.KEYCODE_MEDIA_PLAY);
        main.post(() -> connectAndPlay(pkg));
        return true;
    }

    /** Step 3: the headphone-style press. "pressed", "skipped" (another app played last) or "blind" (can't check). */
    String nudge(String pkg) {
        if (!hasAccess()) { audio.systemPlay(); return "blind"; }
        String last = ctx.getSharedPreferences(AlarmScheduler.PREFS, 0).getString(MediaListener.PREF_LAST_PLAYING, null);
        if (pkg.equals(last)) { audio.systemPlay(); return "pressed"; }
        return "skipped";
    }

    void pause(String pkg) {
        MediaController c = pkg == null ? null : controller(pkg);
        if (c != null) { try { c.getTransportControls().pause(); } catch (Exception ignored) { } }
        else audio.appKey(pkg, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE);
        main.post(this::disconnect);
    }

    // ---------- the app's media service (how Android Auto starts an app that isn't running) ----------
    private void connectAndPlay(String pkg) {
        disconnect();
        ComponentName service = browserService(pkg);
        if (service == null) return;
        try {
            final MediaBrowser[] holder = new MediaBrowser[1];
            holder[0] = new MediaBrowser(ctx, service, new MediaBrowser.ConnectionCallback() {
                @Override public void onConnected() {
                    try { new MediaController(ctx, holder[0].getSessionToken()).getTransportControls().play(); }
                    catch (Exception ignored) { }
                }
                @Override public void onConnectionFailed() { disconnect(); }   // this app doesn't accept us
            }, null);
            browser = holder[0];
            browser.connect();
            main.postDelayed(this::disconnect, 30_000);   // the app keeps playing on its own once started
        } catch (Exception ignored) { }
    }

    private ComponentName browserService(String pkg) {
        try {
            List<ResolveInfo> found = ctx.getPackageManager().queryIntentServices(
                    new Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0);
            if (found.isEmpty()) return null;
            return new ComponentName(pkg, found.get(0).serviceInfo.name);
        } catch (Exception e) { return null; }
    }

    private void disconnect() {
        main.removeCallbacksAndMessages(null);
        MediaBrowser b = browser;
        browser = null;
        if (b != null) { try { b.disconnect(); } catch (Exception ignored) { } }
    }
}
