package au.sunrise.alarm;

import android.app.Application;
import android.os.Build;

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
