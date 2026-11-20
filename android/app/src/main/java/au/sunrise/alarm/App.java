package au.sunrise.alarm;

import android.app.Application;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Saves the details of any crash so the next launch can show them. */
public class App extends Application {
    static final String CRASH_FILE = "last-crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            save(this, "Thread: " + thread.getName(), error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
        // Plugged in while the app is running: maybe time for the night screen (Evening.check).
        // Android doesn't deliver this to apps that aren't running, so Evening also checks every 10 minutes.
        try {
            ContextCompat.registerReceiver(this, new EveningReceiver(),
                    new IntentFilter(Intent.ACTION_POWER_CONNECTED), ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) { save(this, "Watching for the charger", t); }
    }

    static void save(android.content.Context c, String where, Throwable error) {
        try {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            String text = "Sunrise Alarm crash\n"
                    + "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "), " + Build.MODEL + "\n"
                    + where + "\n\n" + sw;
            FileWriter w = new FileWriter(new File(c.getFilesDir(), CRASH_FILE));
            w.write(text);
            w.close();
        } catch (Exception ignored) { }
    }
}
