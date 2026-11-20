package au.sunrise.alarm;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebResourceResponse;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Android side of Sunrise Alarm. The screens are a web page built into the APK (see LocalSite);
 * nothing loads from the website. This adds what a web page can't do:
 * open itself at sunrise, show over the lock screen, keep the screen on, control brightness,
 * play audio in the background and ring a native backup alarm if the page ever fails.
 */
public class MainActivity extends Activity {
    static final String APP_URL = "https://graemeblick-lang.github.io/sunrise-alarm/";
    static final String APP_HOST = "graemeblick-lang.github.io";
    /** Don't show the crash report this close to an alarm: the alarm matters more. */
    static final long QUIET_BEFORE_ALARM = 90 * 60_000;

    private WebView web;
    private NativeAudio audio;
    private SpotifyRemote spotify;
    private volatile boolean captureVolume = false;
    /**
     * True while the WebView shows someone else's page (only the Spotify sign-in pages can open in-app).
     * window.Android refuses to work then. Our own page is trusted by default, so a missed callback
     * can never silently stop alarms or music.
     */
    private volatile boolean foreignPage = false;
    private boolean pageLoaded = false;
    private long wakeAlarm = 0;   // the alarm this launch is for (0 = normal launch)
    /** A Spotify link shared to the app, waiting for the page to be ready. */
    private String[] pendingShare;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        File crash = new File(getFilesDir(), App.CRASH_FILE);
        boolean alarmSoon = isWake(getIntent()) || AlarmScheduler.activeAlarm(this, QUIET_BEFORE_ALARM) > 0;
        // The crash report waits for a normal daytime launch. It never stands between you and an alarm.
        if (crash.exists() && !alarmSoon) { showReport(crash); return; }
        try {
            start();
        } catch (Throwable t) {
            App.save(this, "Starting up", t);
            if (!alarmSoon) showReport(crash);
            // If this was an alarm launch, the native backup alarm will still ring
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

        audio = new NativeAudio(this);
        PlaybackService.listener = this::sendAudioEvent;
        spotify = new SpotifyRemote(this::sendAudioEvent);

        // A music fade that was cut short (app closed mid-alarm) left the media volume low: put it back
        if (AlarmScheduler.activeAlarm(this, 0) == 0) audio.musicRestore();
        // Make sure the system alarms match what's saved (cheap, and fixes anything Android dropped)
        AlarmScheduler.reschedule(this);

        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        } catch (Exception ignored) { }

        wakeAlarm = wakeAlarmFor(getIntent());
        takeShare(getIntent());
        buildWeb();
        loadApp();
        clearWakeNotifications();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void buildWeb() {
        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // lets the alarm sound start on its own
        s.setAllowFileAccess(false);                    // the page never needs the phone's files
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);       // the app's files come from the APK, never an old cached copy
        web.addJavascriptInterface(new Bridge(), "Android");

        // A service worker left over from when the app loaded the website must also get the built-in files
        try {
            ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest req) {
                    return LocalSite.serve(MainActivity.this, req.getUrl());
                }
            });
        } catch (Exception ignored) { }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                String host = u.getHost() == null ? "" : u.getHost();
                boolean web = scheme.equals("http") || scheme.equals("https");
                // The app and the Spotify login stay inside; everything else opens in the browser
                // (Spotify's own login pages may hop between its domains and Google/Apple/Facebook sign-in).
                boolean inside = web && (host.equals(APP_HOST) || isLoginHost(host));
                if (!inside) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                    return true;
                }
                return false;
            }

            /** Our own address is answered from the APK; nothing is fetched from the website. */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return LocalSite.serve(MainActivity.this, req.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) { trackHost(url); }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) { trackHost(url); }

            @Override
            public void onPageFinished(WebView view, String url) {
                trackHost(url);
                if (isAppUrl(Uri.parse(url))) { pageLoaded = true; deliverShare(); checkForUpdate(false); }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // The page's engine stopped (often low memory on old phones). Rebuild it in place and carry on,
                // straight back into the alarm if one is due.
                App.save(MainActivity.this, "WebView renderer stopped (crashed: " + detail.didCrash() + ")", new RuntimeException("Renderer gone"));
                rebuildWeb();
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (!APP_HOST.equals(Uri.parse(origin).getHost())) { cb.invoke(origin, false, false); return; }
                if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    cb.invoke(origin, true, false);
                } else {
                    geoOrigin = origin; geoCallback = cb;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 2);
                }
            }
        });
        setContentView(web);
    }

    private void rebuildWeb() {
        WebView old = web;
        web = null;
        try {
            if (old != null) {
                ((android.view.ViewGroup) old.getParent()).removeView(old);
                old.destroy();
            }
        } catch (Exception ignored) { }
        pageLoaded = false; foreignPage = false;
        wakeAlarm = 0;   // the page picks the alarm back up from its own saved state
        try { buildWeb(); loadApp(); } catch (Throwable t) { App.save(this, "Rebuilding the page", t); }
    }

    /** Open the app (served from the APK by LocalSite, at its usual address so saved settings are kept). */
    private void loadApp() {
        if (!LocalSite.hasApp(this)) throw new IllegalStateException("The app's web files are missing from this APK");
        foreignPage = false;
        web.loadUrl(APP_URL + hash());
    }

    /** Keeps a Spotify link shared to Sunrise (Spotify → Share → Sunrise). Anything else is ignored. */
    private boolean takeShare(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return false;
        String text = i.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null || !(text.contains("open.spotify.com/") || text.contains("spotify:"))) return false;
        String subject = i.getStringExtra(Intent.EXTRA_SUBJECT);
        pendingShare = new String[]{ text.length() > 1000 ? text.substring(0, 1000) : text, subject == null ? "" : subject };
        return true;
    }

    private void deliverShare() {
        if (pendingShare == null || web == null) return;
        String js = "window.nativeShared && window.nativeShared(" + JSONObject.quote(pendingShare[0]) + "," + JSONObject.quote(pendingShare[1]) + ")";
        pendingShare = null;
        web.evaluateJavascript(js, null);
    }

    /** Tell the page when a newer APK is on GitHub Releases. */
    private void checkForUpdate(boolean force) {
        UpdateCheck.check(this, force, code -> runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("window.nativeUpdate && window.nativeUpdate(" + code + ")", null);
        }));
    }

    private String hash() {
        if (wakeAlarm == 0) return "";
        return wakeAlarm > 0 ? "#wake=" + wakeAlarm : "#wake";
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (takeShare(intent) && pageLoaded) deliverShare();
        if (web != null && isWake(intent)) {
            long ms = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM, 0);
            if (pageLoaded) web.evaluateJavascript("window.nativeWake && window.nativeWake(" + ms + ")", null);
            else { wakeAlarm = ms > 0 ? ms : -1; }
            clearWakeNotifications();
        }
    }

    private void sendAudioEvent(String ev) {
        final String safe = ev.replaceAll("[^A-Za-z0-9:_-]", "");
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("window.nativeAudioEvent && window.nativeAudioEvent('" + safe + "')", null);
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean up = keyCode == KeyEvent.KEYCODE_VOLUME_UP, down = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
        if (captureVolume && web != null && (up || down)) {
            web.evaluateJavascript("window.nativeVolumeKey && window.nativeVolumeKey('" + (up ? "up" : "down") + "')", null);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (captureVolume && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (audio != null && AlarmScheduler.activeAlarm(this, 0) == 0) audio.musicRestore();
        if (spotify != null) spotify.disconnect();
        PlaybackService.listener = null;
        if (web != null) { try { web.destroy(); } catch (Exception ignored) { } web = null; }
        super.onDestroy();
    }

    @Override
    protected void onResume() { super.onResume(); try { goFullscreen(); } catch (Exception ignored) { } }

    /** Shows what went wrong last time, with a button to copy it. */
    private void showReport(File crash) {
        String text;
        try { text = new String(Files.readAllBytes(crash.toPath()), StandardCharsets.UTF_8); } catch (Exception e) { text = "Couldn't read the crash details: " + e; }
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
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Sunrise crash", report));
            copy.setText("Copied ✓");
        });
        Button cont = new Button(this); cont.setText("Continue");
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

    private void trackHost(String url) {
        if (url == null) return;
        if (url.startsWith("data:") || url.startsWith("about:")) return;
        String h = Uri.parse(url).getHost();
        if (h == null || h.isEmpty()) return;
        foreignPage = !APP_HOST.equals(h);
    }

    private static boolean isAppUrl(Uri u) { return u != null && APP_HOST.equals(u.getHost()); }

    private static boolean isLoginHost(String h) {
        return h.equals("spotify.com") || h.endsWith(".spotify.com") || h.equals("accounts.google.com")
                || h.equals("appleid.apple.com") || h.endsWith(".facebook.com");
    }

    private boolean isWake(Intent i) { return i != null && i.getBooleanExtra("wake", false); }

    /** The alarm time this launch is for: from the intent, or -1 if it's a wake without one, else 0. */
    private long wakeAlarmFor(Intent i) {
        if (!isWake(i)) return 0;
        long ms = i.getLongExtra(AlarmScheduler.EXTRA_ALARM, 0);
        return ms > 0 ? ms : -1;
    }

    private void clearWakeNotifications() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.cancel(AlarmReceiver.NOTIF_ID);
        nm.cancel(AlarmReceiver.BACKUP_NOTIF_ID);
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

    /**
     * Functions the web page can call as window.Android.*
     * Every call checks it's coming from our own page, so a login page or any other site the app
     * shows can't set alarms, play things or read the list of installed apps.
     */
    class Bridge {
        private boolean ok() { return !foreignPage; }

        // ----- alarms -----
        /** The next few alarms as JSON [[sunriseMs, alarmMs], ...]. "[]" turns automatic opening off. */
        @JavascriptInterface public void schedulePlan(String json) {
            if (!ok()) return;
            try { AlarmScheduler.setPlan(MainActivity.this, json); }
            catch (Throwable t) { App.save(MainActivity.this, "Scheduling alarms", t); }
        }
        @JavascriptInterface public void schedule(double sunriseMs, double alarmMs) {
            if (!ok()) return;
            try { AlarmScheduler.schedule(MainActivity.this, (long) sunriseMs, (long) alarmMs); }
            catch (Throwable t) { App.save(MainActivity.this, "Scheduling the alarm", t); }
        }
        @JavascriptInterface public void cancel() {
            if (!ok()) return;
            try { AlarmScheduler.cancel(MainActivity.this); } catch (Throwable t) { App.save(MainActivity.this, "Cancelling the alarm", t); }
        }
        /** A native backup for an alarm the page is handling (Goodnight, snooze). */
        @JavascriptInterface public void armBackup(double alarmMs) {
            if (!ok()) return;
            try { AlarmScheduler.armBackup(MainActivity.this, (long) alarmMs); } catch (Throwable t) { App.save(MainActivity.this, "Setting the backup alarm", t); }
        }
        @JavascriptInterface public void cancelBackup() {
            if (!ok()) return;
            try { AlarmScheduler.cancelBackup(MainActivity.this); } catch (Throwable t) { App.save(MainActivity.this, "Clearing the backup alarm", t); }
        }
        /** The page is ringing: the native backup can stand down. */
        @JavascriptInterface public void alarmRinging() {
            if (!ok()) return;
            AlarmScheduler.markRinging(MainActivity.this);
            runOnUiThread(() -> PlaybackService.stopBackup(MainActivity.this));
        }

        /** The phone's Material You colours (from the wallpaper), Android 12 and later. */
        @JavascriptInterface public String systemColors() {
            if (!ok() || Build.VERSION.SDK_INT < 31) return "";
            int[] ids = { android.R.color.system_accent1_100, android.R.color.system_accent1_200, android.R.color.system_accent1_300,
                    android.R.color.system_accent1_500, android.R.color.system_accent1_700, android.R.color.system_accent1_800,
                    android.R.color.system_accent1_900, android.R.color.system_accent2_700, android.R.color.system_neutral1_900 };
            String[] names = { "a1_100", "a1_200", "a1_300", "a1_500", "a1_700", "a1_800", "a1_900", "a2_700", "n1_900" };
            try {
                JSONObject o = new JSONObject();
                for (int i = 0; i < ids.length; i++) o.put(names[i], String.format("#%06x", 0xFFFFFF & getColor(ids[i])));
                return o.toString();
            } catch (Exception e) { return ""; }
        }

        // ----- any music app -----
        /** Installed apps that accept play/pause buttons, as JSON [{pkg,label}]. */
        @JavascriptInterface public String listMusicApps() {
            if (!ok()) return "[]";
            PackageManager pm = getPackageManager();
            List<ResolveInfo> found = pm.queryBroadcastReceivers(new Intent(Intent.ACTION_MEDIA_BUTTON), 0);
            Map<String, String> apps = new LinkedHashMap<>();
            for (ResolveInfo r : found) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || apps.containsKey(pkg)) continue;
                try { apps.put(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()); } catch (Exception ignored) { }
            }
            JSONArray arr = new JSONArray();
            try {
                for (Map.Entry<String, String> e : apps.entrySet()) arr.put(new JSONObject().put("pkg", e.getKey()).put("label", e.getValue()));
            } catch (Exception ignored) { }
            return arr.toString();
        }
        @JavascriptInterface public boolean appWake(String pkg) { if (!ok()) return false; audio.musicFadeStart(); return audio.appKey(pkg, KeyEvent.KEYCODE_MEDIA_PLAY); }
        @JavascriptInterface public void appPause(String pkg) { if (ok()) audio.appKey(pkg, KeyEvent.KEYCODE_MEDIA_PAUSE); }
        @JavascriptInterface public void musicFadeStart() { if (ok()) audio.musicFadeStart(); }
        /**
         * Presses play through Android itself, like a headphone button. Reaches the app that last played
         * (Android lets it start from cold, which a direct press can't always do on Android 12+).
         */
        @JavascriptInterface public void mediaPlayAny() { if (ok()) audio.systemPlay(); }

        // ----- Spotify app (official App Remote SDK) -----
        /** The Spotify developer Client ID built into this app (empty if none). */
        @JavascriptInterface public String spotifyClientId() { return ok() ? BuildConfig.SPOTIFY_CLIENT_ID : ""; }
        @JavascriptInterface public void spotifyRemoteAuth(String clientId) {
            if (ok()) runOnUiThread(() -> spotify.connect(getApplicationContext(), clientId, true, null, false));
        }
        @JavascriptInterface public void spotifyRemote(String clientId, String uri, boolean shuffle) {
            if (ok()) runOnUiThread(() -> spotify.connect(getApplicationContext(), clientId, false, uri, shuffle));
        }
        @JavascriptInterface public void spotifyRemotePause() { if (ok()) runOnUiThread(() -> spotify.pause()); }

        /** While the alarm's music plays, the phone's volume buttons control it. */
        @JavascriptInterface public void captureVolumeKeys(boolean on) { captureVolume = on && ok(); }

        // ----- radio (plays in a background media service, so it keeps going if you switch apps) -----
        @JavascriptInterface public boolean radioService() { return true; }
        @JavascriptInterface public void radioPlay(String url, double volume) { radioPlay(url, volume, "Radio"); }
        @JavascriptInterface public void radioPlay(String url, double volume, String title) {
            if (!ok() || url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
            runOnUiThread(() -> PlaybackService.play(MainActivity.this, url, title, (float) volume));
        }
        @JavascriptInterface public void radioVolume(double v) { if (ok()) runOnUiThread(() -> PlaybackService.setVolume(MainActivity.this, (float) v)); }
        @JavascriptInterface public void radioStop() { if (ok()) runOnUiThread(() -> PlaybackService.stop(MainActivity.this)); }

        // ----- Spotify by media button, and media volume -----
        /** Turns media volume down, then presses play in Spotify so it wakes up. */
        @JavascriptInterface public boolean spotifyWake() { if (!ok()) return false; audio.musicFadeStart(); return audio.spotifyKey(KeyEvent.KEYCODE_MEDIA_PLAY); }
        @JavascriptInterface public void spotifyPause() { if (ok()) audio.spotifyKey(KeyEvent.KEYCODE_MEDIA_PAUSE); }
        @JavascriptInterface public void musicVolume(double f) { if (ok()) audio.setMusic((float) f); }
        @JavascriptInterface public void musicRestore() { if (ok()) audio.musicRestore(); }
        /** True if any app is playing audio right now (used to check Spotify really started). */
        @JavascriptInterface public boolean musicActive() {
            return ok() && getSystemService(AudioManager.class).isMusicActive();
        }

        /** 0..1 sets the screen brightness for this app; below 0 hands control back to the phone. */
        @JavascriptInterface public void setBrightness(final float v) {
            if (!ok()) return;
            runOnUiThread(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = v < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : Math.max(0.002f, Math.min(1f, v));
                getWindow().setAttributes(lp);
            });
        }

        /** Comma-separated list of settings that would stop the alarm working properly. */
        @JavascriptInterface public String problems() {
            StringBuilder sb = new StringBuilder();
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (!nm.areNotificationsEnabled()) sb.append("notifications,");
            if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) sb.append("fullscreen,");
            if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager.class).canScheduleExactAlarms()) sb.append("exact,");
            if (!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) sb.append("battery,");
            return sb.toString();
        }

        /** True when the app has the native backup alarm (so the page can say so). */
        @JavascriptInterface public boolean hasBackupAlarm() { return true; }

        // ----- version and updates -----
        @JavascriptInterface public String appVersion() { return BuildConfig.VERSION_NAME; }
        @JavascriptInterface public void checkUpdate() { if (ok()) runOnUiThread(() -> checkForUpdate(true)); }
        /** Opens the latest APK download in the browser; Android then offers to install it. */
        @JavascriptInterface public void openUpdate() {
            if (!ok()) return;
            runOnUiThread(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(UpdateCheck.APK_URL))); } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface public void fix(String what) {
            if (!ok()) return;
            Intent i;
            Uri pkg = Uri.parse("package:" + getPackageName());
            if ("fullscreen".equals(what) && Build.VERSION.SDK_INT >= 34) {
                i = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg);
            } else if ("exact".equals(what) && Build.VERSION.SDK_INT >= 31) {
                i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg);
            } else if ("battery".equals(what)) {
                i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg);
            } else {
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            }
            try { startActivity(i); } catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)); } catch (Exception ignored) { }
            }
        }
    }
}
