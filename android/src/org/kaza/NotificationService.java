package org.kaza;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.pm.ServiceInfo;
import android.content.SharedPreferences;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * Pure Java foreground service for background tasks
 * No Qt dependencies - can run independently of main app
 *
 * Keeps one mTLS connection to KaZa-Server. While the CPU sleeps, an AlarmManager heartbeat
 * (KaZaHeartbeatReceiver) wakes it up under a partial wake lock to PING or reconnect.
 */
public class NotificationService extends Service
{
    private static final String TAG = "NotificationService";
    private static final int NOTIFICATION_ID = 1001; // ID for foreground service notification
    private static final String PREFS_NAME = "NotificationServicePrefs";

    private static NotificationService instance = null;

    // SSL Configuration
    private String sslClientCert = null;
    private String sslCaCert = null;
    private String sslClientKey = null;
    private String sslClientPass = null;
    private String sslHost = null;
    private int sslPort = 0;
    private String username = null;
    private boolean configured = false;

    // SSL Connection.
    // State transitions happen on connectionHandler. `conn` is the only live connection and is
    // identified by a generation number: callbacks of a closed connection (reader thread, timers)
    // are ignored instead of tearing down its successor.
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int HANDSHAKE_TIMEOUT_MS = 15000;
    private static final int PONG_TIMEOUT_MS = 20000;
    private static final int PONG_LATE_GRACE_MS = 5000;           // timer fired late (device was asleep)
    private static final int CONFIG_ERROR_RETRY_MS = 5 * 60 * 1000;

    private enum State { DISCONNECTED, CONNECTING, CONNECTED }

    private static class Conn {
        final int generation;
        final SSLSocket socket;
        final KaZaProtocol protocol;
        final Network network;          // default network at connection time
        volatile boolean closed = false;

        Conn(int generation, SSLSocket socket, KaZaProtocol protocol, Network network) {
            this.generation = generation;
            this.socket = socket;
            this.protocol = protocol;
            this.network = network;
        }
    }

    private volatile State state = State.DISCONNECTED;
    private volatile Conn conn = null;
    private volatile boolean isConnected = false;
    private int connGeneration = 0;
    private HandlerThread connectionThread = null;
    private Handler connectionHandler = null;
    private final ExecutorService positionExecutor = Executors.newSingleThreadExecutor();

    // Reconnection state: never gives up, only slows down
    private int reconnectAttempts = 0;
    private static final int RECONNECT_BASE_DELAY_MS = 2000; // 2 seconds
    private static final int RECONNECT_MAX_DELAY_MS = 60000; // 60 seconds
    private Runnable reconnectRunnable = null;
    private volatile String lastError = null;
    private volatile boolean configError = false;

    // Keep-alive - Adaptive intervals for battery optimization
    private static final int KEEPALIVE_SCREEN_ON_MS = 10000;      // 10s when screen is on (fast response)
    private static final int KEEPALIVE_BURST_MODE_MS = 10000;     // 10s during position tracking burst
    private static final int KEEPALIVE_WIFI_MS = 60000;           // 60s on WiFi (stable connection)
    private static final int KEEPALIVE_MOBILE_MS = 90000;         // 90s on mobile data (moderate save)
    private static final int KEEPALIVE_SCREEN_OFF_MS = 90000;     // 90s when screen off (battery save)
    private static final int KEEPALIVE_IDLE_MS = 120000;          // 120s when device idle (max save)
    private static final int KEEPALIVE_DOZE_MS = 15 * 60 * 1000;  // 15 min when in Doze mode (Android compliance)

    // Battery-aware intervals - Conservative intervals when battery is low
    private static final int KEEPALIVE_LOW_BATTERY_SCREEN_OFF_MS = 180000;  // 3 min when battery < 15%
    private static final int KEEPALIVE_LOW_BATTERY_IDLE_MS = 240000;        // 4 min idle in low battery
    private static final int LOW_BATTERY_THRESHOLD = 15;                    // Battery percentage threshold
    private static final int BATTERY_RESTORE_THRESHOLD = 20;                // Restore normal behavior above this

    // Burst mode - Fast response after position request for 2 minutes
    private static final long BURST_MODE_DURATION_MS = 2 * 60 * 1000; // 2 minutes initial
    private static final long BURST_MODE_MAX_DURATION_MS = 5 * 60 * 1000; // 5 minutes max
    private static final long BURST_MODE_EXTENSION_MS = 1 * 60 * 1000; // Extend by 1 minute
    private static final long BURST_MODE_IDLE_TIMEOUT_MS = 30000; // Exit if no request for 30s
    private volatile long burstModeEndTime = 0; // Timestamp when burst mode ends (0 = disabled)
    private volatile long burstModeStartTime = 0; // Timestamp when burst mode started

    // GPS/Location tracker
    private KaZaTracker tracker = null;

    private int currentKeepAliveInterval = KEEPALIVE_SCREEN_ON_MS; // Current active interval
    private Runnable keepAliveRunnable = null;
    private Handler keepAliveHandler = null;

    // Connection health monitoring
    private volatile long lastMessageReceivedTime = System.currentTimeMillis();
    private Runnable pongCheckRunnable = null;

    // Heartbeat: AlarmManager is the only timer that fires while the CPU sleeps (Handler timers
    // use uptime). Each beat holds a partial wake lock long enough for PING/PONG or a reconnection
    // (connect 10 s + handshake 15 s) and re-arms the next one.
    private static final long HEARTBEAT_WIFI_MS = 2 * 60 * 1000;
    private static final long HEARTBEAT_MOBILE_MS = 4 * 60 * 1000;
    private static final long HEARTBEAT_DISCONNECTED_MS = 60 * 1000;
    private static final long HEARTBEAT_LOW_BATTERY_FACTOR = 2;
    private static final long HEARTBEAT_WAKELOCK_MS = 40 * 1000;
    private static final int HEARTBEAT_REQUEST_CODE = 42;
    // Keeps the Wi-Fi radio out of power-save while connected on Wi-Fi: otherwise incoming
    // packets (notifications) can be delayed by several seconds with the screen off.
    private static final boolean USE_WIFI_LOCK = true;
    private AlarmManager alarmManager = null;
    private PowerManager.WakeLock heartbeatLock = null;
    private WifiManager.WifiLock wifiLock = null;

    // Device state monitoring (battery, network, screen, doze)
    private KaZaDeviceStateMonitor deviceMonitor = null;

    // App Standby Bucket monitoring (API 28+)
    private int currentStandbyBucket = -1;
    private Handler networkCheckHandler = null;
    private Runnable networkCheckRunnable = null;
    private static final long NETWORK_CHECK_INTERVAL_MS = 5 * 60 * 1000; // Check every 5 minutes

    public static synchronized NotificationService getInstance() {
        return instance;
    }

    public static synchronized boolean isServiceRunning() {
        return instance != null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // This is a started service, not a bound service
        return null;
    }

    /**
     * Configure SSL connection parameters
     * @param clientCert Path to client certificate
     * @param caCert Path to CA certificate
     * @param clientKey Path to client key
     * @param clientPass Client key password
     * @param host Server host
     * @param port Server port
     * @param user Username
     * @return "OK" if successful, error message otherwise
     */
    public String configure(String clientCert, String caCert, String clientKey,
                           String clientPass, String host, int port, String user) {
        Log.i(TAG, "KaZaService: Configuration received");
        Log.i(TAG, "KaZaService: Host: " + host + ":" + port);
        Log.i(TAG, "KaZaService: Username: " + user);
        Log.i(TAG, "KaZaService: Client cert: " + clientCert);
        Log.i(TAG, "KaZaService: CA cert: " + caCert);
        Log.i(TAG, "KaZaService: Client key: " + clientKey);

        this.sslClientCert = clientCert;
        this.sslCaCert = caCert;
        this.sslClientKey = clientKey;
        this.sslClientPass = clientPass;
        this.sslHost = host;
        this.sslPort = port;
        this.username = user;
        this.configured = true;

        // Save configuration to SharedPreferences
        saveConfiguration();

        Log.i(TAG, "KaZaService: Configuration stored successfully");

        // Try to connect to server
        connectToServer();

        return "OK";
    }

    /**
     * Save SSL configuration to SharedPreferences
     */
    private void saveConfiguration() {
        Log.i(TAG, "KaZaService: Saving configuration to persistent storage");
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        editor.putString("ssl_client_cert", sslClientCert);
        editor.putString("ssl_ca_cert", sslCaCert);
        editor.putString("ssl_client_key", sslClientKey);
        editor.putString("ssl_client_pass", sslClientPass);
        editor.putString("ssl_host", sslHost);
        editor.putInt("ssl_port", sslPort);
        editor.putString("username", username);
        editor.putBoolean("configured", configured);

        boolean saved = editor.commit();
        Log.i(TAG, "KaZaService: Configuration saved: " + saved);
    }

    /**
     * Load SSL configuration from SharedPreferences
     */
    private void loadConfiguration() {
        Log.i(TAG, "KaZaService: Loading configuration from persistent storage");
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        this.sslClientCert = prefs.getString("ssl_client_cert", null);
        this.sslCaCert = prefs.getString("ssl_ca_cert", null);
        this.sslClientKey = prefs.getString("ssl_client_key", null);
        this.sslClientPass = prefs.getString("ssl_client_pass", null);
        this.sslHost = prefs.getString("ssl_host", null);
        this.sslPort = prefs.getInt("ssl_port", 0);
        this.username = prefs.getString("username", null);
        this.configured = prefs.getBoolean("configured", false);

        if (configured) {
            Log.i(TAG, "KaZaService: Configuration loaded successfully");
            Log.i(TAG, "KaZaService: Host: " + sslHost + ":" + sslPort);
            Log.i(TAG, "KaZaService: Username: " + username);
        } else {
            Log.i(TAG, "KaZaService: No saved configuration found");
        }
    }

    /**
     * Query the service with a command string
     * @param query The query string (e.g., "status", "info", "data:key")
     * @return Response string from the service
     */
    public String query(String query) {
        Log.i(TAG, "KaZaService: Query received: " + query);

        if (query == null || query.isEmpty()) {
            return "ERROR: Empty query";
        }

        // Parse query and return appropriate response
        String[] parts = query.split(":", 2);
        String command = parts[0].toLowerCase();

        switch (command) {
            case "status":
                return getServiceStatus();

            case "pid":
                return String.valueOf(android.os.Process.myPid());

            case "uptime":
                // TODO: Track service start time and calculate uptime
                return "uptime_not_implemented";

            case "info":
                return "NotificationService v1.0 - PID:" + android.os.Process.myPid();

            case "ping":
                return "pong";

            case "connection":
                Log.i(TAG, "KaZaService: Connection query received from client");
                Log.i(TAG, "KaZaService: Service responding with OK");
                return "OK";

            case "config":
                if (configured) {
                    return "configured:host=" + sslHost + ":" + sslPort + ",user=" + username;
                } else {
                    return "not_configured";
                }

            case "connected":
                return isConnected ? "connected" : "disconnected";

            case "connect":
                if (configured) {
                    connectToServer();
                    return "connecting";
                } else {
                    return "ERROR: Not configured";
                }

            case "disconnect":
                disconnectFromServer();
                return "disconnected";

            // TODO: Add more commands (e.g., "server:status", "connection:check")
            default:
                Log.w(TAG, "KaZaService: Unknown query: " + query);
                return "ERROR: Unknown command: " + command;
        }
    }

    private String getServiceStatus() {
        return instance != null ? "running" : "stopped";
    }

    private int lastNotificationId = 0;

    /** Stable, strictly increasing id (seconds since epoch): a restart of the service never overwrites a displayed notification. */
    private synchronized int nextNotificationId() {
        int id = (int) (System.currentTimeMillis() / 1000L);
        if (id <= lastNotificationId) {
            id = lastNotificationId + 1;
        }
        lastNotificationId = id;
        return id;
    }

    private PendingIntent createOpenAppIntent(int requestCode) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch == null) {
            launch = new Intent();
            launch.setClassName(getPackageName(), "org.kaza.KaZaActivity");
        }
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, requestCode, launch,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Send an Android notification with the given title and message.
     * Each notification gets a unique id so multiple notifications can be displayed; tapping it opens the app.
     */
    public void notify(String title, String message) {
        try {
            NotificationManager m_notificationManager = (NotificationManager)
                    this.getSystemService(Context.NOTIFICATION_SERVICE);

            if (!m_notificationManager.areNotificationsEnabled()) {
                Log.w(TAG, "KaZaService: Notifications are disabled for KaZa - the alert cannot be displayed");
            }

            // Create or get notification channel for Android 8.0+
            String channelId = "KaZaNotifications";
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                NotificationChannel notificationChannel = m_notificationManager.getNotificationChannel(channelId);
                if (notificationChannel == null) {
                    int importance = NotificationManager.IMPORTANCE_HIGH; // HIGH for sound and popup
                    notificationChannel = new NotificationChannel(channelId, "KaZa Alert", importance);
                    notificationChannel.setDescription("Notifications from KaZa server");
                    notificationChannel.enableLights(true);
                    notificationChannel.setLightColor(Color.GREEN);
                    notificationChannel.enableVibration(true);
                    m_notificationManager.createNotificationChannel(notificationChannel);
                    Log.i(TAG, "KaZaService: Created notification channel: " + channelId);
                }
            }

            // Build notification
            Notification.Builder m_builder;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                m_builder = new Notification.Builder(this, channelId);
            } else {
                m_builder = new Notification.Builder(this);
                m_builder.setDefaults(Notification.DEFAULT_ALL);
                m_builder.setPriority(Notification.PRIORITY_HIGH);
            }

            int notificationId = nextNotificationId();
            Bitmap icon = BitmapFactory.decodeResource(this.getResources(), R.drawable.icon);
            m_builder.setSmallIcon(R.drawable.ic_stat_kaza)          // monochrome status-bar icon
                    .setLargeIcon(icon)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setColor(Color.GREEN)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)
                    .setAutoCancel(true)
                    .setContentIntent(createOpenAppIntent(notificationId))
                    .setStyle(new Notification.BigTextStyle().bigText(message)); // Support long text

            m_notificationManager.notify(notificationId, m_builder.build());

            Log.i(TAG, "KaZaService: Notification displayed (ID: " + notificationId + ")");
        } catch (Exception e) {
            Log.e(TAG, "KaZaService: Failed to show notification", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "KaZaService: Creating Notification Service");
        Log.i(TAG, "KaZaService: Process ID: " + android.os.Process.myPid());

        instance = this;

        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            heartbeatLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KaZa:heartbeat");
            heartbeatLock.setReferenceCounted(false);
        }

        // Must be called within a few seconds of startForegroundService(); never throws
        if (!startForegroundCompat()) {
            Log.e(TAG, "KaZaService: Cannot enter foreground - stopping");
            stopSelf();
            return;
        }

        Log.i(TAG, "KaZaService: Service created successfully - running in foreground");

        // Create background thread for network operations
        connectionThread = new HandlerThread("SSLConnectionThread");
        connectionThread.start();
        connectionHandler = new Handler(connectionThread.getLooper());

        // Initialize GPS tracker
        tracker = new KaZaTracker(this, connectionThread.getLooper());
        tracker.setProtocolCallback(new KaZaTracker.ProtocolCallback() {
            @Override
            public void sendCommand(String command) throws Exception {
                Conn c = conn;
                if (c != null) {
                    c.protocol.sendCommand(command);
                }
            }

            @Override
            public boolean isConnected() {
                return NotificationService.this.isConnected;
            }
        });

        // Initialize device state monitor
        deviceMonitor = new KaZaDeviceStateMonitor(this);
        deviceMonitor.setCallback(new KaZaDeviceStateMonitor.StateChangeCallback() {
            @Override
            public void onScreenStateChanged(boolean isScreenOn) {
                deviceMonitor.resetIdleTimer();
                updateKeepAliveInterval();

                // Start/stop location caching based on screen state
                if (tracker != null) {
                    if (deviceMonitor.isScreenOn()) {
                        tracker.startLocationCaching();
                    } else {
                        tracker.stopLocationCaching();
                    }
                }
            }

            @Override
            public void onBatteryStateChanged(int level, boolean isCharging, boolean isLowBattery) {
                updateKeepAliveInterval();
            }

            @Override
            public void onNetworkAvailable(final Network network) {
                connectionHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        handleDefaultNetworkAvailable(network);
                    }
                });
            }

            @Override
            public void onNetworkLost(Network network) {
                // Informative only: a new default network (Wi-Fi -> 4G) normally follows, and a
                // dead socket is detected by the PING/PONG deadline. Tearing the connection down
                // here is what used to leave the service disconnected after a network switch.
                Log.i(TAG, "KaZaService: Default network lost: " + network);
            }

            @Override
            public void onDozeStateChanged(boolean isInDozeMode) {
                updateKeepAliveInterval();
            }

            @Override
            public Context getContext() {
                return NotificationService.this;
            }
        });

        // Start monitoring device state
        deviceMonitor.startMonitoring();

        // Start location caching if screen is currently ON
        if (deviceMonitor.isScreenOn() && tracker != null) {
            tracker.startLocationCaching();
        }

        // Check app standby bucket and log initial state (API 28+)
        checkAppStandbyBucket();

        // Start periodic network type checking
        startNetworkTypeMonitoring();

        // Load saved configuration
        loadConfiguration();

        // If configured, try to connect to server
        if (configured) {
            Log.i(TAG, "KaZaService: Configuration found, attempting to connect");
            connectToServer();
        } else {
            Log.i(TAG, "KaZaService: No configuration found, waiting for configuration");
        }
    }

    /**
     * (Re)enter foreground with the service types matching the permissions currently granted.
     * Location type needs a granted location permission, and is refused when the service is
     * started from the background on Android 14+ (e.g. BOOT_COMPLETED): fall back to
     * remote messaging only, the location type is restored by PERMISSIONS_CHANGED.
     * @return false if the service could not enter foreground at all
     */
    private boolean startForegroundCompat() {
        Notification notification = createForegroundNotification();
        boolean hasLocation = PermissionActivity.hasLocationPermission(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING;
            if (hasLocation) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            try {
                startForeground(NOTIFICATION_ID, notification, type);
                Log.i(TAG, "KaZaService: Foreground type " + type + " (location permission: " + hasLocation + ")");
                return true;
            } catch (Exception e) {
                Log.w(TAG, "KaZaService: Foreground type " + type + " refused (" + e + "), falling back to remote messaging");
            }
            try {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "KaZaService: startForeground failed: " + e);
                return false;
            }
        }

        try {
            startForeground(NOTIFICATION_ID, notification);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "KaZaService: startForeground failed: " + e);
            return false;
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "KaZaService: Destroying Notification Service");
        Log.i(TAG, "KaZaService: Service was running in PID: " + android.os.Process.myPid());

        // Stop device state monitoring
        if (deviceMonitor != null) {
            deviceMonitor.stopMonitoring();
        }

        // Stop periodic network monitoring
        stopNetworkTypeMonitoring();

        // Stop location tracking
        if (tracker != null) {
            tracker.stopLocationCaching();
            tracker.stopAutomaticPositionTracking();
        }

        positionExecutor.shutdownNow();

        cancelHeartbeat();

        // Close SSL connection and drop every pending timer
        if (connectionHandler != null) {
            connectionHandler.removeCallbacksAndMessages(null);
        }
        Conn c = conn;
        if (c != null) {
            closeConnection(c);
        }

        // Stop connection thread
        if (connectionThread != null) {
            connectionThread.quitSafely();
            try {
                connectionThread.join(2000);
            } catch (InterruptedException e) {
                Log.e(TAG, "KaZaService: Error stopping connection thread", e);
            }
        }

        releaseWifiLock();
        if (heartbeatLock != null && heartbeatLock.isHeld()) {
            heartbeatLock.release();
        }
        instance = null;

        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "KaZaService: onStartCommand called");

        // Handle commands via Intent extras (works across processes)
        if (intent != null && intent.hasExtra("ACTION")) {
            String action = intent.getStringExtra("ACTION");
            Log.i(TAG, "KaZaService: Received action: " + action);

            if ("CONFIGURE".equals(action)) {
                // Extract SSL configuration from intent extras
                String clientCert = intent.getStringExtra("ssl_client_cert");
                String caCert = intent.getStringExtra("ssl_ca_cert");
                String clientKey = intent.getStringExtra("ssl_client_key");
                String clientPass = intent.getStringExtra("ssl_client_pass");
                String host = intent.getStringExtra("ssl_host");
                int port = intent.getIntExtra("ssl_port", 0);
                String user = intent.getStringExtra("username");

                if (clientCert != null && caCert != null && clientKey != null &&
                    host != null && port > 0 && user != null) {

                    boolean changed = !configured
                        || !clientCert.equals(sslClientCert) || !caCert.equals(sslCaCert)
                        || !clientKey.equals(sslClientKey)
                        || !java.util.Objects.equals(clientPass, sslClientPass)
                        || !host.equals(sslHost) || port != sslPort || !user.equals(username);

                    Log.i(TAG, "KaZaService: Configuring service via Intent (changed: " + changed + ")");
                    Log.i(TAG, "KaZaService: Host: " + host + ":" + port);

                    // Store configuration
                    this.sslClientCert = clientCert;
                    this.sslCaCert = caCert;
                    this.sslClientKey = clientKey;
                    this.sslClientPass = clientPass;
                    this.sslHost = host;
                    this.sslPort = port;
                    this.username = user;
                    this.configured = true;

                    // Save to SharedPreferences for persistence
                    saveConfiguration();

                    if (changed) {
                        reconnectNow("configuration changed");
                    } else {
                        connectToServer();
                    }
                } else {
                    Log.w(TAG, "KaZaService: Invalid configuration in Intent - missing parameters");
                }
            } else if ("PERMISSIONS_CHANGED".equals(action)) {
                // Permissions were granted: restore the location foreground type and the tracking
                startForegroundCompat();
                if (tracker != null) {
                    if (deviceMonitor != null && deviceMonitor.isScreenOn()) {
                        tracker.startLocationCaching();
                    }
                    connectionHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isConnected) {
                                tracker.startAutomaticPositionTracking();
                            }
                        }
                    });
                }
                updateForegroundNotification();
            } else if ("RECONNECT".equals(action)) {
                reconnectNow("requested");
            }
        }

        // START_STICKY ensures Android will restart the service if killed
        return START_STICKY;
    }


    private Notification createForegroundNotification() {
        return createForegroundNotification(isConnected);
    }

    private Notification createForegroundNotification(boolean connected) {
        NotificationManager notificationManager = (NotificationManager)
                this.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder builder;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                "service_channel",
                "KaZa state",
                NotificationManager.IMPORTANCE_LOW
            );
            notificationManager.createNotificationChannel(channel);
            builder = new Notification.Builder(this, channel.getId());
        } else {
            builder = new Notification.Builder(this);
        }

        Bitmap icon = BitmapFactory.decodeResource(this.getResources(), R.drawable.icon);

        // Status text: connection state, reconnection progress, configuration error
        String statusText;
        int color;

        if (connected) {
            statusText = "KaZa service connected";
            color = Color.GREEN;
        } else if (configError && lastError != null) {
            statusText = "KaZa configuration error: " + lastError;
            color = Color.RED;
        } else if (configured) {
            statusText = reconnectAttempts > 0
                ? "KaZa is disconnected (reconnecting, attempt " + reconnectAttempts + ")"
                : "KaZa is connecting...";
            color = 0xFFFFA500; // Orange
        } else {
            statusText = "KaZa is not configured";
            color = Color.RED;
        }

        // Missing permission: the notification leads to the onboarding instead of the app
        PendingIntent contentIntent;
        if (!PermissionActivity.allGranted(this)) {
            statusText += " - permissions missing, tap to fix";
            Intent permissions = new Intent(this, PermissionActivity.class);
            permissions.putExtra("force", true);
            permissions.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            contentIntent = PendingIntent.getActivity(this, 1, permissions,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } else {
            contentIntent = createOpenAppIntent(1);
        }

        builder.setSmallIcon(R.drawable.ic_stat_kaza)
                .setLargeIcon(icon)
                .setContentTitle("KaZa")
                .setContentText(statusText)
                .setColor(color)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(contentIntent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    /**
     * Update the foreground notification with current connection status
     */
    private void updateForegroundNotification() {
        try {
            NotificationManager notificationManager = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID, createForegroundNotification(isConnected));
            }
        } catch (Exception e) {
            Log.e(TAG, "KaZaService: Failed to update notification: " + e.getMessage());
        }
    }

    /** Thrown for errors that retrying quickly cannot fix (missing/invalid certificate or key). */
    private static class ConfigException extends Exception {
        ConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Request a connection attempt (no-op when already connected or connecting).
     * Safe from any thread.
     */
    private void connectToServer() {
        if (!configured) {
            Log.w(TAG, "KaZaService: Cannot connect - not configured");
            return;
        }
        if (connectionHandler == null) {
            return;
        }
        connectionHandler.post(new Runnable() {
            @Override
            public void run() {
                doConnect();
            }
        });
    }

    // ------------------------------------------------------------------ heartbeat / wake locks

    /** Hold the CPU awake for at most timeoutMs (auto-released, so it can never leak). */
    private void holdWakeLock(long timeoutMs) {
        PowerManager.WakeLock lock = heartbeatLock;
        if (lock != null) {
            try {
                lock.acquire(timeoutMs);
            } catch (Exception e) {
                Log.w(TAG, "KaZaService: Cannot acquire wake lock: " + e);
            }
        }
    }

    private PendingIntent heartbeatIntent() {
        Intent intent = new Intent(this, KaZaHeartbeatReceiver.class);
        intent.setAction("org.kaza.HEARTBEAT");
        return PendingIntent.getBroadcast(this, HEARTBEAT_REQUEST_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private long heartbeatInterval() {
        if (!isConnected) {
            return HEARTBEAT_DISCONNECTED_MS;
        }
        long interval = (deviceMonitor != null && deviceMonitor.isOnWifi()) ? HEARTBEAT_WIFI_MS : HEARTBEAT_MOBILE_MS;
        if (deviceMonitor != null && deviceMonitor.isLowBattery()) {
            interval *= HEARTBEAT_LOW_BATTERY_FACTOR;
        }
        return interval;
    }

    /**
     * Arm (replace) the next heartbeat alarm. Exact when the user allows it, otherwise
     * setAndAllowWhileIdle, which still fires in Doze (rate-limited unless the app is exempt
     * from battery optimizations).
     */
    private void scheduleHeartbeat() {
        if (alarmManager == null || !configured) {
            return;
        }
        long triggerAt = SystemClock.elapsedRealtime() + heartbeatInterval();
        PendingIntent pi = heartbeatIntent();
        try {
            boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms();
            if (exact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            }
        } catch (SecurityException e) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
        }
    }

    private void cancelHeartbeat() {
        if (alarmManager != null) {
            alarmManager.cancel(heartbeatIntent());
        }
    }

    /**
     * Alarm fired (called by KaZaHeartbeatReceiver, any thread): the CPU is awake only for the
     * duration of the broadcast, so take our own wake lock first, then PING or reconnect.
     */
    void onHeartbeat() {
        holdWakeLock(HEARTBEAT_WAKELOCK_MS);
        Handler h = connectionHandler;
        if (h == null) {
            return;
        }
        h.post(new Runnable() {
            @Override
            public void run() {
                scheduleHeartbeat();
                if (!configured) {
                    return;
                }
                Conn c = conn;
                if (c != null && !c.closed) {
                    Log.d(TAG, "KaZaService: Heartbeat - checking connection");
                    sendKeepAlive();
                } else if (state == State.DISCONNECTED) {
                    Log.i(TAG, "KaZaService: Heartbeat - disconnected, reconnecting now");
                    reconnectAttempts = 0;
                    cancelScheduledReconnect();
                    doConnect();
                }
            }
        });
    }

    /** Hold a Wi-Fi lock while connected through Wi-Fi, release it otherwise. */
    private void updateWifiLock() {
        if (!USE_WIFI_LOCK) {
            return;
        }
        boolean wanted = isConnected && deviceMonitor != null && deviceMonitor.isOnWifi();
        if (!wanted) {
            releaseWifiLock();
            return;
        }
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm == null) {
                    return;
                }
                int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "KaZa:wifi");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) {
                wifiLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "KaZaService: Cannot acquire Wi-Fi lock: " + e);
        }
    }

    private void releaseWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "KaZaService: Cannot release Wi-Fi lock: " + e);
        }
    }

    /** Drop the current connection (if any) and reconnect immediately with a fresh backoff. */
    private void reconnectNow(final String reason) {
        if (connectionHandler == null) {
            return;
        }
        connectionHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "KaZaService: Reconnecting now (" + reason + ")");
                Conn c = conn;
                if (c != null) {
                    closeConnection(c);
                }
                reconnectAttempts = 0;
                cancelScheduledReconnect();
                doConnect();
            }
        });
    }

    private void handleDefaultNetworkAvailable(Network network) {
        holdWakeLock(HEARTBEAT_WAKELOCK_MS);
        Conn c = conn;
        if (c == null) {
            if (configured && state == State.DISCONNECTED) {
                Log.i(TAG, "KaZaService: Network available while disconnected, reconnecting");
                reconnectAttempts = 0;
                cancelScheduledReconnect();
                doConnect();
            }
        } else if (c.network != null && network != null && !network.equals(c.network)) {
            // The socket is bound to the previous network (Wi-Fi <-> mobile): it will never recover
            Log.i(TAG, "KaZaService: Default network changed (" + c.network + " -> " + network + "), reconnecting");
            reconnectNow("default network changed");
        }
    }

    private Network currentNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null ? cm.getActiveNetwork() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private SSLContext buildSslContext() throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");

        // CA certificate
        Certificate ca;
        InputStream caInput = new FileInputStream(stripFileScheme(sslCaCert));
        try {
            ca = cf.generateCertificate(caInput);
        } finally {
            caInput.close();
        }
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setCertificateEntry("ca", ca);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);

        // Client certificate and key (mutual TLS)
        InputStream clientCertInput = new FileInputStream(stripFileScheme(sslClientCert));
        Certificate clientCert;
        try {
            clientCert = cf.generateCertificate(clientCertInput);
        } finally {
            clientCertInput.close();
        }
        PrivateKey privateKey = loadPrivateKey(stripFileScheme(sslClientKey), sslClientPass);

        char[] pass = sslClientPass != null ? sslClientPass.toCharArray() : new char[0];
        KeyStore clientKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        clientKeyStore.load(null, null);
        clientKeyStore.setKeyEntry("client", privateKey, pass, new Certificate[]{clientCert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(clientKeyStore, pass);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return sslContext;
    }

    private static String stripFileScheme(String path) {
        return path.startsWith("file://") ? path.substring(7) : path;
    }

    /**
     * Open the connection. Runs on connectionHandler only.
     * TLS connect and handshake are bounded; the established socket has no read timeout
     * (liveness is the PING/PONG deadline), so a frame can never be read half-way.
     */
    private void doConnect() {
        if (!configured || state != State.DISCONNECTED) {
            return;
        }
        state = State.CONNECTING;
        cancelScheduledReconnect();
        holdWakeLock(HEARTBEAT_WAKELOCK_MS);
        scheduleHeartbeat();
        final int generation = ++connGeneration;
        updateForegroundNotification();

        Log.i(TAG, "KaZaService: Connecting to " + sslHost + ":" + sslPort + " (generation " + generation + ")");
        SSLSocket socket = null;
        try {
            SSLContext sslContext;
            try {
                sslContext = buildSslContext();
            } catch (java.io.FileNotFoundException e) {
                throw new ConfigException("file not found: " + e.getMessage(), e);
            } catch (java.security.cert.CertificateException e) {
                throw new ConfigException("invalid certificate: " + e.getMessage(), e);
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("PBES2") || msg.contains("Unsupported encryption")
                        || msg.contains("decrypt PKCS#8") || msg.contains("Failed to parse")
                        || msg.contains("requires a password"))) {
                    throw new ConfigException("private key: " + msg, e);
                }
                throw e;
            }

            Network network = currentNetwork();
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(sslHost, sslPort), CONNECT_TIMEOUT_MS);
            socket = (SSLSocket) sslContext.getSocketFactory().createSocket(raw, sslHost, sslPort, true);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            socket.startHandshake();
            socket.setSoTimeout(0);
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);

            KaZaProtocol protocol = new KaZaProtocol(socket.getInputStream(), socket.getOutputStream());
            Conn c = new Conn(generation, socket, protocol, network);
            conn = c;
            isConnected = true;
            state = State.CONNECTED;
            lastError = null;
            configError = false;
            lastMessageReceivedTime = System.currentTimeMillis();
            Log.i(TAG, "KaZaService: TLS established (" + socket.getSession().getCipherSuite() + ")");

            // VERSION must be the first frame
            protocol.sendVersion(username, Build.MODEL, 0);

            startReaderThread(c);
            startKeepAlive();
            updateWifiLock();
            scheduleHeartbeat();
            updateForegroundNotification();
        } catch (ConfigException e) {
            Log.e(TAG, "KaZaService: Configuration error: " + e.getMessage());
            failConnect(socket, e.getMessage(), true);
        } catch (Exception e) {
            Log.e(TAG, "KaZaService: Connection failed: " + e);
            failConnect(socket, e.toString(), false);
        }
    }

    private void failConnect(SSLSocket socket, String reason, boolean permanent) {
        if (socket != null) {
            try { socket.close(); } catch (Exception ignored) { }
        }
        Conn c = conn;
        if (c != null) {
            c.closed = true;
            conn = null;
        }
        isConnected = false;
        state = State.DISCONNECTED;
        lastError = reason;
        configError = permanent;
        scheduleReconnect();
    }

    /**
     * Load private key from PEM file
     * Supports both encrypted and unencrypted RSA keys
     */
    private PrivateKey loadPrivateKey(String keyPath, String password) throws Exception {
        Log.i(TAG, "KaZaService: Reading private key from: " + keyPath);
        Log.i(TAG, "KaZaService: ==================== KEY FILE ANALYSIS ====================");

        // Read the PEM file
        BufferedReader reader = new BufferedReader(new FileReader(keyPath));
        StringBuilder pemContent = new StringBuilder();
        StringBuilder fullFileContent = new StringBuilder();
        String line;
        boolean inKey = false;
        boolean isEncrypted = false;
        String keyType = null;
        String encryptionAlgorithm = null;
        String encryptionIV = null;
        int lineNumber = 0;

        while ((line = reader.readLine()) != null) {
            lineNumber++;
            fullFileContent.append(line).append("\n");

            String trimmedLine = line.trim();

            // Skip empty lines
            if (trimmedLine.isEmpty()) {
                continue;
            }

            // Detect key type and check if encrypted
            if (trimmedLine.startsWith("-----BEGIN")) {
                inKey = true;
                keyType = trimmedLine;
                if (trimmedLine.contains("ENCRYPTED")) {
                    isEncrypted = true;
                }
                Log.i(TAG, "KaZaService: Line " + lineNumber + ": " + trimmedLine);
                continue;
            }

            if (trimmedLine.startsWith("-----END")) {
                inKey = false;
                Log.i(TAG, "KaZaService: Line " + lineNumber + ": " + trimmedLine);
                continue;
            }

            // Check for encryption markers in attribute lines
            if (trimmedLine.contains(":")) {
                Log.i(TAG, "KaZaService: Line " + lineNumber + ": " + trimmedLine);
                if (trimmedLine.startsWith("Proc-Type:") && trimmedLine.contains("ENCRYPTED")) {
                    isEncrypted = true;
                } else if (trimmedLine.startsWith("DEK-Info:")) {
                    // Parse encryption info (algorithm,IV)
                    String dekInfo = trimmedLine.substring(trimmedLine.indexOf(":") + 1).trim();
                    String[] parts = dekInfo.split(",");
                    if (parts.length == 2) {
                        encryptionAlgorithm = parts[0].trim();
                        encryptionIV = parts[1].trim();
                    }
                }
                continue;
            }

            // Append only base64 content (no dashes, no special chars)
            if (inKey && trimmedLine.matches("[A-Za-z0-9+/=]+")) {
                pemContent.append(trimmedLine);
            }
        }
        reader.close();

        // Log key format summary
        Log.i(TAG, "KaZaService: ==================== FORMAT SUMMARY ====================");
        Log.i(TAG, "KaZaService: Key type: " + keyType);
        Log.i(TAG, "KaZaService: Is encrypted: " + isEncrypted);
        Log.i(TAG, "KaZaService: Encryption algorithm (DEK-Info): " + encryptionAlgorithm);
        Log.i(TAG, "KaZaService: Has password: " + (password != null && !password.isEmpty()));

        String pemString = pemContent.toString();
        if (pemString.isEmpty()) {
            throw new Exception("No valid base64 content found in PEM file");
        }

        Log.i(TAG, "KaZaService: Base64 content length: " + pemString.length() + " characters");

        // Decode Base64 content
        byte[] keyBytes = Base64.getDecoder().decode(pemString);
        Log.i(TAG, "KaZaService: Key decoded, size: " + keyBytes.length + " bytes");

        // Show first bytes for ASN.1 structure analysis (safe - just structure, not key data)
        if (keyBytes.length >= 20) {
            StringBuilder hexDump = new StringBuilder();
            for (int i = 0; i < Math.min(20, keyBytes.length); i++) {
                hexDump.append(String.format("%02X ", keyBytes[i]));
            }
            Log.i(TAG, "KaZaService: First 20 bytes (hex): " + hexDump.toString());
        }

        // Decrypt if encrypted
        if (isEncrypted) {
            if (password == null || password.isEmpty()) {
                throw new Exception("Encrypted key requires a password. Please provide ssl/client_pass.");
            }

            Log.i(TAG, "KaZaService: Decrypting key with password...");

            // Check if this is PKCS#8 encrypted format (no DEK-Info header)
            if (encryptionAlgorithm == null || encryptionIV == null) {
                // PKCS#8 encrypted private key format
                Log.i(TAG, "KaZaService: Using PKCS#8 encrypted key decryption");
                keyBytes = decryptPKCS8PrivateKey(keyBytes, password);
            } else {
                // Old PEM format with DEK-Info header
                Log.i(TAG, "KaZaService: Using legacy PEM encrypted key decryption");
                keyBytes = decryptPrivateKey(keyBytes, password, encryptionAlgorithm, encryptionIV);
            }

            Log.i(TAG, "KaZaService: ✓ Key decrypted successfully, size: " + keyBytes.length + " bytes");
        }

        Log.i(TAG, "KaZaService: ==================== END KEY ANALYSIS ====================");

        // Determine if it's PKCS#8 or PKCS#1 format based on the header
        boolean isPKCS1 = keyType != null && keyType.contains("RSA PRIVATE KEY");

        if (isPKCS1) {
            // Old format: RSA PRIVATE KEY - convert to PKCS#8 manually
            Log.i(TAG, "KaZaService: Detected PKCS#1 format (RSA PRIVATE KEY)");
            Log.i(TAG, "KaZaService: Converting PKCS#1 to PKCS#8 format...");

            try {
                // Parse PKCS#1 RSA key and convert to PKCS#8
                PrivateKey privateKey = convertPKCS1toPKCS8(keyBytes);
                Log.i(TAG, "KaZaService: ✓ Private key converted and parsed successfully (RSA PKCS#1)");
                return privateKey;
            } catch (Exception e) {
                Log.e(TAG, "KaZaService: Failed to convert PKCS#1 key: " + e.getMessage());
                Log.w(TAG, "KaZaService: You can manually convert with:");
                Log.w(TAG, "KaZaService: openssl pkcs8 -topk8 -nocrypt -in old.key -out new.key");
                throw new Exception("Failed to parse PKCS#1 key: " + e.getMessage());
            }
        }

        // Parse PKCS#8 format
        try {
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = keyFactory.generatePrivate(keySpec);
            Log.i(TAG, "KaZaService: ✓ Private key parsed successfully (RSA PKCS#8)");
            return privateKey;
        } catch (Exception e) {
            // Try EC key
            try {
                PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
                KeyFactory keyFactory = KeyFactory.getInstance("EC");
                PrivateKey privateKey = keyFactory.generatePrivate(keySpec);
                Log.i(TAG, "KaZaService: ✓ Private key parsed successfully (EC)");
                return privateKey;
            } catch (Exception e2) {
                Log.e(TAG, "KaZaService: Failed to parse key as RSA or EC");
                Log.e(TAG, "KaZaService: RSA error: " + e.getMessage());
                Log.e(TAG, "KaZaService: EC error: " + e2.getMessage());
                throw new Exception("Failed to parse private key. Ensure it's in PKCS#8 format.");
            }
        }
    }

    /**
     * Decrypt a PKCS#8 encrypted private key (-----BEGIN ENCRYPTED PRIVATE KEY-----)
     * Note: Android's crypto provider has limited support for PKCS#8 encryption algorithms.
     * PBES2 (modern OpenSSL default) is NOT supported. Use unencrypted keys or PBE-SHA1-3DES.
     */
    private byte[] decryptPKCS8PrivateKey(byte[] encryptedData, String password) throws Exception {
        EncryptedPrivateKeyInfo encryptedPrivateKeyInfo = null;
        String algName = null;

        Log.i(TAG, "KaZaService: ---------- PKCS#8 DECRYPTION ANALYSIS ----------");

        try {
            // Create EncryptedPrivateKeyInfo from the encrypted bytes
            encryptedPrivateKeyInfo = new EncryptedPrivateKeyInfo(encryptedData);
            algName = encryptedPrivateKeyInfo.getAlgName();

            Log.i(TAG, "KaZaService: PKCS#8 encryption algorithm OID: " + algName);
            Log.i(TAG, "KaZaService: Algorithm parameters: " + encryptedPrivateKeyInfo.getAlgParameters());
            Log.i(TAG, "KaZaService: Encrypted data size: " + encryptedPrivateKeyInfo.getEncryptedData().length + " bytes");

            // Decode OID to human-readable name
            String algDescription = "Unknown";
            switch (algName) {
                case "1.2.840.113549.1.5.13":
                    algDescription = "PBES2 (Password-Based Encryption Scheme 2)";
                    break;
                case "1.2.840.113549.1.5.3":
                    algDescription = "PBE-MD5-DES-CBC";
                    break;
                case "1.2.840.113549.1.5.6":
                    algDescription = "PBE-MD5-RC2-CBC";
                    break;
                case "1.2.840.113549.1.5.10":
                    algDescription = "PBE-SHA1-DES-CBC";
                    break;
                case "1.2.840.113549.1.5.11":
                    algDescription = "PBE-SHA1-RC2-CBC";
                    break;
                case "1.2.840.113549.1.12.1.3":
                    algDescription = "PBE-SHA1-3DES (PKCS#12)";
                    break;
                case "1.2.840.113549.1.12.1.6":
                    algDescription = "PBE-SHA1-RC2-40 (PKCS#12)";
                    break;
            }
            Log.i(TAG, "KaZaService: Algorithm name: " + algDescription);

            // Check if this is PBES2 (unsupported on Android)
            if (algName.equals("1.2.840.113549.1.5.13")) {
                Log.e(TAG, "KaZaService: ==================== ERROR ====================");
                Log.e(TAG, "KaZaService: ⚠ PBES2 encryption is NOT supported on Android");
                Log.e(TAG, "KaZaService: PBES2 is the default encryption used by modern OpenSSL");
                Log.e(TAG, "KaZaService: ");
                Log.e(TAG, "KaZaService: SOLUTION 1 (Recommended): Convert to unencrypted PKCS#8");
                Log.e(TAG, "KaZaService:   openssl pkcs8 -in client.key -out client_unencrypted.key -nocrypt");
                Log.e(TAG, "KaZaService: ");
                Log.e(TAG, "KaZaService: SOLUTION 2: Convert to PBE-SHA1-3DES encryption");
                Log.e(TAG, "KaZaService:   openssl pkcs8 -topk8 -in client.key -out client_compatible.key -v1 PBE-SHA1-3DES");
                Log.e(TAG, "KaZaService: ==================== ERROR ====================");
                throw new Exception("PBES2 encryption not supported. Use unencrypted PKCS#8 or PBE-SHA1-3DES format.");
            }

            // Try standard decryption for older PBE algorithms
            PBEKeySpec pbeKeySpec = new PBEKeySpec(password.toCharArray());
            SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(algName);
            PKCS8EncodedKeySpec pkcs8KeySpec = encryptedPrivateKeyInfo.getKeySpec(secretKeyFactory.generateSecret(pbeKeySpec));
            Log.i(TAG, "KaZaService: ✓ PKCS#8 key decrypted successfully");
            return pkcs8KeySpec.getEncoded();

        } catch (java.security.NoSuchAlgorithmException e) {
            Log.e(TAG, "KaZaService: Encryption algorithm not supported: " + algName);
            Log.e(TAG, "KaZaService: Convert your key to unencrypted format:");
            Log.e(TAG, "KaZaService:   openssl pkcs8 -in encrypted.key -out unencrypted.key -nocrypt");
            throw new Exception("Unsupported encryption algorithm: " + algName + ". Use unencrypted PKCS#8 format.", e);
        } catch (Exception e) {
            Log.e(TAG, "KaZaService: PKCS#8 decryption failed: " + e.getMessage());
            throw new Exception("Failed to decrypt PKCS#8 key. Check password or convert to unencrypted format.", e);
        }
    }

    /**
     * Decrypt an encrypted private key using OpenSSL-compatible algorithm
     * Supports DES-EDE3-CBC, AES-128-CBC, AES-192-CBC, AES-256-CBC
     */
    private byte[] decryptPrivateKey(byte[] encryptedData, String password, String algorithm, String ivHex) throws Exception {
        // Parse IV from hex string
        byte[] iv = hexStringToByteArray(ivHex);
        Log.i(TAG, "KaZaService: IV size: " + iv.length + " bytes");

        // Determine cipher transformation and key size
        String cipherName;
        int keySize;

        switch (algorithm) {
            case "DES-EDE3-CBC":
                cipherName = "DESede/CBC/PKCS5Padding";
                keySize = 24; // 192 bits
                break;
            case "AES-128-CBC":
                cipherName = "AES/CBC/PKCS5Padding";
                keySize = 16; // 128 bits
                break;
            case "AES-192-CBC":
                cipherName = "AES/CBC/PKCS5Padding";
                keySize = 24; // 192 bits
                break;
            case "AES-256-CBC":
                cipherName = "AES/CBC/PKCS5Padding";
                keySize = 32; // 256 bits
                break;
            default:
                throw new Exception("Unsupported encryption algorithm: " + algorithm);
        }

        Log.i(TAG, "KaZaService: Cipher: " + cipherName + " (key size: " + keySize + " bytes)");

        // Derive key using OpenSSL's EVP_BytesToKey (MD5-based)
        byte[] key = deriveKeyOpenSSL(password.getBytes("UTF-8"), iv, keySize);
        Log.i(TAG, "KaZaService: Key derived (" + key.length + " bytes)");

        // Decrypt
        Cipher cipher = Cipher.getInstance(cipherName);
        SecretKeySpec secretKey = new SecretKeySpec(key, cipherName.split("/")[0]);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);

        byte[] decrypted = cipher.doFinal(encryptedData);
        Log.i(TAG, "KaZaService: Decryption complete");

        return decrypted;
    }

    /**
     * OpenSSL-compatible key derivation (EVP_BytesToKey with MD5)
     * Implements the same algorithm as: openssl enc -p -<cipher> -S <salt> -P
     */
    private byte[] deriveKeyOpenSSL(byte[] password, byte[] salt, int keyLength) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] key = new byte[keyLength];
        int hashesNeeded = (keyLength + 15) / 16; // MD5 produces 16 bytes per hash
        byte[] concatenated = null;
        int keyOffset = 0;

        for (int i = 0; i < hashesNeeded; i++) {
            md.reset();

            // For rounds after the first, include previous hash
            if (concatenated != null) {
                md.update(concatenated);
            }

            // Add password and salt
            md.update(password);
            md.update(salt, 0, Math.min(8, salt.length)); // OpenSSL uses first 8 bytes of IV as salt

            concatenated = md.digest();

            // Copy as much as needed
            int bytesToCopy = Math.min(concatenated.length, keyLength - keyOffset);
            System.arraycopy(concatenated, 0, key, keyOffset, bytesToCopy);
            keyOffset += bytesToCopy;
        }

        return key;
    }

    /**
     * Convert hex string to byte array
     */
    private byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Convert PKCS#1 RSA private key to PKCS#8 format
     * PKCS#1 structure is embedded in PKCS#8 with additional wrapper
     */
    private PrivateKey convertPKCS1toPKCS8(byte[] pkcs1Bytes) throws Exception {
        // PKCS#8 RSA private key wrapper
        // This is the ASN.1 structure for PKCS#8:
        // SEQUENCE {
        //   version INTEGER (0)
        //   SEQUENCE {
        //     algorithm OBJECT IDENTIFIER (rsaEncryption = 1.2.840.113549.1.1.1)
        //     parameters NULL
        //   }
        //   privateKey OCTET STRING (contains the PKCS#1 key)
        // }

        int pkcs1Length = pkcs1Bytes.length;
        int totalLength = pkcs1Length + 22; // 22 bytes for the PKCS#8 wrapper

        // Build PKCS#8 structure
        byte[] pkcs8Bytes = new byte[totalLength + 4]; // +4 for outer SEQUENCE header
        int offset = 0;

        // Outer SEQUENCE
        pkcs8Bytes[offset++] = 0x30; // SEQUENCE tag
        if (totalLength < 128) {
            pkcs8Bytes[offset++] = (byte) totalLength;
        } else {
            pkcs8Bytes[offset++] = (byte) 0x82; // Long form: 2 bytes for length
            pkcs8Bytes[offset++] = (byte) (totalLength >> 8);
            pkcs8Bytes[offset++] = (byte) (totalLength & 0xFF);
        }

        // version INTEGER (0)
        pkcs8Bytes[offset++] = 0x02; // INTEGER tag
        pkcs8Bytes[offset++] = 0x01; // length
        pkcs8Bytes[offset++] = 0x00; // value = 0

        // algorithm SEQUENCE
        pkcs8Bytes[offset++] = 0x30; // SEQUENCE tag
        pkcs8Bytes[offset++] = 0x0D; // length = 13

        // algorithm OBJECT IDENTIFIER (rsaEncryption)
        pkcs8Bytes[offset++] = 0x06; // OID tag
        pkcs8Bytes[offset++] = 0x09; // length = 9
        // OID: 1.2.840.113549.1.1.1 (rsaEncryption)
        pkcs8Bytes[offset++] = 0x2A; // 1.2
        pkcs8Bytes[offset++] = (byte) 0x86; // 840
        pkcs8Bytes[offset++] = 0x48;
        pkcs8Bytes[offset++] = (byte) 0x86; // 113549
        pkcs8Bytes[offset++] = (byte) 0xF7;
        pkcs8Bytes[offset++] = 0x0D;
        pkcs8Bytes[offset++] = 0x01; // 1
        pkcs8Bytes[offset++] = 0x01; // 1
        pkcs8Bytes[offset++] = 0x01; // 1

        // parameters NULL
        pkcs8Bytes[offset++] = 0x05; // NULL tag
        pkcs8Bytes[offset++] = 0x00; // length = 0

        // privateKey OCTET STRING
        pkcs8Bytes[offset++] = 0x04; // OCTET STRING tag
        if (pkcs1Length < 128) {
            pkcs8Bytes[offset++] = (byte) pkcs1Length;
        } else {
            pkcs8Bytes[offset++] = (byte) 0x82; // Long form
            pkcs8Bytes[offset++] = (byte) (pkcs1Length >> 8);
            pkcs8Bytes[offset++] = (byte) (pkcs1Length & 0xFF);
        }

        // Copy PKCS#1 key data
        System.arraycopy(pkcs1Bytes, 0, pkcs8Bytes, offset, pkcs1Length);

        // Adjust array size if needed (due to variable length encoding)
        byte[] finalBytes;
        if (offset + pkcs1Length < pkcs8Bytes.length) {
            finalBytes = new byte[offset + pkcs1Length];
            System.arraycopy(pkcs8Bytes, 0, finalBytes, 0, offset + pkcs1Length);
        } else {
            finalBytes = pkcs8Bytes;
        }

        Log.i(TAG, "KaZaService: PKCS#8 conversion: " + finalBytes.length + " bytes");

        // Now parse as PKCS#8
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(finalBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(keySpec);
    }

    /**
     * Start the reader thread of a connection. The read blocks without timeout: the thread ends
     * when the socket is closed (closeConnection) or fails, and only reports to the connection
     * thread if its connection is still the current one.
     */
    private void startReaderThread(final Conn c) {
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                String reason = "Connection closed";
                try {
                    while (!c.closed) {
                        KaZaProtocol.ReceivedFrame frame = c.protocol.readFrame();
                        dispatchFrame(c, frame);
                    }
                    return;
                } catch (java.io.EOFException e) {
                    reason = "Connection closed by server";
                } catch (IOException e) {
                    reason = "Socket read error: " + e.getMessage();
                } catch (RuntimeException e) {
                    reason = "Reader error: " + e;
                }
                if (!c.closed) {
                    postConnectionLost(c, reason);
                }
            }
        }, "KaZaReader-" + c.generation);
        reader.setDaemon(true);
        reader.start();
    }

    private void postConnectionLost(final Conn c, final String reason) {
        Handler h = connectionHandler;
        if (h == null) {
            return;
        }
        h.post(new Runnable() {
            @Override
            public void run() {
                onConnectionLost(c, reason);
            }
        });
    }

    /** Reader thread: record liveness, then hand the frame over without ever blocking the reader. */
    private void dispatchFrame(final Conn c, KaZaProtocol.ReceivedFrame frame) {
        lastMessageReceivedTime = System.currentTimeMillis();
        if (!frame.isCommand()) {
            return; // FILE / OBJVALUE / ... are not used by the service
        }
        final String command = frame.getPayloadAsString();
        if (command.equals("PING") || command.equals("PONG")) {
            return;
        }
        Log.i(TAG, "KaZaService: Received command: \"" + command + "\"");

        if (command.equals("POSITION?")) {
            // A position fix can take tens of seconds: neither the reader nor the connection thread may wait for it
            positionExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    handlePositionRequest(c);
                }
            });
            return;
        }

        Handler h = connectionHandler;
        if (h == null) {
            return;
        }
        h.post(new Runnable() {
            @Override
            public void run() {
                if (c.generation == connGeneration && !c.closed) {
                    handleCommand(c, command);
                }
            }
        });
    }

    /** Connection thread */
    private void handleCommand(Conn c, String command) {
        if (deviceMonitor != null) {
            deviceMonitor.resetIdleTimer();
        }

        if (command.startsWith("NOTIFY:")) {
            String notificationText = command.substring(7);
            notify("KaZa Alert", notificationText);
            return;
        }

        if (command.startsWith("ERROR:")) {
            String errorMsg = command.substring(6).trim();
            Log.e(TAG, "KaZaService: Server error: " + errorMsg);
            if (errorMsg.contains("version")) {
                lastError = "protocol version: " + errorMsg;
                onConnectionLost(c, "Protocol version incompatible: " + errorMsg);
            }
            return;
        }

        switch (command) {
            case "CONNECTED":
                Log.i(TAG, "KaZaService: Version negotiation successful - connection established");
                reconnectAttempts = 0;
                updateForegroundNotification();
                if (tracker != null) {
                    tracker.startAutomaticPositionTracking();
                }
                break;

            case "DISCONNECT":
                onConnectionLost(c, "Server requested disconnect");
                break;

            default:
                Log.d(TAG, "KaZaService: Ignored command: " + command);
                break;
        }
    }

    /** Position executor thread */
    private void handlePositionRequest(Conn c) {
        Log.i(TAG, "KaZaService: Position request received");

        // Adaptive burst mode logic
        long now = System.currentTimeMillis();
        boolean wasInBurstMode = (burstModeEndTime > 0 && now < burstModeEndTime);
        tracker.updateLastPositionRequestTime();

        if (!wasInBurstMode) {
            burstModeStartTime = now;
            burstModeEndTime = now + BURST_MODE_DURATION_MS;
            Log.i(TAG, "KaZaService: Entering BURST MODE for 2 minutes (position tracking active)");
        } else {
            long timeSinceStart = now - burstModeStartTime;
            long timeRemaining = burstModeEndTime - now;
            if (timeSinceStart < BURST_MODE_MAX_DURATION_MS && timeRemaining < 60000) {
                long newEndTime = now + BURST_MODE_EXTENSION_MS;
                if (newEndTime - burstModeStartTime <= BURST_MODE_MAX_DURATION_MS) {
                    burstModeEndTime = newEndTime;
                    Log.i(TAG, "KaZaService: Burst mode extended (total duration: "
                          + ((burstModeEndTime - burstModeStartTime) / 1000) + "s)");
                }
            }
        }

        String position = tracker.getGPSPosition(burstModeEndTime, deviceMonitor.isScreenOn());
        if (c.closed) {
            return; // connection replaced while waiting for the fix
        }
        try {
            c.protocol.sendCommand("POSITION:" + position);
            Log.i(TAG, "KaZaService: Sent position: " + position);
        } catch (Exception e) {
            Log.e(TAG, "KaZaService: Failed to send position: " + e.getMessage());
            postConnectionLost(c, "Failed to send position");
        }
    }

    /**
     * Close a connection and release everything attached to it. Idempotent; the closing of the
     * socket unblocks the reader thread. Runs on connectionHandler (or in onDestroy).
     */
    private void closeConnection(Conn c) {
        if (c == null) {
            return;
        }
        boolean wasCurrent = (c == conn);
        c.closed = true;
        if (wasCurrent) {
            if (tracker != null) {
                tracker.stopAutomaticPositionTracking();
            }
            stopKeepAlive();
            conn = null;
            isConnected = false;
            state = State.DISCONNECTED;
            releaseWifiLock();
        }
        try {
            c.socket.close();
        } catch (Exception e) {
            Log.w(TAG, "KaZaService: Error closing socket: " + e.getMessage());
        }
        if (wasCurrent) {
            updateForegroundNotification();
        }
    }

    /** Connection thread: the connection died; ignored if it is not the current one. */
    private void onConnectionLost(Conn c, String reason) {
        if (c != conn || c.closed) {
            return;
        }
        Log.w(TAG, "KaZaService: Connection lost (generation " + c.generation + "): " + reason);
        lastError = reason;
        configError = false;
        closeConnection(c);
        scheduleReconnect();
    }

    /** User requested disconnection ("disconnect" query): no automatic reconnection. */
    private void disconnectFromServer() {
        Handler h = connectionHandler;
        if (h == null) {
            return;
        }
        h.post(new Runnable() {
            @Override
            public void run() {
                cancelScheduledReconnect();
                closeConnection(conn);
            }
        });
    }

    /**
     * Schedule a reconnection with exponential backoff (2 s ... 60 s, +/- 20 % jitter).
     * It never gives up; a configuration error is retried every few minutes (the files may appear).
     */
    private void scheduleReconnect() {
        if (!configured || connectionHandler == null) {
            return;
        }

        cancelScheduledReconnect();
        reconnectAttempts++;

        int delay;
        if (configError) {
            delay = CONFIG_ERROR_RETRY_MS;
        } else {
            delay = Math.min(RECONNECT_BASE_DELAY_MS * (1 << Math.min(reconnectAttempts - 1, 5)), RECONNECT_MAX_DELAY_MS);
            delay = (int) (delay * (0.8 + Math.random() * 0.4));
        }
        Log.i(TAG, "KaZaService: Reconnection attempt " + reconnectAttempts + " in " + (delay / 1000) + " s");

        updateForegroundNotification();

        reconnectRunnable = new Runnable() {
            @Override
            public void run() {
                reconnectRunnable = null;
                doConnect();
            }
        };
        connectionHandler.postDelayed(reconnectRunnable, delay);
    }

    private void cancelScheduledReconnect() {
        if (reconnectRunnable != null && connectionHandler != null) {
            connectionHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
    }

    /**
     * Start keep-alive mechanism with adaptive intervals
     * Dynamically adjusts PING frequency based on network type and device state
     */
    private void startKeepAlive() {
        if (keepAliveHandler == null) {
            keepAliveHandler = new Handler(connectionThread.getLooper());
        }

        stopKeepAlive(); // Stop any existing keep-alive

        updateKeepAliveInterval();
        Log.i(TAG, "KaZaService: Starting adaptive keep-alive - " + getKeepAliveMode()
              + " (" + (currentKeepAliveInterval / 1000) + "s)");

        keepAliveRunnable = new Runnable() {
            @Override
            public void run() {
                sendKeepAlive();

                // Recalculate interval before scheduling next ping (adaptive)
                updateKeepAliveInterval();

                if (isConnected && keepAliveHandler != null && keepAliveRunnable == this) {
                    keepAliveHandler.postDelayed(this, currentKeepAliveInterval);
                }
            }
        };

        keepAliveHandler.postDelayed(keepAliveRunnable, currentKeepAliveInterval);
    }

    private void stopKeepAlive() {
        if (keepAliveHandler != null) {
            if (keepAliveRunnable != null) {
                keepAliveHandler.removeCallbacks(keepAliveRunnable);
                keepAliveRunnable = null;
            }
            if (pongCheckRunnable != null) {
                keepAliveHandler.removeCallbacks(pongCheckRunnable);
                pongCheckRunnable = null;
            }
        }
    }

    /**
     * Send PING and arm the PONG deadline: any frame received after the PING proves the link
     * is alive, otherwise the connection is declared dead and re-established.
     */
    private void sendKeepAlive() {
        final Conn c = conn;
        if (c == null || c.closed) {
            return;
        }

        final long sentAt = System.currentTimeMillis();
        try {
            c.protocol.sendCommand("PING");
        } catch (Exception e) {
            Log.e(TAG, "KaZaService: Keep-alive failed: " + e.getMessage());
            onConnectionLost(c, "Keep-alive failed");
            return;
        }

        if (pongCheckRunnable != null) {
            keepAliveHandler.removeCallbacks(pongCheckRunnable);
        }
        pongCheckRunnable = new Runnable() {
            private boolean graceGiven = false;

            @Override
            public void run() {
                if (c != conn || c.closed || lastMessageReceivedTime >= sentAt) {
                    return;
                }
                long lateness = System.currentTimeMillis() - (sentAt + PONG_TIMEOUT_MS);
                if (!graceGiven && lateness > PONG_LATE_GRACE_MS) {
                    // The timer fired long after its due time (device was asleep): the reader
                    // thread has had no chance to deliver the PONG yet, wait one more period.
                    graceGiven = true;
                    keepAliveHandler.postDelayed(this, PONG_TIMEOUT_MS);
                    return;
                }
                onConnectionLost(c, "No answer to PING within " + (PONG_TIMEOUT_MS / 1000) + " s");
            }
        };
        keepAliveHandler.postDelayed(pongCheckRunnable, PONG_TIMEOUT_MS);
    }

    /**
     * Update keep-alive interval based on current conditions (adaptive)
     * Priority: Doze mode > Burst mode > Screen state > Idle state > Battery level > Network type
     */
    private void updateKeepAliveInterval() {
        int oldInterval = currentKeepAliveInterval;

        // Update idle detection
        deviceMonitor.updateIdleState();

        // Check if burst mode is active
        long now = System.currentTimeMillis();
        boolean inBurstMode = (burstModeEndTime > 0 && now < burstModeEndTime);

        // Disable burst mode if low battery (position tracking requires more power)
        if (inBurstMode && deviceMonitor.isLowBattery()) {
            Log.w(TAG, "KaZaService: Burst mode disabled due to low battery - using conservative intervals");
            burstModeEndTime = 0;
            inBurstMode = false;
        }

        // Priority 0: Doze mode - HIGHEST PRIORITY (Android compliance)
        // When in Doze, device is in deep sleep, use 15-minute intervals
        if (deviceMonitor.isInDozeMode()) {
            currentKeepAliveInterval = KEEPALIVE_DOZE_MS;
        }
        // Priority 1: Burst mode (position tracking) - Fast response required
        else if (inBurstMode) {
            currentKeepAliveInterval = KEEPALIVE_BURST_MODE_MS;
        }
        // Priority 2: Screen on = fast response (user active)
        else if (deviceMonitor.isScreenOn()) {
            currentKeepAliveInterval = KEEPALIVE_SCREEN_ON_MS;
        }
        // Priority 3: Low battery + screen off = ultra conservative
        else if (deviceMonitor.isLowBattery() && !deviceMonitor.isScreenOn()) {
            if (deviceMonitor.isDeviceIdle()) {
                currentKeepAliveInterval = KEEPALIVE_LOW_BATTERY_IDLE_MS;
            } else {
                currentKeepAliveInterval = KEEPALIVE_LOW_BATTERY_SCREEN_OFF_MS;
            }
        }
        // Priority 4: Device idle (no activity for 5+ minutes)
        else if (deviceMonitor.isDeviceIdle()) {
            currentKeepAliveInterval = KEEPALIVE_IDLE_MS;
        }
        // Priority 5: Screen off but not idle
        else if (!deviceMonitor.isScreenOn()) {
            currentKeepAliveInterval = KEEPALIVE_SCREEN_OFF_MS;
        }
        // Priority 6: Network type (WiFi vs Mobile)
        else if (deviceMonitor.isOnWifi()) {
            currentKeepAliveInterval = KEEPALIVE_WIFI_MS;
        } else if (deviceMonitor.isOnMobileData()) {
            currentKeepAliveInterval = KEEPALIVE_MOBILE_MS;
        } else {
            // Unknown network type, use conservative interval
            currentKeepAliveInterval = KEEPALIVE_MOBILE_MS;
        }

        // Log interval changes for debugging
        if (oldInterval != currentKeepAliveInterval) {
            Log.i(TAG, "KaZaService: Keep-alive interval adjusted: " +
                  (oldInterval / 1000) + "s → " + (currentKeepAliveInterval / 1000) + "s (" +
                  getKeepAliveMode() + ")");
        }

        // Early exit from burst mode if idle (no position requests for 30s)
        if (inBurstMode && tracker != null && tracker.getLastPositionRequestTime() > 0) {
            long timeSinceLastRequest = now - tracker.getLastPositionRequestTime();
            if (timeSinceLastRequest > BURST_MODE_IDLE_TIMEOUT_MS) {
                Log.i(TAG, "KaZaService: Burst mode early exit - no position requests for " +
                      (timeSinceLastRequest / 1000) + "s");
                burstModeEndTime = 0;
                burstModeStartTime = 0;
                inBurstMode = false;
            }
        }

        // Log burst mode expiration
        if (!inBurstMode && burstModeEndTime > 0 && now >= burstModeEndTime) {
            Log.i(TAG, "KaZaService: Burst mode expired - returning to normal intervals");
            burstModeEndTime = 0;
            burstModeStartTime = 0;
        }
    }

    /**
     * Get human-readable description of current keep-alive mode
     */
    private String getKeepAliveMode() {
        long now = System.currentTimeMillis();
        boolean inBurstMode = (burstModeEndTime > 0 && now < burstModeEndTime);

        if (deviceMonitor.isInDozeMode()) {
            return "DOZE MODE (deep sleep, 15min intervals)";
        } else if (inBurstMode) {
            long remainingSec = (burstModeEndTime - now) / 1000;
            return "BURST MODE (tracking active, " + remainingSec + "s remaining)";
        } else if (deviceMonitor.isScreenOn()) {
            return "Screen ON (fast response)";
        } else if (deviceMonitor.isLowBattery() && !deviceMonitor.isScreenOn()) {
            if (deviceMonitor.isDeviceIdle()) {
                return "LOW BATTERY + IDLE (4min ultra-save)";
            } else {
                return "LOW BATTERY + Screen OFF (3min save)";
            }
        } else if (deviceMonitor.isDeviceIdle()) {
            return "Device IDLE (2min battery save)";
        } else if (!deviceMonitor.isScreenOn()) {
            return "Screen OFF (90s battery save)";
        } else if (deviceMonitor.isOnWifi()) {
            return "WiFi (60s moderate)";
        } else if (deviceMonitor.isOnMobileData()) {
            return "Mobile Data (90s conservative)";
        } else {
            return "Unknown network";
        }
    }

    /**
     * Check if device is currently on WiFi
     */

    /**
     * Check if device is currently on mobile data
     */

    /**
     * Update idle detection based on time since last activity
     */

    /**
     * Check and log current App Standby Bucket (API 28+)
     * App Standby restricts background activity based on app usage patterns
     * Buckets: ACTIVE (10), WORKING_SET (20), FREQUENT (30), RARE (40), RESTRICTED (45), NEVER (50)
     */
    private void checkAppStandbyBucket() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.d(TAG, "KaZaService: App Standby Bucket monitoring not available (API < 28)");
            return;
        }

        try {
            android.app.usage.UsageStatsManager usageStatsManager =
                (android.app.usage.UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);

            if (usageStatsManager == null) {
                Log.w(TAG, "KaZaService: UsageStatsManager not available");
                return;
            }

            int oldBucket = currentStandbyBucket;
            currentStandbyBucket = usageStatsManager.getAppStandbyBucket();

            String bucketName = getStandbyBucketName(currentStandbyBucket);

            if (oldBucket != currentStandbyBucket) {
                if (currentStandbyBucket >= 40) { // RARE or worse
                    Log.w(TAG, "KaZaService: ⚠ App Standby Bucket: " + bucketName +
                          " (" + currentStandbyBucket + ") - severe restrictions may apply");
                    Log.w(TAG, "KaZaService: Consider requesting battery optimization exemption for better connectivity");
                } else {
                    Log.i(TAG, "KaZaService: App Standby Bucket: " + bucketName +
                          " (" + currentStandbyBucket + ")");
                }

                // Adaptive behavior based on standby bucket
                if (currentStandbyBucket >= 40) { // RARE or RESTRICTED
                    Log.i(TAG, "KaZaService: Adapting to restrictive standby bucket - may reduce connection attempts");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "KaZaService: Failed to check app standby bucket: " + e.getMessage());
        }
    }

    /**
     * Get human-readable name for standby bucket value
     */
    private String getStandbyBucketName(int bucket) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (bucket == android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE) {
                return "ACTIVE";
            } else if (bucket == android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET) {
                return "WORKING_SET";
            } else if (bucket == android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT) {
                return "FREQUENT";
            } else if (bucket == android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE) {
                return "RARE";
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                       bucket == android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED) {
                return "RESTRICTED";
            }
        }
        return "UNKNOWN (" + bucket + ")";
    }

    /**
     * Start periodic network type monitoring to adapt keep-alive intervals
     * Checks network type every 5 minutes and updates intervals if changed
     */
    private void startNetworkTypeMonitoring() {
        if (networkCheckHandler == null) {
            networkCheckHandler = new Handler(connectionThread.getLooper());
        }

        networkCheckRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    // Re-check app standby bucket periodically (can change based on usage)
                    checkAppStandbyBucket();

                    // Update keep-alive interval (will re-check network type internally)
                    updateKeepAliveInterval();

                    // Schedule next check
                    if (networkCheckHandler != null && networkCheckRunnable != null) {
                        networkCheckHandler.postDelayed(networkCheckRunnable, NETWORK_CHECK_INTERVAL_MS);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "KaZaService: Error in network type monitoring: " + e.getMessage());
                }
            }
        };

        // Start periodic checks
        networkCheckHandler.postDelayed(networkCheckRunnable, NETWORK_CHECK_INTERVAL_MS);
        Log.i(TAG, "KaZaService: Periodic network type monitoring started (every " +
              (NETWORK_CHECK_INTERVAL_MS / 1000 / 60) + " minutes)");
    }

    /**
     * Stop periodic network type monitoring
     */
    private void stopNetworkTypeMonitoring() {
        if (networkCheckHandler != null && networkCheckRunnable != null) {
            networkCheckHandler.removeCallbacks(networkCheckRunnable);
            Log.i(TAG, "KaZaService: Periodic network type monitoring stopped");
        }
        networkCheckRunnable = null;
        networkCheckHandler = null;
    }
}
