package au.sunrise.alarm;

import android.content.ComponentName;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Android only lets an app see other apps' media sessions (which music app is playing, play/pause it)
 * if you've allowed it "Notification access". This is that listener. Sunrise doesn't read or keep
 * notifications: it only watches media sessions, to remember which app last played music. That's the
 * app a headphone-style play press reaches, so Sunrise only uses that press when it's the app you chose.
 */
public class MediaListener extends NotificationListenerService {
    static final String PREF_LAST_PLAYING = "lastPlayingPkg";

    private MediaSessionManager msm;
    private final Map<MediaController, MediaController.Callback> watched = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MediaSessionManager.OnActiveSessionsChangedListener onChange = this::watch;

    @Override
    public void onListenerConnected() {
        try {
            msm = getSystemService(MediaSessionManager.class);
            ComponentName me = new ComponentName(this, MediaListener.class);
            msm.addOnActiveSessionsChangedListener(onChange, me, handler);
            watch(msm.getActiveSessions(me));
        } catch (Exception ignored) { }
    }

    @Override
    public void onListenerDisconnected() {
        try { if (msm != null) msm.removeOnActiveSessionsChangedListener(onChange); } catch (Exception ignored) { }
        unwatchAll();
    }

    private void watch(List<MediaController> controllers) {
        unwatchAll();
        if (controllers == null) return;
        for (MediaController c : controllers) {
            note(c, c.getPlaybackState());
            MediaController.Callback cb = new MediaController.Callback() {
                @Override public void onPlaybackStateChanged(PlaybackState state) { note(c, state); }
            };
            try { c.registerCallback(cb, handler); watched.put(c, cb); } catch (Exception ignored) { }
        }
    }

    private void unwatchAll() {
        for (Map.Entry<MediaController, MediaController.Callback> e : watched.entrySet()) {
            try { e.getKey().unregisterCallback(e.getValue()); } catch (Exception ignored) { }
        }
        watched.clear();
    }

    private void note(MediaController c, PlaybackState state) {
        if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
            getSharedPreferences(AlarmScheduler.PREFS, 0).edit().putString(PREF_LAST_PLAYING, c.getPackageName()).apply();
        }
    }
}
