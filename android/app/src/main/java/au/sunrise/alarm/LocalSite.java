package au.sunrise.alarm;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Serves the web app from inside the APK, at its usual web address.
 *
 * The page keeps the address https://graemeblick-lang.github.io/sunrise-alarm/ so everything saved under it
 * (settings, Spotify and Todoist logins) carries on as before, and the Spotify login can still
 * return to it. But every request to that address is answered here from the APK's own files:
 * nothing is fetched from the website, so it works with no internet and can't be changed from outside.
 */
final class LocalSite {
    static final String HOST = MainActivity.APP_HOST;
    static final String BASE_PATH = "/sunrise-alarm/";

    private LocalSite() { }

    /** A response for requests to our own address (null for anything else, which goes to the network as normal). */
    static WebResourceResponse serve(Context c, Uri u) {
        if (u == null || !HOST.equals(u.getHost())) return null;
        String path = u.getPath() == null ? "/" : u.getPath();
        if (path.equals("/sunrise-alarm")) path = BASE_PATH;
        if (!path.startsWith(BASE_PATH)) return notFound();
        String rel = path.substring(BASE_PATH.length());
        if (rel.isEmpty()) rel = "index.html";
        if (rel.contains("..") || rel.startsWith("/")) return notFound();
        try {
            InputStream in = c.getAssets().open("www/" + rel);
            String mime = mime(rel);
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-cache");
            return new WebResourceResponse(mime, mime.startsWith("text/") || mime.endsWith("json") ? "utf-8" : null,
                    200, "OK", headers, in);
        } catch (Exception e) {
            return notFound();
        }
    }

    static boolean hasApp(Context c) {
        try (InputStream ignored = c.getAssets().open("www/index.html")) { return true; } catch (Exception e) { return false; }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<>(),
                new ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".js")) return "text/javascript";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".webmanifest")) return "application/manifest+json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".woff")) return "font/woff";
        if (n.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }
}
