package org.kaza;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.NotificationChannel;
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
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
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
import java.io.InputStream;
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
 * Future features to implement:
 * - AlarmManager for periodic task execution
 * - SSL socket connection to KaZaServer
 */
public class NotificationService extends Service
{
    private static final String TAG = "NotificationService";
    private static final int NOTIFICATION_ID = 1001; // ID for foreground service notification
    private static final int USER_NOTIFICATION_ID_BASE = 2000; // Base ID for user notifications
    private static final String PREFS_NAME = "NotificationServicePrefs";

    private static NotificationService instance = null;
    private int notificationIdCounter = USER_NOTIFICATION_ID_BASE;

    // SSL Configuration
    private String sslClientCert = null;
    private String sslCaCert = null;
    private String sslClientKey = null;
    private String sslClientPass = null;
    private String sslHost = null;
    private int sslPort = 0;
    private String username = null;
    private boolean configured = false;

    // SSL Connection
    private SSLSocket sslSocket = null;
    private boolean isConnected = false;
    private KaZaProtocol protocol = null;
    private HandlerThread connectionThread = null;
    private Handler connectionHandler = null;
    private HandlerThread readerThread = null;
    private Handler readerHandler = null;

    // Reconnection state
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final int RECONNECT_BASE_DELAY_MS = 2000; // 2 seconds
    private static final int RECONNECT_MAX_DELAY_MS = 60000; // 60 seconds
    private Runnable reconnectRunnable = null;

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

    // Socket timeout - Dynamic based on device state for battery optimization
    private static final int SOCKET_TIMEOUT_ACTIVE_MS = 30000;     // 30s when active (screen on/burst)
    private static final int SOCKET_TIMEOUT_SCREEN_OFF_MS = 60000; // 60s when screen off
    private static final int SOCKET_TIMEOUT_DOZE_MS = 90000;       // 90s in Doze mode

    // Burst mode - Fast response after position request for 2 minutes
    private static final long BURST_MODE_DURATION_MS = 2 * 60 * 1000; // 2 minutes initial
    private static final long BURST_MODE_MAX_DURATION_MS = 5 * 60 * 1000; // 5 minutes max
    private static final long BURST_MODE_EXTENSION_MS = 1 * 60 * 1000; // Extend by 1 minute
    private static final long BURST_MODE_IDLE_TIMEOUT_MS = 30000; // Exit if no request for 30s
    private long burstModeEndTime = 0; // Timestamp when burst mode ends (0 = disabled)
    private long burstModeStartTime = 0; // Timestamp when burst mode started
    private long lastPositionRequestTime = 0; // Timestamp of last position request

    // Active location request settings for burst mode
    private static final int LOCATION_REQUEST_TIMEOUT_MS = 30000; // 30s max wait for GPS fix
    private android.location.LocationListener activeLocationListener = null;
    private final Object locationLock = new Object();
    private Location pendingLocation = null;

    // Smart location caching - Proactive location updates when screen ON
    private static final long LOCATION_CACHE_INTERVAL_MS = 5 * 60 * 1000; // Update cache every 5 min
    private static final long LOCATION_CACHE_MAX_AGE_MS = 10 * 60 * 1000; // Cache valid for 10 min
    private android.location.LocationListener cacheLocationListener = null;
    private Location cachedLocation = null;
    private long cachedLocationTime = 0;

    // Automatic position tracking - Send position to server when location changes significantly
    private static final long AUTO_POSITION_MIN_TIME_MS = 5 * 60 * 1000; // 5 minutes minimum interval
    private static final float AUTO_POSITION_MIN_DISTANCE_M = 30.0f; // 30 meters minimum distance
    private android.location.LocationListener autoPositionListener = null;

    private int currentKeepAliveInterval = KEEPALIVE_SCREEN_ON_MS; // Current active interval
    private Runnable keepAliveRunnable = null;
    private Handler keepAliveHandler = null;

    // Connection health monitoring
    private long lastMessageReceivedTime = System.currentTimeMillis();
    private static final int MAX_NO_RESPONSE_CYCLES = 5; // Reconnect if no response for 5 cycles

    // Network connectivity monitoring
    private ConnectivityManager.NetworkCallback networkCallback = null;

    // Screen state monitoring
    private BroadcastReceiver screenStateReceiver = null;
    private boolean isScreenOn = true;

    // Idle detection
    private boolean isDeviceIdle = false;
    private long lastActivityTime = System.currentTimeMillis();

    // Doze mode detection (API 23+)
    private boolean isInDozeMode = false;
    private BroadcastReceiver dozeReceiver = null;
    private PowerManager powerManager = null;

    // Battery level awareness
    private int batteryLevel = 100;
    private boolean isCharging = false;
    private boolean isLowBattery = false;
    private BroadcastReceiver batteryReceiver = null;

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

    /**
     * Send an Android notification with the given title and message
     * Each notification gets a unique ID so multiple notifications can be displayed
     */
    public void notify(String title, String message) {
        try {
            NotificationManager m_notificationManager = (NotificationManager)
                    this.getSystemService(Context.NOTIFICATION_SERVICE);

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
            }

            Bitmap icon = BitmapFactory.decodeResource(this.getResources(), R.drawable.icon);
            m_builder.setSmallIcon(R.drawable.icon)
                    .setLargeIcon(icon)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setDefaults(Notification.DEFAULT_ALL) // Sound, vibration, lights
                    .setColor(Color.GREEN)
                    .setAutoCancel(true)
                    .setStyle(new Notification.BigTextStyle().bigText(message)); // Support long text

            // Use unique notification ID so multiple notifications can be shown
            int notificationId = notificationIdCounter++;
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

        // Start as foreground service with persistent notification
        // Required for Android 8.0+ to keep service alive
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires explicit service type
            // Check if location permissions are granted before using LOCATION service type
            boolean hasLocationPermission = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean hasFineLocation = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
                boolean hasCoarseLocation = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
                hasLocationPermission = hasFineLocation || hasCoarseLocation;
                Log.i(TAG, "KaZaService: Location permissions - Fine: " + hasFineLocation + ", Coarse: " + hasCoarseLocation);
            }

            int serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (hasLocationPermission) {
                serviceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                Log.i(TAG, "KaZaService: Starting with DATA_SYNC + LOCATION service types");
            } else {
                Log.w(TAG, "KaZaService: Location permission not granted - starting with DATA_SYNC only");
                Log.w(TAG, "KaZaService: GPS features will not be available until location permission is granted");
            }

            startForeground(NOTIFICATION_ID, createForegroundNotification(), serviceType);
        } else {
            startForeground(NOTIFICATION_ID, createForegroundNotification());
        }

        Log.i(TAG, "KaZaService: Service created successfully - running in foreground");

        // Create background thread for network operations
        connectionThread = new HandlerThread("SSLConnectionThread");
        connectionThread.start();
        connectionHandler = new Handler(connectionThread.getLooper());

        // Register network connectivity monitor
        registerNetworkReceiver();

        // Register screen state monitor for adaptive keep-alive
        registerScreenStateReceiver();

        // Register battery level monitor for battery-aware intervals
        registerBatteryReceiver();

        // Register Doze mode monitor for deep sleep optimization (API 23+)
        registerDozeReceiver();

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

    @Override
    public void onDestroy() {
        Log.i(TAG, "KaZaService: Destroying Notification Service");
        Log.i(TAG, "KaZaService: Service was running in PID: " + android.os.Process.myPid());

        // Cancel any scheduled reconnections
        cancelScheduledReconnect();

        // Unregister receivers
        unregisterNetworkReceiver();
        unregisterScreenStateReceiver();
        unregisterBatteryReceiver();
        unregisterDozeReceiver();

        // Stop periodic network monitoring
        stopNetworkTypeMonitoring();

        // Stop location tracking
        stopLocationCaching();
        stopAutomaticPositionTracking();

        // Close SSL connection
        disconnectFromServer();

        // Stop connection thread
        if (connectionThread != null) {
            connectionThread.quitSafely();
            try {
                connectionThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "KaZaService: Error stopping connection thread", e);
            }
        }

        instance = null;

        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "KaZaService: onStartCommand called");
        Log.i(TAG, "KaZaService: Service running in PID: " + android.os.Process.myPid());

        // START_STICKY ensures Android will restart the service if killed
        return START_STICKY;
    }


    private Notification createForegroundNotification() {
        return createForegroundNotification(isConnected);
    }

    private Notification createForegroundNotification(boolean connected) {
        Log.i(TAG, "KaZaService: create Foreground Notification (connected: " + connected + ")");
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

        // Determine status based on connection state and reconnection attempts
        String statusText;
        int color;

        if (connected) {
            statusText = "KaZa service connected";
            color = Color.GREEN;
        } else if (reconnectAttempts > 0 && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            statusText = "KaZa is disconnected (reconnecting...)";
            color = 0xFFFFA500; // Orange
        } else {
            statusText = "KaZa is disconnected";
            color = Color.RED;
        }

        return builder.setSmallIcon(R.drawable.icon)
                .setLargeIcon(icon)
                .setContentTitle("KaZa")
                .setContentText(statusText)
                .setColor(color)
                .build();
    }

    /**
     * Update the foreground notification with current connection status
     */
    private void updateForegroundNotification() {
        try {
            NotificationManager notificationManager = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                Notification notification = createForegroundNotification(isConnected);
                notificationManager.notify(NOTIFICATION_ID, notification);
                Log.d(TAG, "KaZaService: Foreground notification updated (connected: " + isConnected + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "KaZaService: Failed to update notification: " + e.getMessage());
        }
    }

    /**
     * Connect to SSL server in background thread
     */
    private void connectToServer() {
        if (!configured) {
            Log.w(TAG, "KaZaService: Cannot connect - not configured");
            return;
        }

        if (isConnected) {
            Log.i(TAG, "KaZaService: Already connected to server");
            return;
        }

        Log.i(TAG, "KaZaService: Initiating connection to " + sslHost + ":" + sslPort);

        // Run connection on background thread
        connectionHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.i(TAG, "KaZaService: Loading SSL certificates...");
                    Log.i(TAG, "KaZaService: CA cert path: " + sslCaCert);

                    // Load CA certificate
                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    InputStream caInput = null;
                    Certificate ca = null;

                    try {
                        // Remove file:// prefix if present
                        String caCertPath = sslCaCert;
                        if (caCertPath.startsWith("file://")) {
                            caCertPath = caCertPath.substring(7);
                        }

                        Log.i(TAG, "KaZaService: Reading CA cert from: " + caCertPath);
                        caInput = new FileInputStream(caCertPath);
                        ca = cf.generateCertificate(caInput);
                        Log.i(TAG, "KaZaService: ✓ CA certificate loaded: " + ((java.security.cert.X509Certificate) ca).getSubjectDN());
                    } finally {
                        if (caInput != null) {
                            caInput.close();
                        }
                    }

                    // Create a KeyStore containing our trusted CA certificate
                    String keyStoreType = KeyStore.getDefaultType();
                    KeyStore keyStore = KeyStore.getInstance(keyStoreType);
                    keyStore.load(null, null);
                    keyStore.setCertificateEntry("ca", ca);
                    Log.i(TAG, "KaZaService: CA certificate added to KeyStore");

                    // Create a TrustManager that trusts the CA certificate
                    String tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
                    TrustManagerFactory tmf = TrustManagerFactory.getInstance(tmfAlgorithm);
                    tmf.init(keyStore);
                    Log.i(TAG, "KaZaService: TrustManager created");

                    // Load client certificate and key for mutual TLS
                    Log.i(TAG, "KaZaService: Loading client certificate...");
                    Log.i(TAG, "KaZaService: Client cert path: " + sslClientCert);
                    Log.i(TAG, "KaZaService: Client key path: " + sslClientKey);

                    // Load client certificate
                    String clientCertPath = sslClientCert;
                    if (clientCertPath.startsWith("file://")) {
                        clientCertPath = clientCertPath.substring(7);
                    }

                    InputStream clientCertInput = new FileInputStream(clientCertPath);
                    Certificate clientCert = cf.generateCertificate(clientCertInput);
                    clientCertInput.close();
                    Log.i(TAG, "KaZaService: Client certificate loaded: " +
                        ((java.security.cert.X509Certificate) clientCert).getSubjectDN());

                    // Load private key
                    String clientKeyPath = sslClientKey;
                    if (clientKeyPath.startsWith("file://")) {
                        clientKeyPath = clientKeyPath.substring(7);
                    }

                    PrivateKey privateKey = loadPrivateKey(clientKeyPath, sslClientPass);
                    Log.i(TAG, "KaZaService: Private key loaded");

                    // Create KeyStore with client certificate and private key
                    KeyStore clientKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                    clientKeyStore.load(null, null);
                    clientKeyStore.setKeyEntry("client", privateKey,
                        sslClientPass != null ? sslClientPass.toCharArray() : new char[0],
                        new Certificate[]{clientCert});
                    Log.i(TAG, "KaZaService: Client KeyStore created");

                    // Create KeyManager with client certificate
                    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                    kmf.init(clientKeyStore, sslClientPass != null ? sslClientPass.toCharArray() : new char[0]);
                    Log.i(TAG, "KaZaService: KeyManager created");

                    // Create SSL context with both KeyManager and TrustManager
                    SSLContext sslContext = SSLContext.getInstance("TLS");
                    sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
                    Log.i(TAG, "KaZaService: SSL context created with client certificate");

                    Log.i(TAG, "KaZaService: Connecting to " + sslHost + ":" + sslPort + "...");

                    // Create SSL socket
                    sslSocket = (SSLSocket) sslContext.getSocketFactory().createSocket(sslHost, sslPort);

                    // Set dynamic socket timeout based on device state for battery optimization
                    // This prevents blocking forever on read operations
                    int socketTimeout = getSocketTimeout();
                    sslSocket.setSoTimeout(socketTimeout);
                    Log.i(TAG, "KaZaService: Socket timeout set to " + (socketTimeout / 1000) + " seconds (adaptive)");

                    sslSocket.startHandshake();

                    // Enable TCP keep-alive for battery efficiency (kernel-level, no app wake-ups)
                    sslSocket.setKeepAlive(true);
                    sslSocket.setTcpNoDelay(true);
                    Log.i(TAG, "KaZaService: TCP keep-alive enabled on socket");

                    isConnected = true;

                    // Reset reconnection attempts on successful connection
                    reconnectAttempts = 0;
                    cancelScheduledReconnect();

                    // Reset connection health monitoring timestamp
                    lastMessageReceivedTime = System.currentTimeMillis();

                    Log.i(TAG, "KaZaService: Connected successfully to " + sslHost + ":" + sslPort);
                    Log.i(TAG, "KaZaService: SSL Session: " + sslSocket.getSession().getCipherSuite());
                    Log.i(TAG, "KaZaService: Server certificate validated successfully");

                    // Initialize KaZa protocol
                    try {
                        protocol = new KaZaProtocol(sslSocket.getInputStream(), sslSocket.getOutputStream());
                        Log.i(TAG, "KaZaService: KaZa protocol initialized");

                        // Get device name from Android system
                        String deviceName = Build.MODEL; // e.g., "Pixel 5", "Galaxy S21"

                        // Send VERSION as FIRST frame (protocol version negotiation)
                        // Parameters: username (from config), device name (from Android), channel (0)
                        protocol.sendVersion(username, deviceName, 0);
                        Log.i(TAG, "KaZaService: VERSION frame sent - waiting for response");

                        // Start reader thread for incoming messages
                        // The reader will handle VERSION response and then we can send other commands
                        startReaderThread();

                        // Start keep-alive mechanism to detect dead connections
                        startKeepAlive();

                    } catch (Exception e) {
                        Log.e(TAG, "KaZaService: ERROR: Failed to initialize protocol: " + e.getMessage());
                        throw e;
                    }

                } catch (java.io.FileNotFoundException e) {
                    isConnected = false;
                    Log.e(TAG, "KaZaService: ERROR: CA certificate file not found: " + sslCaCert);
                    Log.e(TAG, "KaZaService: Please check the certificate path");
                    // Don't retry for file not found - it's a configuration error
                    Log.e(TAG, "KaZaService: Stopping reconnection attempts - fix configuration first");
                } catch (java.security.cert.CertificateException e) {
                    isConnected = false;
                    Log.e(TAG, "KaZaService: ERROR: Invalid certificate: " + e.getMessage());
                    // Don't retry for certificate errors - it's a configuration error
                    Log.e(TAG, "KaZaService: Stopping reconnection attempts - fix certificate first");
                } catch (Exception e) {
                    isConnected = false;
                    String errorMsg = e.getMessage();
                    Log.e(TAG, "KaZaService: ERROR: Connection failed: " + errorMsg, e);

                    // Check if this is a permanent error (key format, etc.)
                    if (errorMsg != null && (errorMsg.contains("PBES2") ||
                                            errorMsg.contains("Unsupported encryption") ||
                                            errorMsg.contains("decrypt PKCS#8"))) {
                        Log.e(TAG, "KaZaService: Permanent key decryption error - stopping reconnection");
                        Log.e(TAG, "KaZaService: Please convert your private key to a compatible format");
                        // Reset reconnection attempts to avoid spamming logs
                        reconnectAttempts = MAX_RECONNECT_ATTEMPTS;
                    } else {
                        // Only retry for transient errors (network, timeout, etc.)
                        scheduleReconnect();
                    }
                }
            }
        });
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
     * Start background thread to read incoming protocol frames
     */
    private void startReaderThread() {
        if (readerThread != null) {
            stopReaderThread();
        }

        readerThread = new HandlerThread("KaZaReaderThread");
        readerThread.start();
        readerHandler = new Handler(readerThread.getLooper());

        Log.i(TAG, "KaZaService: Starting protocol reader thread");

        readerHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "KaZaService: Reader thread started, waiting for frames...");
                try {
                    while (isConnected && sslSocket != null && !sslSocket.isClosed()) {
                        try {
                            // Read a frame (BLOCKING call - waits for data)
                            KaZaProtocol.ReceivedFrame frame = protocol.readFrame();

                            // Process received frame
                            handleReceivedFrame(frame);

                        } catch (java.net.SocketTimeoutException e) {
                            // Socket timeout (30s) - no data received, this is normal
                            // Just continue and wait for more data
                            Log.d(TAG, "KaZaService: Read timeout (no data for 30s), continuing...");
                            continue;
                        } catch (java.io.EOFException e) {
                            Log.w(TAG, "KaZaService: Connection closed by server (EOF)");
                            handleConnectionError("Connection closed by server");
                            break;
                        } catch (java.io.IOException e) {
                            Log.e(TAG, "KaZaService: Read error: " + e.getMessage());
                            handleConnectionError("Socket read error: " + e.getMessage());
                            break;
                        }
                    }
                    Log.i(TAG, "KaZaService: Reader thread exiting");
                } catch (Exception e) {
                    Log.e(TAG, "KaZaService: Reader thread error: " + e.getMessage(), e);
                    handleConnectionError("Reader thread error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Stop the reader thread
     */
    private void stopReaderThread() {
        if (readerThread != null) {
            Log.i(TAG, "KaZaService: Stopping reader thread");
            readerThread.quitSafely();
            try {
                readerThread.join(1000); // Wait up to 1 second
            } catch (InterruptedException e) {
                Log.w(TAG, "KaZaService: Interrupted while waiting for reader thread");
            }
            readerThread = null;
            readerHandler = null;
        }
    }

    /**
     * Handle received protocol frame
     */
    private void handleReceivedFrame(KaZaProtocol.ReceivedFrame frame) {
        String position;
        // Update last message received time (for connection health monitoring)
        lastMessageReceivedTime = System.currentTimeMillis();

        // Skip logging for PING/PONG frames to reduce battery drain
        boolean isPingPong = (frame.isCommand() &&
                             (frame.getPayloadAsString().equals("PING") ||
                              frame.getPayloadAsString().equals("PONG")));
        if (!isPingPong) {
            Log.i(TAG, "KaZaService: Processing frame - Type: " + frame.frameType + ", Size: " + frame.payload.length);
        }

        if (frame.isCommand()) {
            String command = frame.getPayloadAsString();

            // Skip logging for PING/PONG to reduce battery drain
            if (!command.equals("PING") && !command.equals("PONG")) {
                Log.i(TAG, "KaZaService: Received command: \"" + command + "\"");
                // Update activity time for meaningful commands (not keep-alive)
                lastActivityTime = System.currentTimeMillis();
            }

            // Check for NOTIFY: command
            if (command.startsWith("NOTIFY:")) {
                String notificationText = command.substring(7); // Remove "NOTIFY:" prefix
                Log.i(TAG, "KaZaService: Showing notification: " + notificationText);

                // Send Android notification
                notify("KaZa Alert", notificationText);
                Log.i(TAG, "KaZaService: Notification sent successfully");
                return;
            }

            // Handle version negotiation errors
            if (command.startsWith("ERROR:")) {
                String errorMsg = command.substring(6).trim(); // Remove "ERROR:" prefix
                Log.e(TAG, "KaZaService: Server error: " + errorMsg);

                // Check if it's a version incompatibility error
                if (errorMsg.contains("Protocol version incompatible") ||
                    errorMsg.contains("version")) {
                    Log.e(TAG, "KaZaService: Version negotiation FAILED - disconnecting");
                    handleConnectionError("Protocol version incompatible: " + errorMsg);
                }
                return;
            }

            // Handle other commands
            switch (command) {
                case "CONNECTED":
                    Log.i(TAG, "KaZaService: ✓ Version negotiation successful - connection established");
                    // Protocol is now ready - connection fully established

                    // Send initial position
                    position = getGPSPosition();
                    try {
                        protocol.sendCommand("POSITION:" + position);
                        Log.i(TAG, "KaZaService: Sent initial position: " + position);
                    } catch (Exception e) {
                        Log.e(TAG, "KaZaService: Failed to send position: " + e.getMessage());
                    }

                    // Start automatic position tracking (battery-efficient)
                    startAutomaticPositionTracking();
                    break;

                case "DISCONNECT":
                    Log.i(TAG, "KaZaService: Server requested disconnect");
                    disconnectFromServer();
                    break;

                case "POSITION?":
                    Log.i(TAG, "KaZaService: Position request received");

                    // Adaptive burst mode logic
                    long now = System.currentTimeMillis();
                    boolean wasInBurstMode = (burstModeEndTime > 0 && now < burstModeEndTime);
                    lastPositionRequestTime = now;

                    if (!wasInBurstMode) {
                        // Starting new burst mode
                        burstModeStartTime = now;
                        burstModeEndTime = now + BURST_MODE_DURATION_MS;
                        Log.i(TAG, "KaZaService: Entering BURST MODE for 2 minutes (position tracking active)");
                    } else {
                        // Already in burst mode - check if we should extend
                        long timeSinceStart = now - burstModeStartTime;
                        long timeRemaining = burstModeEndTime - now;

                        if (timeSinceStart < BURST_MODE_MAX_DURATION_MS && timeRemaining < 60000) {
                            // Less than 1 minute remaining and not at max duration - extend
                            long newEndTime = now + BURST_MODE_EXTENSION_MS;
                            if (newEndTime - burstModeStartTime <= BURST_MODE_MAX_DURATION_MS) {
                                burstModeEndTime = newEndTime;
                                long totalDuration = (burstModeEndTime - burstModeStartTime) / 1000;
                                Log.i(TAG, "KaZaService: Burst mode extended (total duration: " + totalDuration + "s)");
                            } else {
                                Log.i(TAG, "KaZaService: Burst mode at max duration (5min) - not extending");
                            }
                        } else {
                            Log.d(TAG, "KaZaService: Continuing burst mode (" + (timeRemaining / 1000) + "s remaining)");
                        }
                    }

                    position = getGPSPosition();
                    try {
                        protocol.sendCommand("POSITION:" + position);
                        Log.i(TAG, "KaZaService: Sent position: " + position);
                    } catch (Exception e) {
                        Log.e(TAG, "KaZaService: Failed to send position: " + e.getMessage());
                    }
                    break;

                default:
                    Log.i(TAG, "KaZaService: Unknown command: " + command);
                    break;
            }
        } else if (frame.isFile()) {
            Log.i(TAG, "KaZaService: Received file frame (" + frame.payload.length + " bytes)");
            // TODO: Handle file frames
        } else {
            Log.i(TAG, "KaZaService: Received frame type: " + frame.frameType);
            // TODO: Handle other frame types
        }
    }

    /**
     * Get current GPS position
     * Strategy: Use active GPS fix during burst mode + screen off for accuracy,
     *           otherwise use cached location for battery efficiency
     * @return Position string in format "lat,lon,accuracy,provider" or error message
     */
    private String getGPSPosition() {
        try {
            long now = System.currentTimeMillis();

            // Check if we have a fresh cached location (screen ON case)
            // Only use cache if NOT in burst mode with screen OFF (which needs active GPS)
            boolean inBurstMode = (burstModeEndTime > 0 && now < burstModeEndTime);
            boolean needActiveFix = inBurstMode && !isScreenOn;

            if (!needActiveFix && cachedLocation != null && cachedLocationTime > 0) {
                long cacheAge = now - cachedLocationTime;
                if (cacheAge < LOCATION_CACHE_MAX_AGE_MS) {
                    String positionString = String.format("%.6f:%.6f:%.2f:%.1f:%s",
                        cachedLocation.getLatitude(),
                        cachedLocation.getLongitude(),
                        cachedLocation.getAltitude(),
                        cachedLocation.getAccuracy(),
                        cachedLocation.getProvider());
                    Log.i(TAG, "KaZaService: Position from cache (age: " + (cacheAge / 1000) + "s): " + positionString);
                    return positionString;
                } else {
                    Log.d(TAG, "KaZaService: Cached location too old (" + (cacheAge / 1000) + "s) - requesting fresh location");
                }
            }

            // Check permissions first
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ requires background location permission for background access
                boolean hasBackgroundLocation = checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
                boolean hasFineLocation = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;

                Log.i(TAG, "KaZaService: Fine location permission: " + hasFineLocation);
                Log.i(TAG, "KaZaService: Background location permission: " + hasBackgroundLocation);

                if (!hasFineLocation) {
                    return "ERROR:No location permission";
                }
                if (!hasBackgroundLocation) {
                    Log.w(TAG, "KaZaService: ⚠ No background location permission - location only available while app is open");
                    Log.w(TAG, "KaZaService: User needs to grant 'Allow all the time' in app settings");
                    // Try anyway - might work if app is in foreground
                }
            }

            LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

            if (locationManager == null) {
                Log.w(TAG, "KaZaService: LocationManager not available");
                return "ERROR:LocationManager not available";
            }

            // Check if location services are enabled
            boolean gpsEnabled = false;
            boolean networkEnabled = false;

            try {
                gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            } catch (Exception e) {
                Log.w(TAG, "KaZaService: GPS provider check failed: " + e.getMessage());
            }

            try {
                networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            } catch (Exception e) {
                Log.w(TAG, "KaZaService: Network provider check failed: " + e.getMessage());
            }

            if (!gpsEnabled && !networkEnabled) {
                Log.w(TAG, "KaZaService: No location providers enabled");
                return "ERROR:Location services disabled";
            }

            Log.i(TAG, "KaZaService: GPS enabled: " + gpsEnabled + ", Network enabled: " + networkEnabled);

            // Determine if we should request active GPS fix (reuse variables from cache check above)
            boolean shouldRequestActiveFix = needActiveFix && gpsEnabled;

            // Request active GPS fix during burst mode when screen is off (accurate tracking needed)
            if (shouldRequestActiveFix) {
                Log.i(TAG, "KaZaService: Burst mode + screen off - requesting active GPS fix for accuracy");
                Location freshLocation = requestSingleLocationUpdate(locationManager);
                if (freshLocation != null) {
                    String positionString = String.format("%.6f:%.6f:%.2f:%.1f:%s",
                        freshLocation.getLatitude(),
                        freshLocation.getLongitude(),
                        cachedLocation.getAltitude(),
                        freshLocation.getAccuracy(),
                        freshLocation.getProvider());
                    Log.i(TAG, "KaZaService: Fresh position (active GPS): " + positionString);
                    return positionString;
                }
                Log.w(TAG, "KaZaService: Active GPS fix timeout - falling back to cached location");
            }

            // Fallback: Use cached location (battery efficient for screen on or normal mode)
            Location bestLocation = null;

            // Try GPS provider first (most accurate)
            if (gpsEnabled) {
                try {
                    Location gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (gpsLocation != null) {
                        bestLocation = gpsLocation;
                        Log.i(TAG, "KaZaService: Got GPS location (age: " +
                            (System.currentTimeMillis() - gpsLocation.getTime()) / 1000 + "s)");
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "KaZaService: No permission for GPS location: " + e.getMessage());
                    return "ERROR:No location permission";
                }
            }

            // Try network provider if GPS not available or as fallback
            if (networkEnabled) {
                try {
                    Location networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    if (networkLocation != null) {
                        // Use network location if no GPS location, or if network location is newer
                        if (bestLocation == null || networkLocation.getTime() > bestLocation.getTime()) {
                            bestLocation = networkLocation;
                            Log.i(TAG, "KaZaService: Got Network location (age: " +
                                (System.currentTimeMillis() - networkLocation.getTime()) / 1000 + "s)");
                        }
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "KaZaService: No permission for Network location: " + e.getMessage());
                    if (bestLocation == null) {
                        return "ERROR:No location permission";
                    }
                }
            }

            if (bestLocation == null) {
                Log.w(TAG, "KaZaService: No last known location available");
                return "ERROR:No location available";
            }

            // Format: "lat,lon,accuracy,provider"
            String positionString = String.format("%.6f:%.6f:%.2f:%.1f:%s",
                bestLocation.getLatitude(),
                bestLocation.getLongitude(),
                bestLocation.getAltitude(),
                bestLocation.getAccuracy(),
                bestLocation.getProvider());

            Log.i(TAG, "KaZaService: Position (cached): " + positionString);
            return positionString;

        } catch (Exception e) {
            Log.e(TAG, "KaZaService: Error getting GPS position: " + e.getMessage(), e);
            return "ERROR:" + e.getMessage();
        }
    }

    /**
     * Request a single fresh GPS location update with timeout
     * Used during burst mode for accurate position tracking
     * @param locationManager LocationManager instance
     * @return Fresh Location or null if timeout/error
     */
    private Location requestSingleLocationUpdate(LocationManager locationManager) {
        synchronized (locationLock) {
            pendingLocation = null;

            try {
                // Create one-time location listener
                activeLocationListener = new android.location.LocationListener() {
                    @Override
                    public void onLocationChanged(Location location) {
                        synchronized (locationLock) {
                            if (pendingLocation == null || location.getAccuracy() < pendingLocation.getAccuracy()) {
                                pendingLocation = location;
                                Log.i(TAG, "KaZaService: Received fresh GPS fix (accuracy: " +
                                      location.getAccuracy() + "m)");
                            }
                            locationLock.notifyAll();
                        }
                    }

                    @Override
                    public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}

                    @Override
                    public void onProviderEnabled(String provider) {}

                    @Override
                    public void onProviderDisabled(String provider) {}
                };

                // Request single update from GPS provider
                locationManager.requestSingleUpdate(
                    LocationManager.GPS_PROVIDER,
                    activeLocationListener,
                    connectionThread.getLooper()
                );

                Log.i(TAG, "KaZaService: Waiting for GPS fix (max " +
                      (LOCATION_REQUEST_TIMEOUT_MS / 1000) + "s)...");

                // Wait for location update with timeout
                locationLock.wait(LOCATION_REQUEST_TIMEOUT_MS);

                // Clean up listener
                locationManager.removeUpdates(activeLocationListener);
                activeLocationListener = null;

                return pendingLocation;

            } catch (SecurityException e) {
                Log.e(TAG, "KaZaService: No permission for active location request: " + e.getMessage());
                return null;
            } catch (InterruptedException e) {
                Log.w(TAG, "KaZaService: Location request interrupted: " + e.getMessage());
                if (activeLocationListener != null) {
                    try {
                        locationManager.removeUpdates(activeLocationListener);
                    } catch (Exception ex) {
                        // Ignore cleanup errors
                    }
                    activeLocationListener = null;
                }
                return null;
            } catch (Exception e) {
                Log.e(TAG, "KaZaService: Error requesting location update: " + e.getMessage());
                if (activeLocationListener != null) {
                    try {
                        locationManager.removeUpdates(activeLocationListener);
                    } catch (Exception ex) {
                        // Ignore cleanup errors
                    }
                    activeLocationListener = null;
                }
                return null;
            }
        }
    }

    /**
     * Disconnect from SSL server
     */
    private void disconnectFromServer() {
        Log.i(TAG, "KaZaService: Disconnecting from server");

        // Stop automatic position tracking
        stopAutomaticPositionTracking();

        // Stop keep-alive
        stopKeepAlive();

        // Stop reader thread first
        stopReaderThread();

        // Close protocol and reset negotiation state
        if (protocol != null) {
            try {
                protocol.close();
                Log.i(TAG, "KaZaService: Protocol closed");
            } catch (Exception e) {
                Log.e(TAG, "KaZaService: Error closing protocol", e);
            }
            protocol = null;
        }

        // Close socket
        if (sslSocket != null) {
            try {
                sslSocket.close();
                Log.i(TAG, "KaZaService: Socket closed");
            } catch (Exception e) {
                Log.e(TAG, "KaZaService: Error closing socket", e);
            }
            sslSocket = null;
        }

        isConnected = false;
        Log.i(TAG, "KaZaService: Disconnected");

        // Update notification to show disconnected status
        updateForegroundNotification();
    }

    /**
     * Schedule reconnection with exponential backoff
     */
    private void scheduleReconnect() {
        if (!configured) {
            Log.w(TAG, "KaZaService: Not configured, cannot reconnect");
            return;
        }

        // Cancel any pending reconnect
        cancelScheduledReconnect();

        reconnectAttempts++;
        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "KaZaService: Max reconnection attempts reached, giving up");
            // Update notification to show final disconnected state
            updateForegroundNotification();
            return;
        }

        // Calculate delay with exponential backoff: 2s, 4s, 8s, 16s, 32s, 60s (max)
        int delay = Math.min(
            RECONNECT_BASE_DELAY_MS * (1 << (reconnectAttempts - 1)),
            RECONNECT_MAX_DELAY_MS
        );

        Log.i(TAG, "KaZaService: Scheduling reconnection attempt " + reconnectAttempts +
                   " in " + (delay / 1000) + " seconds");

        // Update notification to show reconnecting state
        updateForegroundNotification();

        reconnectRunnable = new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "KaZaService: Reconnection attempt " + reconnectAttempts);
                connectToServer();
            }
        };

        connectionHandler.postDelayed(reconnectRunnable, delay);
    }

    /**
     * Cancel scheduled reconnection
     */
    private void cancelScheduledReconnect() {
        if (reconnectRunnable != null && connectionHandler != null) {
            connectionHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
            Log.d(TAG, "KaZaService: Cancelled scheduled reconnection");
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

        // Calculate optimal interval based on current conditions
        updateKeepAliveInterval();

        String intervalMode = getKeepAliveMode();
        Log.i(TAG, "KaZaService: Starting adaptive keep-alive - " + intervalMode + " (" + (currentKeepAliveInterval / 1000) + "s)");

        keepAliveRunnable = new Runnable() {
            @Override
            public void run() {
                sendKeepAlive();

                // Recalculate interval before scheduling next ping (adaptive)
                updateKeepAliveInterval();

                // Schedule next keep-alive with updated interval
                if (isConnected && keepAliveHandler != null) {
                    keepAliveHandler.postDelayed(this, currentKeepAliveInterval);
                }
            }
        };

        keepAliveHandler.postDelayed(keepAliveRunnable, currentKeepAliveInterval);
    }

    /**
     * Stop keep-alive mechanism
     */
    private void stopKeepAlive() {
        if (keepAliveRunnable != null && keepAliveHandler != null) {
            keepAliveHandler.removeCallbacks(keepAliveRunnable);
            keepAliveRunnable = null;
            Log.d(TAG, "KaZaService: Keep-alive stopped");
        }
    }

    /**
     * Send keep-alive ping
     */
    private void sendKeepAlive() {
        if (!isConnected || protocol == null ) {
            // Silently skip if not connected (avoid log spam)
            return;
        }

        // Connection health timeout monitoring
        long now = System.currentTimeMillis();
        long timeSinceLastMessage = now - lastMessageReceivedTime;
        long maxTimeout = MAX_NO_RESPONSE_CYCLES * currentKeepAliveInterval;

        if (timeSinceLastMessage > maxTimeout) {
            Log.w(TAG, "KaZaService: Connection timeout - no response for " +
                  (timeSinceLastMessage / 1000) + "s (max: " + (maxTimeout / 1000) + "s)");
            handleConnectionError("Connection health timeout");
            return;
        }

        try {
            // Send PING without logging (battery optimization)
            protocol.sendCommand("PING");
        } catch (Exception e) {
            Log.e(TAG, "KaZaService: Keep-alive failed: " + e.getMessage());
            // Connection is dead, trigger reconnection
            handleConnectionError("Keep-alive failed");
        }
    }

    /**
     * Handle connection error and trigger reconnection
     */
    private void handleConnectionError(String reason) {
        Log.e(TAG, "KaZaService: Connection error: " + reason);

        if (!isConnected) {
            // Already disconnected, nothing to do
            return;
        }

        // Disconnect and schedule reconnection
        disconnectFromServer();
        scheduleReconnect();
    }

    /**
     * Register network connectivity callback to detect network changes
     */
    private void registerNetworkReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                ConnectivityManager connectivityManager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

                if (connectivityManager == null) {
                    Log.w(TAG, "KaZaService: ConnectivityManager not available");
                    return;
                }

                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        Log.i(TAG, "KaZaService: Network available - " + network);
                        // Network is back, attempt to reconnect if disconnected
                        if (!isConnected && configured) {
                            Log.i(TAG, "KaZaService: Network restored, attempting reconnection");
                            // Reset reconnection attempts to allow immediate reconnection
                            reconnectAttempts = 0;
                            cancelScheduledReconnect();
                            connectToServer();
                        }
                    }

                    @Override
                    public void onLost(Network network) {
                        Log.w(TAG, "KaZaService: Network lost - " + network);
                        // Network lost, disconnect if connected
                        if (isConnected) {
                            Log.w(TAG, "KaZaService: Disconnecting due to network loss");
                            disconnectFromServer();
                        }
                    }

                    @Override
                    public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                        boolean hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                        boolean validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                        Log.d(TAG, "KaZaService: Network capabilities changed - Internet: " + hasInternet + ", Validated: " + validated);
                    }
                };

                // Register callback for any network
                NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();

                connectivityManager.registerNetworkCallback(request, networkCallback);
                Log.i(TAG, "KaZaService: Network connectivity monitoring registered");

            } catch (Exception e) {
                Log.e(TAG, "KaZaService: Failed to register network callback: " + e.getMessage());
            }
        } else {
            Log.i(TAG, "KaZaService: Network monitoring requires Android N+ (API 24+)");
        }
    }

    /**
     * Unregister network connectivity callback
     */
    private void unregisterNetworkReceiver() {
        if (networkCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                ConnectivityManager connectivityManager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

                if (connectivityManager != null) {
                    connectivityManager.unregisterNetworkCallback(networkCallback);
                    Log.i(TAG, "KaZaService: Network connectivity monitoring unregistered");
                }
            } catch (Exception e) {
                Log.e(TAG, "KaZaService: Failed to unregister network callback: " + e.getMessage());
            }
            networkCallback = null;
        }
    }

    /**
     * Update keep-alive interval based on current conditions (adaptive)
     * Priority: Doze mode > Burst mode > Screen state > Idle state > Battery level > Network type
     */
    private void updateKeepAliveInterval() {
        int oldInterval = currentKeepAliveInterval;

        // Update idle detection
        updateIdleState();

        // Check if burst mode is active
        long now = System.currentTimeMillis();
        boolean inBurstMode = (burstModeEndTime > 0 && now < burstModeEndTime);

        // Disable burst mode if low battery (position tracking requires more power)
        if (inBurstMode && isLowBattery) {
            Log.w(TAG, "KaZaService: Burst mode disabled due to low battery - using conservative intervals");
            burstModeEndTime = 0;
            inBurstMode = false;
        }

        // Priority 0: Doze mode - HIGHEST PRIORITY (Android compliance)
        // When in Doze, device is in deep sleep, use 15-minute intervals
        if (isInDozeMode) {
            currentKeepAliveInterval = KEEPALIVE_DOZE_MS;
        }
        // Priority 1: Burst mode (position tracking) - Fast response required
        else if (inBurstMode) {
            currentKeepAliveInterval = KEEPALIVE_BURST_MODE_MS;
        }
        // Priority 2: Screen on = fast response (user active)
        else if (isScreenOn) {
            currentKeepAliveInterval = KEEPALIVE_SCREEN_ON_MS;
        }
        // Priority 3: Low battery + screen off = ultra conservative
        else if (isLowBattery && !isScreenOn) {
            if (isDeviceIdle) {
                currentKeepAliveInterval = KEEPALIVE_LOW_BATTERY_IDLE_MS;
            } else {
                currentKeepAliveInterval = KEEPALIVE_LOW_BATTERY_SCREEN_OFF_MS;
            }
        }
        // Priority 4: Device idle (no activity for 5+ minutes)
        else if (isDeviceIdle) {
            currentKeepAliveInterval = KEEPALIVE_IDLE_MS;
        }
        // Priority 5: Screen off but not idle
        else if (!isScreenOn) {
            currentKeepAliveInterval = KEEPALIVE_SCREEN_OFF_MS;
        }
        // Priority 6: Network type (WiFi vs Mobile)
        else if (isOnWifi()) {
            currentKeepAliveInterval = KEEPALIVE_WIFI_MS;
        } else if (isOnMobileData()) {
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
        if (inBurstMode && lastPositionRequestTime > 0) {
            long timeSinceLastRequest = now - lastPositionRequestTime;
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

        if (isInDozeMode) {
            return "DOZE MODE (deep sleep, 15min intervals)";
        } else if (inBurstMode) {
            long remainingSec = (burstModeEndTime - now) / 1000;
            return "BURST MODE (tracking active, " + remainingSec + "s remaining)";
        } else if (isScreenOn) {
            return "Screen ON (fast response)";
        } else if (isLowBattery && !isScreenOn) {
            if (isDeviceIdle) {
                return "LOW BATTERY + IDLE (4min ultra-save)";
            } else {
                return "LOW BATTERY + Screen OFF (3min save)";
            }
        } else if (isDeviceIdle) {
            return "Device IDLE (2min battery save)";
        } else if (!isScreenOn) {
            return "Screen OFF (90s battery save)";
        } else if (isOnWifi()) {
            return "WiFi (60s moderate)";
        } else if (isOnMobileData()) {
            return "Mobile Data (90s conservative)";
        } else {
            return "Unknown network";
        }
    }

    /**
     * Calculate dynamic socket timeout based on device state for battery optimization
     * @return Socket timeout in milliseconds
     */
    private int getSocketTimeout() {
        // Doze mode: longest timeout to minimize wake-ups
        if (isInDozeMode) {
            return SOCKET_TIMEOUT_DOZE_MS;
        }

        // Active states (screen on or burst mode): short timeout for responsiveness
        long now = System.currentTimeMillis();
        boolean inBurstMode = (burstModeEndTime > 0 && now < burstModeEndTime);
        if (isScreenOn || inBurstMode) {
            return SOCKET_TIMEOUT_ACTIVE_MS;
        }

        // Screen off: longer timeout for battery save
        return SOCKET_TIMEOUT_SCREEN_OFF_MS;
    }

    /**
     * Check if device is currently on WiFi
     */
    private boolean isOnWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network activeNetwork = cm.getActiveNetwork();
                if (activeNetwork == null) return false;

                NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
                return capabilities != null &&
                       capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            } else {
                android.net.NetworkInfo networkInfo = cm.getActiveNetworkInfo();
                return networkInfo != null &&
                       networkInfo.isConnected() &&
                       networkInfo.getType() == ConnectivityManager.TYPE_WIFI;
            }
        } catch (Exception e) {
            Log.w(TAG, "KaZaService: Error checking WiFi status: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if device is currently on mobile data
     */
    private boolean isOnMobileData() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network activeNetwork = cm.getActiveNetwork();
                if (activeNetwork == null) return false;

                NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
                return capabilities != null &&
                       capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
            } else {
                android.net.NetworkInfo networkInfo = cm.getActiveNetworkInfo();
                return networkInfo != null &&
                       networkInfo.isConnected() &&
                       networkInfo.getType() == ConnectivityManager.TYPE_MOBILE;
            }
        } catch (Exception e) {
            Log.w(TAG, "KaZaService: Error checking mobile data status: " + e.getMessage());
            return false;
        }
    }

    /**
     * Update idle detection based on time since last activity
     */
    private void updateIdleState() {
        long idleTime = System.currentTimeMillis() - lastActivityTime;
        long IDLE_THRESHOLD_MS = 5 * 60 * 1000; // 5 minutes

        boolean wasIdle = isDeviceIdle;
        isDeviceIdle = (idleTime > IDLE_THRESHOLD_MS) && !isScreenOn;

        if (wasIdle != isDeviceIdle) {
            Log.d(TAG, "KaZaService: Idle state changed: " + wasIdle + " → " + isDeviceIdle);
        }
    }

    /**
     * Register screen state receiver to detect screen on/off
     */
    private void registerScreenStateReceiver() {
        try {
            screenStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent.getAction() == null) return;

                    if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                        Log.i(TAG, "KaZaService: Screen turned ON - switching to real-time mode");
                        isScreenOn = true;
                        lastActivityTime = System.currentTimeMillis(); // Reset idle timer
                        updateKeepAliveInterval();
                        // Start proactive location caching when screen is ON
                        startLocationCaching();
                    } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                        Log.i(TAG, "KaZaService: Screen turned OFF - switching to battery save mode");
                        isScreenOn = false;
                        updateKeepAliveInterval();
                        // Stop location caching to save battery when screen is OFF
                        stopLocationCaching();
                    }
                }
            };

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screenStateReceiver, filter);

            // Initialize current screen state
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                isScreenOn = powerManager.isInteractive();
            } else if (powerManager != null) {
                isScreenOn = powerManager.isScreenOn();
            }

            Log.i(TAG, "KaZaService: Screen state monitoring registered (current: " +
                  (isScreenOn ? "ON" : "OFF") + ")");

            // Start location caching if screen is currently ON
            if (isScreenOn) {
                startLocationCaching();
            }
        } catch (Exception e) {
            Log.e(TAG, "KaZaService: Failed to register screen state receiver: " + e.getMessage());
        }
    }

    /**
     * Unregister screen state receiver
     */
    private void unregisterScreenStateReceiver() {
        if (screenStateReceiver != null) {
            try {
                unregisterReceiver(screenStateReceiver);
                Log.i(TAG, "KaZaService: Screen state monitoring unregistered");
            } catch (Exception e) {
                Log.e(TAG, "KaZaService: Failed to unregister screen state receiver: " + e.getMessage());
            }
            screenStateReceiver = null;
        }
    }

    /**
     * Register battery state receiver to detect battery level and charging status
     */
    private void registerBatteryReceiver() {
        try {
            batteryReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;

                    int level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                    int scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                    int status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);

                    if (level >= 0 && scale > 0) {
                        int oldBatteryLevel = batteryLevel;
                        batteryLevel = (level * 100) / scale;

                        boolean wasCharging = isCharging;
                        isCharging = (status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                      status == android.os.BatteryManager.BATTERY_STATUS_FULL);

                        boolean wasLowBattery = isLowBattery;

                        // Update low battery status based on thresholds
                        if (!isCharging) {
                            if (batteryLevel < LOW_BATTERY_THRESHOLD) {
                                isLowBattery = true;
                            } else if (batteryLevel > BATTERY_RESTORE_THRESHOLD) {
                                isLowBattery = false;
                            }
                            // Hysteresis: if between 15-20%, keep previous state
                        } else {
                            // When charging, clear low battery flag
                            isLowBattery = false;
                        }

                        // Log significant changes
                        if (wasCharging != isCharging) {
                            Log.i(TAG, "KaZaService: Charging state changed: " + (isCharging ? "CHARGING" : "NOT CHARGING"));
                        }

                        if (wasLowBattery != isLowBattery) {
                            if (isLowBattery) {
                                Log.w(TAG, "KaZaService: ⚠ Low battery mode activated (" + batteryLevel + "%) - reducing network activity");
                            } else {
                                Log.i(TAG, "KaZaService: Low battery mode deactivated (" + batteryLevel + "%) - restoring normal intervals");
                            }
                            updateKeepAliveInterval();
                        }

                        // Log battery level changes every 10%
                        if (Math.abs(oldBatteryLevel - batteryLevel) >= 10) {
                            Log.i(TAG, "KaZaService: Battery level: " + batteryLevel + "%" +
                                  (isCharging ? " (charging)" : "") +
                                  (isLowBattery ? " [LOW BATTERY MODE]" : ""));
                        }
                    }
                }
            };

            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            registerReceiver(batteryReceiver, filter);

            Log.i(TAG, "KaZaService: Battery monitoring registered");
        } catch (Exception e) {
            Log.e(TAG, "KaZaService: Failed to register battery receiver: " + e.getMessage());
        }
    }

    /**
     * Unregister battery state receiver
     */
    private void unregisterBatteryReceiver() {
        if (batteryReceiver != null) {
            try {
                unregisterReceiver(batteryReceiver);
                Log.i(TAG, "KaZaService: Battery monitoring unregistered");
            } catch (Exception e) {
                Log.e(TAG, "KaZaService: Failed to unregister battery receiver: " + e.getMessage());
            }
            batteryReceiver = null;
        }
    }

    /**
     * Register Doze mode receiver to detect when device enters/exits Doze mode
     * Doze mode is Android 6.0+ (API 23+) deep sleep optimization
     */
    private void registerDozeReceiver() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.d(TAG, "KaZaService: Doze mode not available (API < 23)");
            return;
        }

        try {
            // Get PowerManager for Doze detection
            powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

            dozeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || intent.getAction() == null) return;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager != null) {
                        boolean wasInDozeMode = isInDozeMode;
                        isInDozeMode = powerManager.isDeviceIdleMode();

                        if (wasInDozeMode != isInDozeMode) {
                            if (isInDozeMode) {
                                Log.w(TAG, "KaZaService: ⏸ Entered Doze mode - switching to 15-minute intervals");
                                // Unregister network callback during Doze to reduce wake-ups
                                // Socket timeout will handle connection loss detection
                                unregisterNetworkReceiver();
                                Log.i(TAG, "KaZaService: Network monitoring suspended during Doze (socket timeout will handle disconnects)");
                            } else {
                                Log.i(TAG, "KaZaService: ▶ Exited Doze mode - restoring normal intervals");
                                // Re-register network callback when exiting Doze
                                registerNetworkReceiver();
                                Log.i(TAG, "KaZaService: Network monitoring resumed");
                            }
                            updateKeepAliveInterval();
                        }
                    }
                }
            };

            IntentFilter filter = new IntentFilter();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
            }
            registerReceiver(dozeReceiver, filter);

            // Initialize current Doze state
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager != null) {
                isInDozeMode = powerManager.isDeviceIdleMode();
                Log.i(TAG, "KaZaService: Doze mode monitoring registered (current: " +
                      (isInDozeMode ? "DOZE" : "ACTIVE") + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "KaZaService: Failed to register Doze receiver: " + e.getMessage());
        }
    }

    /**
     * Unregister Doze mode receiver
     */
    private void unregisterDozeReceiver() {
        if (dozeReceiver != null) {
            try {
                unregisterReceiver(dozeReceiver);
                Log.i(TAG, "KaZaService: Doze mode monitoring unregistered");
            } catch (Exception e) {
                Log.e(TAG, "KaZaService: Failed to unregister Doze receiver: " + e.getMessage());
            }
            dozeReceiver = null;
        }
    }

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

    /**
     * Start smart location caching - Proactively requests location updates when screen is ON
     * Caches fresh location every 5 minutes for battery-efficient position responses
     */
    private void startLocationCaching() {
        try {
            LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) {
                Log.w(TAG, "KaZaService: LocationManager not available for caching");
                return;
            }

            // Check permissions
            boolean hasLocationPermission = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;

            if (!hasLocationPermission) {
                Log.w(TAG, "KaZaService: No location permission - cannot start location caching");
                return;
            }

            // Check if GPS is enabled
            boolean gpsEnabled = false;
            try {
                gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            } catch (Exception e) {
                Log.w(TAG, "KaZaService: GPS provider check failed: " + e.getMessage());
            }

            if (!gpsEnabled) {
                Log.d(TAG, "KaZaService: GPS disabled - location caching not started");
                return;
            }

            // Stop existing caching if any
            stopLocationCaching();

            // Create location listener for caching
            cacheLocationListener = new android.location.LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    cachedLocation = location;
                    cachedLocationTime = System.currentTimeMillis();
                    Log.i(TAG, "KaZaService: Location cache updated (accuracy: " +
                          location.getAccuracy() + "m, age: 0s)");
                }

                @Override
                public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}

                @Override
                public void onProviderEnabled(String provider) {
                    Log.i(TAG, "KaZaService: GPS enabled - location caching active");
                }

                @Override
                public void onProviderDisabled(String provider) {
                    Log.w(TAG, "KaZaService: GPS disabled - location cache may become stale");
                }
            };

            // Request location updates every 5 minutes
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_CACHE_INTERVAL_MS,
                0, // No distance filter - update based on time only
                cacheLocationListener,
                connectionThread.getLooper()
            );

            Log.i(TAG, "KaZaService: Smart location caching started (updates every " +
                  (LOCATION_CACHE_INTERVAL_MS / 1000 / 60) + " minutes)");

        } catch (SecurityException e) {
            Log.e(TAG, "KaZaService: No permission for location caching: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "KaZaService: Failed to start location caching: " + e.getMessage());
        }
    }

    /**
     * Stop smart location caching when screen turns OFF
     */
    private void stopLocationCaching() {
        if (cacheLocationListener != null) {
            try {
                LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                if (locationManager != null) {
                    locationManager.removeUpdates(cacheLocationListener);
                    Log.i(TAG, "KaZaService: Smart location caching stopped");
                }
            } catch (Exception e) {
                Log.e(TAG, "KaZaService: Failed to stop location caching: " + e.getMessage());
            }
            cacheLocationListener = null;
        }
    }

    /**
     * Start automatic position tracking
     * Sends position updates to server when location changes by ≥30m or ≥5 minutes elapsed
     * Battery-efficient: only wakes service when criteria are met
     */
    private void startAutomaticPositionTracking() {
        try {
            LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) {
                Log.w(TAG, "KaZaService: LocationManager not available");
                return;
            }

            // Check if GPS is enabled
            boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            if (!gpsEnabled) {
                Log.w(TAG, "KaZaService: GPS disabled - automatic position tracking not started");
                // Try to start with network provider as fallback
                boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
                if (!networkEnabled) {
                    Log.w(TAG, "KaZaService: No location providers available");
                    return;
                }
            }

            // Stop existing tracking if any
            stopAutomaticPositionTracking();

            // Create location listener that sends position updates to server
            autoPositionListener = new android.location.LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    // Format position string
                    String position = String.format("%.6f:%.6f:%.2f:%.1f:%s",
                        location.getLatitude(),
                        location.getLongitude(),
                        location.getAltitude(),
                        location.getAccuracy(),
                        location.getProvider());

                    Log.i(TAG, "KaZaService: Auto position update triggered (moved ≥" +
                          AUTO_POSITION_MIN_DISTANCE_M + "m or ≥" +
                          (AUTO_POSITION_MIN_TIME_MS / 1000 / 60) + "min)");
                    Log.i(TAG, "KaZaService: Position: " + position);

                    // Send position to server
                    if (protocol != null && isConnected) {
                        try {
                            protocol.sendCommand("POSITION:" + position);
                            Log.i(TAG, "KaZaService: Auto position sent to server");
                        } catch (Exception e) {
                            Log.e(TAG, "KaZaService: Failed to send auto position: " + e.getMessage());
                        }
                    } else {
                        Log.w(TAG, "KaZaService: Not connected - cannot send position");
                    }

                    // Also update cache
                    cachedLocation = location;
                    cachedLocationTime = System.currentTimeMillis();
                }

                @Override
                public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}

                @Override
                public void onProviderEnabled(String provider) {
                    Log.i(TAG, "KaZaService: Location provider enabled: " + provider);
                }

                @Override
                public void onProviderDisabled(String provider) {
                    Log.w(TAG, "KaZaService: Location provider disabled: " + provider);
                }
            };

            // Request location updates with 30m distance and 5min time criteria
            // System will only wake the service when EITHER criterion is met
            String provider = gpsEnabled ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
            locationManager.requestLocationUpdates(
                provider,
                AUTO_POSITION_MIN_TIME_MS,  // Min time: 5 minutes
                AUTO_POSITION_MIN_DISTANCE_M, // Min distance: 30 meters
                autoPositionListener,
                connectionThread.getLooper()
            );

            Log.i(TAG, "KaZaService: Automatic position tracking started");
            Log.i(TAG, "KaZaService: - Provider: " + provider);
            Log.i(TAG, "KaZaService: - Min distance: " + AUTO_POSITION_MIN_DISTANCE_M + "m");
            Log.i(TAG, "KaZaService: - Min time: " + (AUTO_POSITION_MIN_TIME_MS / 1000 / 60) + " minutes");
            Log.i(TAG, "KaZaService: Position updates will be sent automatically when criteria are met");

        } catch (SecurityException e) {
            Log.e(TAG, "KaZaService: No permission for automatic position tracking: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "KaZaService: Failed to start automatic position tracking: " + e.getMessage());
        }
    }

    /**
     * Stop automatic position tracking
     */
    private void stopAutomaticPositionTracking() {
        if (autoPositionListener != null) {
            try {
                LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                if (locationManager != null) {
                    locationManager.removeUpdates(autoPositionListener);
                    Log.i(TAG, "KaZaService: Automatic position tracking stopped");
                }
            } catch (Exception e) {
                Log.e(TAG, "KaZaService: Failed to stop automatic position tracking: " + e.getMessage());
            }
            autoPositionListener = null;
        }
    }
}
