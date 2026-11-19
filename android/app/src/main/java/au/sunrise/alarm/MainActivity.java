package au.sunrise.alarm;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.RenderProcessGoneDetail;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import java.io.File;
import java.nio.file.Files;

/**
 * A thin Android shell around the Sunrise Alarm web app.
 * The app itself is the web page; this adds what a web page can't do:
 * open itself at sunrise, show over the lock screen, keep the screen on and control brightness.
 */
public class MainActivity extends Activity {
    static final String APP_URL = "https://graemeblick-lang.github.io/sunrise-alarm/";
    static final String APP_HOST = "graemeblick-lang.github.io";

    private WebView web;
    private NativeAudio audio;
    private SpotifyRemote spotify;
    private volatile boolean captureVolume = false;
    private boolean pageLoaded = false, usingBundled = false, launchWake = false;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        File crash = new File(getFilesDir(), App.CRASH_FILE);
        if (crash.exists()) { showReport(crash); return; }
        try {
            start();
        } catch (Throwable t) {
            App.save(this, "Starting up", t);
            showReport(crash);
        }
    }

    private void start() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try { goFullscreen(); } catch (Exception ignored) { }

        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        audio = new NativeAudio(this, this::sendAudioEvent);
        PlaybackService.listener = this::sendAudioEvent;
        spotify = new SpotifyRemote(this::sendAudioEvent);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // lets the alarm sound start on its own
        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                String host = u.getHost() == null ? "" : u.getHost();
                boolean external = !(scheme.equals("http") || scheme.equals("https")) || host.endsWith("willyweather.com.au");
                if (external) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                    return true;
                }
                return false;   // Spotify login and the app itself stay inside
            }

            @Override
            public void onPageFinished(WebView view, String url) { pageLoaded = true; }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, android.webkit.WebResourceError err) {
                if (req.isForMainFrame()) loadBundled();
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest req, android.webkit.WebResourceResponse resp) {
                if (req.isForMainFrame() && resp.getStatusCode() >= 400) loadBundled();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // The page's engine stopped; restart cleanly instead of letting Android close the app
                App.save(MainActivity.this, "WebView renderer stopped (crashed: " + detail.didCrash() + ")", new RuntimeException("Renderer gone"));
                recreate();
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    cb.invoke(origin, true, false);
                } else {
                    geoOrigin = origin; geoCallback = cb;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 2);
                }
            }
        });

        setContentView(web);

        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        } catch (Exception ignored) { }

        launchWake = isWake(getIntent());
        web.loadUrl(APP_URL + (launchWake ? "#wake" : ""));
        // If the website hasn't loaded within 10 s (no internet, GitHub down), use the copy built into the app
        web.postDelayed(() -> { if (!pageLoaded) loadBundled(); }, 10000);
        clearWakeNotification();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (web != null && isWake(intent)) {
            web.evaluateJavascript("window.nativeWake && window.nativeWake()", null);
            clearWakeNotification();
        }
    }

    private void sendAudioEvent(String ev) {
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("window.nativeAudioEvent && window.nativeAudioEvent('" + ev.replace("'", "") + "')", null);
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        boolean up = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP, down = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
        if (captureVolume && web != null && (up || down)) {
            web.evaluateJavascript("window.nativeVolumeKey && window.nativeVolumeKey('" + (up ? "up" : "down") + "')", null);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
        if (captureVolume && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (audio != null) { audio.radioStop(); audio.musicRestore(); }
        if (spotify != null) spotify.disconnect();
        PlaybackService.listener = null;
        super.onDestroy();
    }

    @Override
    protected void onResume() { super.onResume(); try { goFullscreen(); } catch (Exception ignored) { } }

    /** Shows what went wrong last time, with a button to copy it. */
    private void showReport(File crash) {
        String text;
        try { text = new String(Files.readAllBytes(crash.toPath())); } catch (Exception e) { text = "Couldn't read the crash details: " + e; }
        final String report = text;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 80, 40, 40);
        box.setBackgroundColor(0xFF15110F);

        TextView title = new TextView(this);
        title.setText("Sunrise Alarm hit a problem last time.\nTap Copy and paste it to Claude.");
        title.setTextColor(0xFFFFB36B); title.setTextSize(18); title.setPadding(0, 0, 0, 30);
        box.addView(title);

        LinearLayout buttons = new LinearLayout(this);
        Button copy = new Button(this); copy.setText("Copy");
        copy.setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Sunrise crash", report));
            copy.setText("Copied ✓");
        });
        Button cont = new Button(this); cont.setText("Try again");
        cont.setOnClickListener(v -> { crash.delete(); recreate(); });
        buttons.addView(copy); buttons.addView(cont);
        box.addView(buttons);

        ScrollView sv = new ScrollView(this);
        TextView body = new TextView(this);
        body.setText(report); body.setTextColor(0xFFE8DCD0); body.setTextSize(11);
        body.setTypeface(Typeface.MONOSPACE); body.setTextIsSelectable(true); body.setPadding(0, 30, 0, 0);
        sv.addView(body);
        box.addView(sv);
        setContentView(box);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else moveTaskToBack(true);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == 2 && geoCallback != null) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            geoCallback.invoke(geoOrigin, ok, false);
            geoCallback = null;
        }
    }

    /** Show the copy of the app that's built into the APK, on the same web address so settings are kept. */
    private void loadBundled() {
        if (usingBundled || web == null) return;
        usingBundled = true;
        try (java.io.InputStream in = getAssets().open("www/index.html")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            String html = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            web.loadDataWithBaseURL(APP_URL + (launchWake ? "#wake" : ""), html, "text/html", "utf-8", null);
        } catch (Exception e) {
            App.save(this, "Loading the built-in copy", e);
        }
    }

    private boolean isWake(Intent i) { return i != null && i.getBooleanExtra("wake", false); }

    private void clearWakeNotification() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(AlarmReceiver.NOTIF_ID);
    }

    private void goFullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    /** Functions the web page can call as window.Android.* */
    class Bridge {
        @JavascriptInterface public void schedule(double sunriseMs, double alarmMs) {
            try { AlarmScheduler.schedule(MainActivity.this, (long) sunriseMs, (long) alarmMs); }
            catch (Throwable t) { App.save(MainActivity.this, "Scheduling the alarm", t); }
        }

        /** The phone's Material You colours (from the wallpaper), Android 12 and later. */
        @JavascriptInterface public String systemColors() {
            if (Build.VERSION.SDK_INT < 31) return "";
            int[] ids = { android.R.color.system_accent1_100, android.R.color.system_accent1_200, android.R.color.system_accent1_300,
                    android.R.color.system_accent1_500, android.R.color.system_accent1_700, android.R.color.system_accent1_800,
                    android.R.color.system_accent1_900, android.R.color.system_accent2_700, android.R.color.system_neutral1_900 };
            String[] names = { "a1_100", "a1_200", "a1_300", "a1_500", "a1_700", "a1_800", "a1_900", "a2_700", "n1_900" };
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < ids.length; i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(names[i]).append("\":\"").append(String.format("#%06x", 0xFFFFFF & getColor(ids[i]))).append('"');
            }
            return sb.append('}').toString();
        }

        // ----- any music app -----
        /** Installed apps that accept play/pause buttons, as JSON [{pkg,label}]. */
        @JavascriptInterface public String listMusicApps() {
            android.content.pm.PackageManager pm = getPackageManager();
            java.util.List<android.content.pm.ResolveInfo> found = pm.queryBroadcastReceivers(new Intent(Intent.ACTION_MEDIA_BUTTON), 0);
            java.util.LinkedHashMap<String, String> apps = new java.util.LinkedHashMap<>();
            for (android.content.pm.ResolveInfo r : found) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || apps.containsKey(pkg)) continue;
                try { apps.put(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()); } catch (Exception ignored) { }
            }
            StringBuilder sb = new StringBuilder("[");
            for (java.util.Map.Entry<String, String> e : apps.entrySet()) {
                if (sb.length() > 1) sb.append(',');
                sb.append("{\"pkg\":\"").append(e.getKey()).append("\",\"label\":\"").append(e.getValue().replace("\"", "")).append("\"}");
            }
            return sb.append(']').toString();
        }
        @JavascriptInterface public boolean appWake(String pkg) { audio.musicFadeStart(); return audio.appKey(pkg, android.view.KeyEvent.KEYCODE_MEDIA_PLAY); }
        @JavascriptInterface public void appPause(String pkg) { audio.appKey(pkg, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE); }
        @JavascriptInterface public void musicFadeStart() { audio.musicFadeStart(); }

        // ----- Spotify app (official App Remote SDK) -----
        @JavascriptInterface public void spotifyRemoteAuth(String clientId) {
            runOnUiThread(() -> spotify.connect(getApplicationContext(), clientId, true, null, false));
        }
        @JavascriptInterface public void spotifyRemote(String clientId, String uri, boolean shuffle) {
            runOnUiThread(() -> spotify.connect(getApplicationContext(), clientId, false, uri, shuffle));
        }
        @JavascriptInterface public void spotifyRemotePause() { runOnUiThread(() -> spotify.pause()); }

        /** While the alarm's music plays, the phone's volume buttons control it. */
        @JavascriptInterface public void captureVolumeKeys(boolean on) { captureVolume = on; }

        // ----- audio -----
        // Radio plays in a background media service, so it keeps going if you switch apps
        @JavascriptInterface public boolean radioService() { return true; }
        @JavascriptInterface public void radioPlay(String url, double volume) { radioPlay(url, volume, "Radio"); }
        @JavascriptInterface public void radioPlay(String url, double volume, String title) {
            runOnUiThread(() -> PlaybackService.play(MainActivity.this, url, title, (float) volume));
        }
        @JavascriptInterface public void radioVolume(double v) { runOnUiThread(() -> PlaybackService.setVolume(MainActivity.this, (float) v)); }
        @JavascriptInterface public void radioStop() { runOnUiThread(() -> PlaybackService.stop(MainActivity.this)); }
        /** Turns media volume down, then presses play in Spotify so it wakes up. */
        @JavascriptInterface public boolean spotifyWake() { audio.musicFadeStart(); return audio.spotifyKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY); }
        @JavascriptInterface public void spotifyPause() { audio.spotifyKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE); }
        @JavascriptInterface public void musicVolume(double f) { audio.setMusic((float) f); }
        @JavascriptInterface public void musicRestore() { audio.musicRestore(); }
        /** True if any app is playing audio right now (used to check Spotify really started). */
        @JavascriptInterface public boolean musicActive() {
            return ((android.media.AudioManager) getSystemService(AUDIO_SERVICE)).isMusicActive();
        }

        @JavascriptInterface public void cancel() {
            try { AlarmScheduler.cancel(MainActivity.this); } catch (Throwable t) { App.save(MainActivity.this, "Cancelling the alarm", t); }
        }

        /** 0..1 sets the screen brightness for this app; below 0 hands control back to the phone. */
        @JavascriptInterface public void setBrightness(final float v) {
            runOnUiThread(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = v < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : Math.max(0.002f, Math.min(1f, v));
                getWindow().setAttributes(lp);
            });
        }

        /** Comma-separated list of settings that would stop the app opening itself. */
        @JavascriptInterface public String problems() {
            StringBuilder sb = new StringBuilder();
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (!nm.areNotificationsEnabled()) sb.append("notifications,");
            if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) sb.append("fullscreen,");
            return sb.toString();
        }

        @JavascriptInterface public void fix(String what) {
            Intent i;
            if ("fullscreen".equals(what) && Build.VERSION.SDK_INT >= 34) {
                i = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getPackageName()));
            } else {
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            }
            try { startActivity(i); } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }
        }
    }
}
