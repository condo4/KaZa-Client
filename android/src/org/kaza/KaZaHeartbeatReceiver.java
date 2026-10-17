package org.kaza;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Receives the AlarmManager heartbeat armed by NotificationService. Alarms are the only timer that
 * fires while the CPU sleeps (Handler timers use uptime and stop in suspend/Doze), so this is what
 * keeps the server connection alive and re-established when the screen is off.
 * Runs in the ":notification" process, like the service.
 */
public class KaZaHeartbeatReceiver extends BroadcastReceiver {
    private static final String TAG = "KaZaHeartbeat";

    @Override
    public void onReceive(Context context, Intent intent) {
        NotificationService service = NotificationService.getInstance();
        if (service != null) {
            // Takes its own wake lock before returning, the alarm's one is released at return
            service.onHeartbeat();
            return;
        }

        // Service was killed (OEM task killer, crash): bring it back, it re-arms the alarm itself
        Log.w(TAG, "Heartbeat received without running service - restarting it");
        try {
            Intent serviceIntent = new Intent(context, NotificationService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Cannot restart NotificationService: " + e);
        }
    }
}
