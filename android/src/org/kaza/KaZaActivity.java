package org.kaza;

import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import org.qtproject.qt.android.bindings.QtActivity;

/**
 * Main activity. Once the user closes the app, Qt's main() has returned but Android keeps the
 * (now dead) Qt process cached; relaunching then reuses it and never restarts Qt, so the app
 * stays on the splash screen until force-stopped. Kill the UI process whenever the activity is
 * destroyed for good. The ":notification" service process is a different process and is not
 * affected.
 */
public class KaZaActivity extends QtActivity {
    private static final String TAG = "KaZaActivity";
    // Survives activity re-creation inside the same process: true means Qt already ran here
    private static boolean sStartedOnce = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "onCreate pid=" + Process.myPid() + " reusedProcess=" + sStartedOnce);
        if (sStartedOnce) {
            // Qt cannot be restarted in this process: die and let Android relaunch the activity
            Log.w(TAG, "Qt already ran in this process, killing it");
            Process.killProcess(Process.myPid());
            return;
        }
        sStartedOnce = true;
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "onDestroy pid=" + Process.myPid() + " finishing=" + isFinishing()
                + " changingConfig=" + isChangingConfigurations());
        if (!isChangingConfigurations()) {
            // super.onDestroy() waits for Qt to shut down and can block the main thread for good,
            // leaving a half-dead process that shows a black splash on relaunch: kill it anyway
            new Thread(() -> {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
                Log.w(TAG, "onDestroy watchdog: killing process");
                Process.killProcess(Process.myPid());
            }, "KaZaKillWatchdog").start();
        }
        super.onDestroy();
        if (!isChangingConfigurations()) {
            Process.killProcess(Process.myPid());
        }
    }
}
