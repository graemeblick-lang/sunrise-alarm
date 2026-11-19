package au.sunrise.alarm;

import android.content.Context;

import com.spotify.android.appremote.api.ConnectionParams;
import com.spotify.android.appremote.api.Connector;
import com.spotify.android.appremote.api.SpotifyAppRemote;

/**
 * Talks to the Spotify app on this phone directly (Spotify's official App Remote SDK),
 * so a chosen playlist can start without going through the internet.
 */
final class SpotifyRemote {
    static final String REDIRECT_URI = "au.sunrise.alarm://spotify-callback";

    interface Events { void send(String event); }

    private SpotifyAppRemote remote;
    private final Events events;

    SpotifyRemote(Events e) { events = e; }

    /** Connect (showing Spotify's permission screen the first time). If uri is set, start playing it. */
    void connect(Context c, String clientId, boolean showAuth, String uri, boolean shuffle) {
        try { SpotifyAppRemote.disconnect(remote); } catch (Exception ignored) { }
        ConnectionParams params = new ConnectionParams.Builder(clientId)
                .setRedirectUri(REDIRECT_URI)
                .showAuthView(showAuth)
                .build();
        SpotifyAppRemote.connect(c, params, new Connector.ConnectionListener() {
            @Override
            public void onConnected(SpotifyAppRemote r) {
                remote = r;
                if (uri == null || uri.isEmpty()) { events.send("sr:connected"); return; }
                try { r.getPlayerApi().setShuffle(shuffle); } catch (Exception ignored) { }
                r.getPlayerApi().play(uri)
                        .setResultCallback(empty -> events.send("sr:playing"))
                        .setErrorCallback(t -> events.send("sr:error:" + name(t)));
            }

            @Override
            public void onFailure(Throwable t) { events.send("sr:error:" + name(t)); }
        });
    }

    void pause() {
        try { if (remote != null && remote.isConnected()) remote.getPlayerApi().pause(); } catch (Exception ignored) { }
    }

    void disconnect() {
        try { SpotifyAppRemote.disconnect(remote); } catch (Exception ignored) { }
        remote = null;
    }

    private static String name(Throwable t) {
        return t == null ? "Unknown" : t.getClass().getSimpleName().replaceAll("[^A-Za-z]", "");
    }
}
