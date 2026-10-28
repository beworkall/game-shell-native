package app.webgameshell.nativeshell;

import android.app.Application;
import android.util.Log;
import java.io.File;
import java.util.Date;

/** Saves any crash (launcher or game process) so the launcher can show it next time. */
public final class ShellApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                Shell.write(new File(getFilesDir(), "crash.txt"), new Date() + "\nprocess: " + android.os.Process.myPid() + " thread: " + thread.getName() + "\n" + Log.getStackTraceString(error));
            } catch (Throwable ignored) { }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }
}
