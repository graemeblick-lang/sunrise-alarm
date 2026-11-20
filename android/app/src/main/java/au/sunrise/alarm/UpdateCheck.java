package au.sunrise.alarm;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The app no longer loads from the website, so updates only arrive as a new APK.
 * This checks (at most every 12 hours, never near an alarm) whether a newer build is on the
 * GitHub Releases page. It only reads a tiny version file; installing is always your choice.
 */
final class UpdateCheck {
    static final String RELEASE = "https://github.com/graemeblick-lang/sunrise-alarm/releases/download/latest/";
    static final String APK_URL = RELEASE + "SunriseAlarm.apk";
    private static final String VERSION_URL = RELEASE + "version.json";
    private static final long EVERY = 12 * 60 * 60_000L;

    interface Result { void newer(int versionCode); }

    private UpdateCheck() { }

    /** Runs in the background. Calls back (on a background thread) only if a newer version exists. */
    static void check(Context c, boolean force, Result result) {
        final Context app = c.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences(AlarmScheduler.PREFS, 0);
        int known = p.getInt("latestVersion", 0);
        if (known > BuildConfig.VERSION_CODE) result.newer(known);   // already know about it: say so straight away
        long now = System.currentTimeMillis();
        if (!force && now - p.getLong("updateChecked", 0) < EVERY) return;
        if (AlarmScheduler.activeAlarm(app, 3 * 60 * 60_000L) > 0) return;   // keep the network quiet near an alarm
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(VERSION_URL).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setInstanceFollowRedirects(true);
                if (conn.getResponseCode() != 200) return;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = conn.getInputStream()) {
                    byte[] buf = new byte[4096]; int n, total = 0;
                    while ((n = in.read(buf)) > 0 && (total += n) < 64_000) out.write(buf, 0, n);
                }
                int latest = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8)).optInt("versionCode", 0);
                p.edit().putLong("updateChecked", System.currentTimeMillis()).putInt("latestVersion", latest).apply();
                if (latest > BuildConfig.VERSION_CODE && latest != known) result.newer(latest);
            } catch (Exception ignored) {
                // No internet or GitHub unreachable: try again next time
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "update-check").start();
    }
}
