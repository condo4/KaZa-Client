package org.kaza;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.NotificationChannel;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import org.qtproject.qt.android.bindings.QtService;

public class LocalService extends QtService
{
    private static final String TAG = "LocalService";
    private static final long INTERVAL = 15 * 1000; // 1 minute en millisecondes
    private static final int NOTIFICATION_ID = 1001;
    private static final String ACTION_TASK = "org.kaza.action.TASK";

    private static LocalService instance = null;

    public static synchronized LocalService getInstance() {
        return instance;
    }

    public void notify(String title, String message) {
        try {
            NotificationManager m_notificationManager = (NotificationManager)
                    this.getSystemService(Context.NOTIFICATION_SERVICE);

            Notification.Builder m_builder;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                int importance = NotificationManager.IMPORTANCE_DEFAULT;
                NotificationChannel notificationChannel;
                notificationChannel = new NotificationChannel("Qt", "Qt Notifier", importance);
                m_notificationManager.createNotificationChannel(notificationChannel);
                m_builder = new Notification.Builder(this, notificationChannel.getId());
            } else {
                m_builder = new Notification.Builder(this);
            }

            Bitmap icon = BitmapFactory.decodeResource(this.getResources(), R.drawable.icon);
            m_builder.setSmallIcon(R.drawable.icon)
                    .setLargeIcon(icon)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setDefaults(Notification.DEFAULT_SOUND)
                    .setColor(Color.GREEN)
                    .setAutoCancel(true);

            m_notificationManager.notify(0, m_builder.build());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "KaZoELOG: Creating Service");

        instance = this;

        //startForeground(NOTIFICATION_ID, createForegroundNotification());

        nativeOnCreate();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "KaZoELOG: Destroying Service");

        instance = null;

        scheduleTask();

        nativeOnDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int ret = super.onStartCommand(intent, flags, startId);

        if (intent != null && ACTION_TASK.equals(intent.getAction())) {
            // Call cyclic task
            performTask();
        } else {
            // First service start, program cyclic task
            scheduleTask();
        }

        // If service is dead, it will be restart
        return START_STICKY;
    }

    private Notification createForegroundNotification() {
        NotificationManager notificationManager = (NotificationManager)
                this.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder builder;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                "service_channel",
                "Service Channel",
                NotificationManager.IMPORTANCE_LOW
            );
            notificationManager.createNotificationChannel(channel);
            builder = new Notification.Builder(this, channel.getId());
        } else {
            builder = new Notification.Builder(this);
        }

        Bitmap icon = BitmapFactory.decodeResource(this.getResources(), R.drawable.icon);
        return builder.setSmallIcon(R.drawable.icon)
                .setLargeIcon(icon)
                .setContentTitle("KaZa Service")
                .setContentText("Background KaZa Service")
                .build();
    }

    private void scheduleTask() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, LocalService.class);
        intent.setAction(ACTION_TASK);

        // FLAG_IMMUTABLE required for Android 12+
        PendingIntent pendingIntent = PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Program next run
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + INTERVAL,
            pendingIntent
        );
    }

    // Function called cyclic
    private void performTask() {
        // Call Qt C++ function
        try {
            taskNative();
        } catch (Exception e) {
            Log.e(TAG, "Exception in taskNative", e);
        }

        // Schedule the next call
        scheduleTask();
    }

    // Qt native method implemented in C++
    private native void taskNative();
    private native void nativeOnCreate();
    private native void nativeOnDestroy();
}
