package au.sunrise.alarm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.util.List;

/**
 * Starts and stops one specific music app (Spotify, YouTube Music, Podcast Addict…), never
 * "whichever app played last", and without any special permission:
 *
 *  1. A play press sent only to that app.
 *  2. A connection to the app's media service, the way Android Auto starts an app that isn't running,
 *     then "play" through its media session. When the app accepts the connection, Sunrise can also see
 *     whether it's really playing and pause it.
 *
 * Some apps only accept connections from apps they know (Android Auto, Google). For those, Sunrise can't
 * check which app is playing, only that the press went to the right one. If nothing plays, the chimes do.
 *
 * (Sunrise deliberately doesn't use Notification access to watch other apps' media: Google Play Protect
 * blocks installing apps outside the Play Store that ask for it.)
 */
final class MediaApps {
    private final Context ctx;
    private final NativeAudio audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaBrowser browser;
    private volatile MediaController controller;   // the chosen app's session, once connected
    private volatile String controllerPkg;

    MediaApps(Context c, NativeAudio audio) {
        ctx = c.getApplicationContext();
        this.audio = audio;
    }

    boolean installed(String pkg) {
        try { ctx.getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    /** Press play in that app and connect to it. Returns false if it isn't installed. */
    boolean start(String pkg) {
        if (pkg == null || pkg.isEmpty() || !installed(pkg)) return false;
        audio.musicFadeStart();
        audio.appKey(pkg, KeyEvent.KEYCODE_MEDIA_PLAY);
        main.post(() -> connectAndPlay(pkg));
        return true;
    }

    /** "playing", "not" (connected and it isn't), or "unknown" (the app didn't let us connect). */
    String state(String pkg) {
        MediaController c = controller;
        if (c == null || !pkg.equals(controllerPkg)) return "unknown";
        try {
            PlaybackState s = c.getPlaybackState();
            return s != null && s.getState() == PlaybackState.STATE_PLAYING ? "playing" : "not";
        } catch (Exception e) { return "unknown"; }
    }

    /** Ask again through the session, if connected (some apps need a moment after starting). */
    void retry(String pkg) {
        MediaController c = controller;
        if (c != null && pkg.equals(controllerPkg)) {
            try { c.getTransportControls().play(); } catch (Exception ignored) { }
        }
    }

    void pause(String pkg) {
        MediaController c = controller;
        if (c != null && pkg != null && pkg.equals(controllerPkg)) {
            try { c.getTransportControls().pause(); } catch (Exception ignored) { }
        } else if (pkg != null) {
            audio.appKey(pkg, KeyEvent.KEYCODE_MEDIA_PAUSE);
        }
        main.post(this::disconnect);
    }

    void release() { main.post(this::disconnect); }

    // ---------- the app's media service ----------
    private void connectAndPlay(String pkg) {
        disconnect();
        ComponentName service = browserService(pkg);
        if (service == null) return;
        try {
            final MediaBrowser[] holder = new MediaBrowser[1];
            holder[0] = new MediaBrowser(ctx, service, new MediaBrowser.ConnectionCallback() {
                @Override public void onConnected() {
                    if (browser != holder[0]) return;
                    try {
                        MediaController c = new MediaController(ctx, holder[0].getSessionToken());
                        controllerPkg = pkg;
                        controller = c;
                        c.getTransportControls().play();
                    } catch (Exception ignored) { }
                }
                @Override public void onConnectionFailed() { if (browser == holder[0]) disconnect(); }   // this app doesn't accept us
                @Override public void onConnectionSuspended() { if (browser == holder[0]) disconnect(); }
            }, null);
            browser = holder[0];
            browser.connect();
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
        controller = null;
        controllerPkg = null;
        MediaBrowser b = browser;
        browser = null;
        if (b != null) { try { b.disconnect(); } catch (Exception ignored) { } }
    }
}
