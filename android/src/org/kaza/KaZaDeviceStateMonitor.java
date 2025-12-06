package org.kaza;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

/**
 * Monitors device state for adaptive behavior
 * Tracks: battery, network, screen, and Doze mode
 */
public class KaZaDeviceStateMonitor {
    private static final String TAG = "KaZaDeviceStateMonitor";

    // Battery thresholds
    private static final int LOW_BATTERY_THRESHOLD = 15;
    private static final int BATTERY_RESTORE_THRESHOLD = 20;

    private final Context context;
    private StateChangeCallback callback;

    // Battery state
    private BroadcastReceiver batteryReceiver = null;
    private int batteryLevel = 100;
    private boolean isCharging = false;
    private boolean isLowBattery = false;

    // Network state
    private ConnectivityManager.NetworkCallback networkCallback = null;

    // Screen state
    private BroadcastReceiver screenStateReceiver = null;
    private boolean isScreenOn = true;
    private long lastActivityTime = System.currentTimeMillis();

    // Doze mode state
    private BroadcastReceiver dozeReceiver = null;
    private PowerManager powerManager = null;
    private boolean isInDozeMode = false;

    // Device idle state
    private boolean isDeviceIdle = false;
    private static final long IDLE_THRESHOLD_MS = 5 * 60 * 1000; // 5 minutes

    /**
     * Callback interface for state changes
     */
    public interface StateChangeCallback {
        void onScreenStateChanged(boolean isScreenOn);
        void onBatteryStateChanged(int level, boolean isCharging, boolean isLowBattery);
        void onNetworkAvailable();
        void onNetworkLost();
        void onDozeStateChanged(boolean isInDozeMode);
        Context getContext();
    }

    public KaZaDeviceStateMonitor(Context context) {
        this.context = context;
    }

    /**
     * Set callback for state changes
     */
    public void setCallback(StateChangeCallback callback) {
        this.callback = callback;
    }

    /**
     * Start monitoring all device states
     */
    public void startMonitoring() {
        registerBatteryReceiver();
        registerNetworkReceiver();
        registerScreenStateReceiver();
        registerDozeReceiver();
    }

    /**
     * Stop monitoring all device states
     */
    public void stopMonitoring() {
        unregisterBatteryReceiver();
        unregisterNetworkReceiver();
        unregisterScreenStateReceiver();
        unregisterDozeReceiver();
    }

    // ==================== Battery Monitoring ====================

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
                            Log.i(TAG, "KaZaDeviceStateMonitor: Charging state changed: " + (isCharging ? "CHARGING" : "NOT CHARGING"));
                        }

                        if (wasLowBattery != isLowBattery) {
                            if (isLowBattery) {
                                Log.w(TAG, "KaZaDeviceStateMonitor: ⚠ Low battery mode activated (" + batteryLevel + "%) - reducing network activity");
                            } else {
                                Log.i(TAG, "KaZaDeviceStateMonitor: Low battery mode deactivated (" + batteryLevel + "%) - restoring normal intervals");
                            }
                        }

                        // Log battery level changes every 10%
                        if (Math.abs(oldBatteryLevel - batteryLevel) >= 10) {
                            Log.i(TAG, "KaZaDeviceStateMonitor: Battery level: " + batteryLevel + "%" +
                                  (isCharging ? " (charging)" : "") +
                                  (isLowBattery ? " [LOW BATTERY MODE]" : ""));
                        }

                        // Notify callback if state changed
                        if (callback != null && (wasCharging != isCharging || wasLowBattery != isLowBattery)) {
                            callback.onBatteryStateChanged(batteryLevel, isCharging, isLowBattery);
                        }
                    }
                }
            };

            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            context.registerReceiver(batteryReceiver, filter);

            Log.i(TAG, "KaZaDeviceStateMonitor: Battery monitoring registered");
        } catch (Exception e) {
            Log.e(TAG, "KaZaDeviceStateMonitor: Failed to register battery receiver: " + e.getMessage());
        }
    }

    private void unregisterBatteryReceiver() {
        if (batteryReceiver != null) {
            try {
                context.unregisterReceiver(batteryReceiver);
                Log.i(TAG, "KaZaDeviceStateMonitor: Battery monitoring unregistered");
            } catch (Exception e) {
                Log.e(TAG, "KaZaDeviceStateMonitor: Failed to unregister battery receiver: " + e.getMessage());
            }
            batteryReceiver = null;
        }
    }

    // ==================== Network Monitoring ====================

    public void registerNetworkReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                ConnectivityManager connectivityManager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

                if (connectivityManager == null) {
                    Log.w(TAG, "KaZaDeviceStateMonitor: ConnectivityManager not available");
                    return;
                }

                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        Log.i(TAG, "KaZaDeviceStateMonitor: Network available - " + network);
                        if (callback != null) {
                            callback.onNetworkAvailable();
                        }
                    }

                    @Override
                    public void onLost(Network network) {
                        Log.w(TAG, "KaZaDeviceStateMonitor: Network lost - " + network);
                        if (callback != null) {
                            callback.onNetworkLost();
                        }
                    }

                    @Override
                    public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                        boolean hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                        boolean validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                        Log.d(TAG, "KaZaDeviceStateMonitor: Network capabilities changed - Internet: " + hasInternet + ", Validated: " + validated);
                    }
                };

                // Register callback for any network
                NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();

                connectivityManager.registerNetworkCallback(request, networkCallback);
                Log.i(TAG, "KaZaDeviceStateMonitor: Network connectivity monitoring registered");

            } catch (Exception e) {
                Log.e(TAG, "KaZaDeviceStateMonitor: Failed to register network callback: " + e.getMessage());
            }
        } else {
            Log.i(TAG, "KaZaDeviceStateMonitor: Network monitoring requires Android N+ (API 24+)");
        }
    }

    public void unregisterNetworkReceiver() {
        if (networkCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                ConnectivityManager connectivityManager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

                if (connectivityManager != null) {
                    connectivityManager.unregisterNetworkCallback(networkCallback);
                    Log.i(TAG, "KaZaDeviceStateMonitor: Network connectivity monitoring unregistered");
                }
            } catch (Exception e) {
                Log.e(TAG, "KaZaDeviceStateMonitor: Failed to unregister network callback: " + e.getMessage());
            }
            networkCallback = null;
        }
    }

    // ==================== Screen State Monitoring ====================

    private void registerScreenStateReceiver() {
        try {
            screenStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent.getAction() == null) return;

                    if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                        Log.i(TAG, "KaZaDeviceStateMonitor: Screen turned ON - switching to real-time mode");
                        isScreenOn = true;
                        lastActivityTime = System.currentTimeMillis(); // Reset idle timer

                        if (callback != null) {
                            callback.onScreenStateChanged(true);
                        }
                    } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                        Log.i(TAG, "KaZaDeviceStateMonitor: Screen turned OFF - switching to battery save mode");
                        isScreenOn = false;

                        if (callback != null) {
                            callback.onScreenStateChanged(false);
                        }
                    }
                }
            };

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            context.registerReceiver(screenStateReceiver, filter);

            // Initialize current screen state
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                isScreenOn = powerManager.isInteractive();
            } else if (powerManager != null) {
                isScreenOn = powerManager.isScreenOn();
            }

            Log.i(TAG, "KaZaDeviceStateMonitor: Screen state monitoring registered (current: " +
                  (isScreenOn ? "ON" : "OFF") + ")");

        } catch (Exception e) {
            Log.e(TAG, "KaZaDeviceStateMonitor: Failed to register screen state receiver: " + e.getMessage());
        }
    }

    private void unregisterScreenStateReceiver() {
        if (screenStateReceiver != null) {
            try {
                context.unregisterReceiver(screenStateReceiver);
                Log.i(TAG, "KaZaDeviceStateMonitor: Screen state monitoring unregistered");
            } catch (Exception e) {
                Log.e(TAG, "KaZaDeviceStateMonitor: Failed to unregister screen state receiver: " + e.getMessage());
            }
            screenStateReceiver = null;
        }
    }

    // ==================== Doze Mode Monitoring ====================

    private void registerDozeReceiver() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.d(TAG, "KaZaDeviceStateMonitor: Doze mode not available (API < 23)");
            return;
        }

        try {
            // Get PowerManager for Doze detection
            powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

            dozeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || intent.getAction() == null) return;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager != null) {
                        boolean wasInDozeMode = isInDozeMode;
                        isInDozeMode = powerManager.isDeviceIdleMode();

                        if (wasInDozeMode != isInDozeMode) {
                            if (isInDozeMode) {
                                Log.w(TAG, "KaZaDeviceStateMonitor: ⏸ Entered Doze mode - switching to 15-minute intervals");
                            } else {
                                Log.i(TAG, "KaZaDeviceStateMonitor: ▶ Exited Doze mode - restoring normal intervals");
                            }

                            if (callback != null) {
                                callback.onDozeStateChanged(isInDozeMode);
                            }
                        }
                    }
                }
            };

            IntentFilter filter = new IntentFilter();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
            }
            context.registerReceiver(dozeReceiver, filter);

            // Initialize current Doze state
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager != null) {
                isInDozeMode = powerManager.isDeviceIdleMode();
                Log.i(TAG, "KaZaDeviceStateMonitor: Doze mode monitoring registered (current: " +
                      (isInDozeMode ? "DOZE" : "ACTIVE") + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "KaZaDeviceStateMonitor: Failed to register Doze receiver: " + e.getMessage());
        }
    }

    private void unregisterDozeReceiver() {
        if (dozeReceiver != null) {
            try {
                context.unregisterReceiver(dozeReceiver);
                Log.i(TAG, "KaZaDeviceStateMonitor: Doze mode monitoring unregistered");
            } catch (Exception e) {
                Log.e(TAG, "KaZaDeviceStateMonitor: Failed to unregister Doze receiver: " + e.getMessage());
            }
            dozeReceiver = null;
        }
    }

    // ==================== Idle Detection ====================

    /**
     * Update idle detection state
     */
    public void updateIdleState() {
        long now = System.currentTimeMillis();
        long timeSinceActivity = now - lastActivityTime;
        boolean wasIdle = isDeviceIdle;

        isDeviceIdle = (timeSinceActivity > IDLE_THRESHOLD_MS) && !isScreenOn;

        if (wasIdle != isDeviceIdle) {
            if (isDeviceIdle) {
                Log.d(TAG, "KaZaDeviceStateMonitor: Device now idle (no activity for " + (timeSinceActivity / 1000) + "s)");
            } else {
                Log.d(TAG, "KaZaDeviceStateMonitor: Device now active");
            }
        }
    }

    /**
     * Reset idle timer (call when user activity detected)
     */
    public void resetIdleTimer() {
        lastActivityTime = System.currentTimeMillis();
        isDeviceIdle = false;
    }

    // ==================== Network Type Detection ====================

    /**
     * Check if device is on WiFi
     */
    public boolean isOnWifi() {
        try {
            ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

            if (connectivityManager == null) {
                return false;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = connectivityManager.getActiveNetwork();
                if (network == null) return false;

                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                if (capabilities == null) return false;

                return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            } else {
                android.net.NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
                return networkInfo != null && networkInfo.getType() == ConnectivityManager.TYPE_WIFI;
            }
        } catch (Exception e) {
            Log.e(TAG, "KaZaDeviceStateMonitor: Error checking WiFi status: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if device is on mobile data
     */
    public boolean isOnMobileData() {
        try {
            ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

            if (connectivityManager == null) {
                return false;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = connectivityManager.getActiveNetwork();
                if (network == null) return false;

                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                if (capabilities == null) return false;

                return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
            } else {
                android.net.NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
                return networkInfo != null && networkInfo.getType() == ConnectivityManager.TYPE_MOBILE;
            }
        } catch (Exception e) {
            Log.e(TAG, "KaZaDeviceStateMonitor: Error checking mobile data status: " + e.getMessage());
            return false;
        }
    }

    // ==================== Getters ====================

    public int getBatteryLevel() {
        return batteryLevel;
    }

    public boolean isCharging() {
        return isCharging;
    }

    public boolean isLowBattery() {
        return isLowBattery;
    }

    public boolean isScreenOn() {
        return isScreenOn;
    }

    public boolean isDeviceIdle() {
        return isDeviceIdle;
    }

    public boolean isInDozeMode() {
        return isInDozeMode;
    }

    public long getLastActivityTime() {
        return lastActivityTime;
    }
}
