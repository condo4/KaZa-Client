package org.kaza;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

/**
 * GPS/Location tracking manager for KaZa service
 * Handles all location-related functionality including:
 * - Smart location caching (proactive updates when screen ON)
 * - Active GPS fixes (burst mode for accurate tracking)
 * - Automatic position tracking (sends updates when location changes)
 */
public class KaZaTracker {
    private static final String TAG = "KaZaTracker";

    // Active location request settings for burst mode
    private static final int LOCATION_REQUEST_TIMEOUT_MS = 30000; // 30s max wait for GPS fix

    // Smart location caching - Proactive location updates when screen ON
    private static final long LOCATION_CACHE_INTERVAL_MS = 5 * 60 * 1000; // Update cache every 5 min
    private static final long LOCATION_CACHE_MAX_AGE_MS = 10 * 60 * 1000; // Cache valid for 10 min

    // Automatic position tracking - Send position to server when location changes significantly
    private static final long AUTO_POSITION_MIN_TIME_MS = 5 * 60 * 1000; // 5 minutes minimum interval
    private static final float AUTO_POSITION_MIN_DISTANCE_M = 30.0f; // 30 meters minimum distance

    private final Context context;
    private final Looper looper;

    // Active location request for burst mode
    private android.location.LocationListener activeLocationListener = null;
    private final Object locationLock = new Object();
    private Location pendingLocation = null;

    // Smart location caching
    private android.location.LocationListener cacheLocationListener = null;
    private Location cachedLocation = null;
    private long cachedLocationTime = 0;

    // Automatic position tracking
    private android.location.LocationListener autoPositionListener = null;
    private long lastPositionRequestTime = 0;

    // Protocol callback interface
    private ProtocolCallback protocolCallback = null;

    /**
     * Callback interface for sending data to protocol
     */
    public interface ProtocolCallback {
        void sendCommand(String command) throws Exception;
        boolean isConnected();
    }

    /**
     * Constructor
     * @param context Android context for accessing system services
     * @param looper Looper for location callbacks (should be from background thread)
     */
    public KaZaTracker(Context context, Looper looper) {
        this.context = context;
        this.looper = looper;
    }

    /**
     * Set protocol callback for sending position updates
     */
    public void setProtocolCallback(ProtocolCallback callback) {
        this.protocolCallback = callback;
    }

    /**
     * Get current GPS position
     * Strategy: Use active GPS fix during burst mode + screen off for accuracy,
     *           otherwise use cached location for battery efficiency
     * @param burstModeEndTime Timestamp when burst mode ends (0 if not in burst mode)
     * @param isScreenOn Whether the screen is currently on
     * @return Position string in format "lat:lon:alt:accuracy:provider" or error message
     */
    public String getGPSPosition(long burstModeEndTime, boolean isScreenOn) {
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
                    Log.i(TAG, "KaZaTracker: Position from cache (age: " + (cacheAge / 1000) + "s): " + positionString);
                    return positionString;
                } else {
                    Log.d(TAG, "KaZaTracker: Cached location too old (" + (cacheAge / 1000) + "s) - requesting fresh location");
                }
            }

            // Check permissions first
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ requires background location permission for background access
                boolean hasBackgroundLocation = context.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
                boolean hasFineLocation = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;

                Log.i(TAG, "KaZaTracker: Fine location permission: " + hasFineLocation);
                Log.i(TAG, "KaZaTracker: Background location permission: " + hasBackgroundLocation);

                if (!hasFineLocation) {
                    return "ERROR:No location permission";
                }
                if (!hasBackgroundLocation) {
                    Log.w(TAG, "KaZaTracker: ⚠ No background location permission - location only available while app is open");
                    Log.w(TAG, "KaZaTracker: User needs to grant 'Allow all the time' in app settings");
                    // Try anyway - might work if app is in foreground
                }
            }

            LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);

            if (locationManager == null) {
                Log.w(TAG, "KaZaTracker: LocationManager not available");
                return "ERROR:LocationManager not available";
            }

            // Check if location services are enabled
            boolean gpsEnabled = false;
            boolean networkEnabled = false;

            try {
                gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            } catch (Exception e) {
                Log.w(TAG, "KaZaTracker: GPS provider check failed: " + e.getMessage());
            }

            try {
                networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            } catch (Exception e) {
                Log.w(TAG, "KaZaTracker: Network provider check failed: " + e.getMessage());
            }

            if (!gpsEnabled && !networkEnabled) {
                Log.w(TAG, "KaZaTracker: No location providers enabled");
                return "ERROR:Location services disabled";
            }

            Log.i(TAG, "KaZaTracker: GPS enabled: " + gpsEnabled + ", Network enabled: " + networkEnabled);

            // Determine if we should request active GPS fix (reuse variables from cache check above)
            boolean shouldRequestActiveFix = needActiveFix && gpsEnabled;

            // Request active GPS fix during burst mode when screen is off (accurate tracking needed)
            if (shouldRequestActiveFix) {
                Log.i(TAG, "KaZaTracker: Burst mode + screen off - requesting active GPS fix for accuracy");
                Location freshLocation = requestSingleLocationUpdate(locationManager);
                if (freshLocation != null) {
                    String positionString = String.format("%.6f:%.6f:%.2f:%.1f:%s",
                        freshLocation.getLatitude(),
                        freshLocation.getLongitude(),
                        freshLocation.getAltitude(),
                        freshLocation.getAccuracy(),
                        freshLocation.getProvider());
                    Log.i(TAG, "KaZaTracker: Fresh position (active GPS): " + positionString);
                    return positionString;
                }
                Log.w(TAG, "KaZaTracker: Active GPS fix timeout - falling back to cached location");
            }

            // Fallback: Use cached location (battery efficient for screen on or normal mode)
            Location bestLocation = null;

            // Try GPS provider first (most accurate)
            if (gpsEnabled) {
                try {
                    Location gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (gpsLocation != null) {
                        bestLocation = gpsLocation;
                        Log.i(TAG, "KaZaTracker: Got GPS location (age: " +
                            (System.currentTimeMillis() - gpsLocation.getTime()) / 1000 + "s)");
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "KaZaTracker: No permission for GPS location: " + e.getMessage());
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
                            Log.i(TAG, "KaZaTracker: Got Network location (age: " +
                                (System.currentTimeMillis() - networkLocation.getTime()) / 1000 + "s)");
                        }
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "KaZaTracker: No permission for Network location: " + e.getMessage());
                    if (bestLocation == null) {
                        return "ERROR:No location permission";
                    }
                }
            }

            if (bestLocation == null) {
                Log.w(TAG, "KaZaTracker: No last known location available");
                return "ERROR:No location available";
            }

            // Format: "lat:lon:alt:accuracy:provider"
            String positionString = String.format("%.6f:%.6f:%.2f:%.1f:%s",
                bestLocation.getLatitude(),
                bestLocation.getLongitude(),
                bestLocation.getAltitude(),
                bestLocation.getAccuracy(),
                bestLocation.getProvider());

            Log.i(TAG, "KaZaTracker: Position (cached): " + positionString);
            return positionString;

        } catch (Exception e) {
            Log.e(TAG, "KaZaTracker: Error getting GPS position: " + e.getMessage(), e);
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
                                Log.i(TAG, "KaZaTracker: Received fresh GPS fix (accuracy: " +
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
                    looper
                );

                Log.i(TAG, "KaZaTracker: Waiting for GPS fix (max " +
                      (LOCATION_REQUEST_TIMEOUT_MS / 1000) + "s)...");

                // Wait for location update with timeout
                locationLock.wait(LOCATION_REQUEST_TIMEOUT_MS);

                // Clean up listener
                locationManager.removeUpdates(activeLocationListener);
                activeLocationListener = null;

                return pendingLocation;

            } catch (SecurityException e) {
                Log.e(TAG, "KaZaTracker: No permission for active location request: " + e.getMessage());
                return null;
            } catch (InterruptedException e) {
                Log.w(TAG, "KaZaTracker: Location request interrupted: " + e.getMessage());
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
                Log.e(TAG, "KaZaTracker: Error requesting location update: " + e.getMessage());
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
     * Start smart location caching - Proactively requests location updates when screen is ON
     * Caches fresh location every 5 minutes for battery-efficient position responses
     */
    public void startLocationCaching() {
        try {
            LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) {
                Log.w(TAG, "KaZaTracker: LocationManager not available for caching");
                return;
            }

            // Check permissions
            boolean hasLocationPermission = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;

            if (!hasLocationPermission) {
                Log.w(TAG, "KaZaTracker: No location permission - cannot start location caching");
                return;
            }

            // Check if GPS is enabled
            boolean gpsEnabled = false;
            try {
                gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            } catch (Exception e) {
                Log.w(TAG, "KaZaTracker: GPS provider check failed: " + e.getMessage());
            }

            if (!gpsEnabled) {
                Log.d(TAG, "KaZaTracker: GPS disabled - location caching not started");
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
                    Log.i(TAG, "KaZaTracker: Location cache updated (accuracy: " +
                          location.getAccuracy() + "m, age: 0s)");
                }

                @Override
                public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}

                @Override
                public void onProviderEnabled(String provider) {
                    Log.i(TAG, "KaZaTracker: GPS enabled - location caching active");
                }

                @Override
                public void onProviderDisabled(String provider) {
                    Log.w(TAG, "KaZaTracker: GPS disabled - location cache may become stale");
                }
            };

            // Request location updates every 5 minutes
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_CACHE_INTERVAL_MS,
                0, // No distance filter - update based on time only
                cacheLocationListener,
                looper
            );

            Log.i(TAG, "KaZaTracker: Smart location caching started (updates every " +
                  (LOCATION_CACHE_INTERVAL_MS / 1000 / 60) + " minutes)");

        } catch (SecurityException e) {
            Log.e(TAG, "KaZaTracker: No permission for location caching: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "KaZaTracker: Failed to start location caching: " + e.getMessage());
        }
    }

    /**
     * Stop smart location caching when screen turns OFF
     */
    public void stopLocationCaching() {
        if (cacheLocationListener != null) {
            try {
                LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
                if (locationManager != null) {
                    locationManager.removeUpdates(cacheLocationListener);
                    Log.i(TAG, "KaZaTracker: Smart location caching stopped");
                }
            } catch (Exception e) {
                Log.e(TAG, "KaZaTracker: Failed to stop location caching: " + e.getMessage());
            }
            cacheLocationListener = null;
        }
    }

    /**
     * Start automatic position tracking
     * Sends position updates to server when location changes by ≥30m or ≥5 minutes elapsed
     * Battery-efficient: only wakes service when criteria are met
     */
    public void startAutomaticPositionTracking() {
        try {
            LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) {
                Log.w(TAG, "KaZaTracker: LocationManager not available");
                return;
            }

            // Check if GPS is enabled
            boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            if (!gpsEnabled) {
                Log.w(TAG, "KaZaTracker: GPS disabled - automatic position tracking not started");
                // Try to start with network provider as fallback
                boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
                if (!networkEnabled) {
                    Log.w(TAG, "KaZaTracker: No location providers available");
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

                    Log.i(TAG, "KaZaTracker: Auto position update triggered (moved ≥" +
                          AUTO_POSITION_MIN_DISTANCE_M + "m or ≥" +
                          (AUTO_POSITION_MIN_TIME_MS / 1000 / 60) + "min)");
                    Log.i(TAG, "KaZaTracker: Position: " + position);

                    // Send position to server
                    if (protocolCallback != null && protocolCallback.isConnected()) {
                        try {
                            protocolCallback.sendCommand("POSITION:" + position);
                            Log.i(TAG, "KaZaTracker: Auto position sent to server");
                        } catch (Exception e) {
                            Log.e(TAG, "KaZaTracker: Failed to send auto position: " + e.getMessage());
                        }
                    } else {
                        Log.w(TAG, "KaZaTracker: Not connected - cannot send position");
                    }

                    // Also update cache
                    cachedLocation = location;
                    cachedLocationTime = System.currentTimeMillis();
                }

                @Override
                public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}

                @Override
                public void onProviderEnabled(String provider) {
                    Log.i(TAG, "KaZaTracker: Location provider enabled: " + provider);
                }

                @Override
                public void onProviderDisabled(String provider) {
                    Log.w(TAG, "KaZaTracker: Location provider disabled: " + provider);
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
                looper
            );

            Log.i(TAG, "KaZaTracker: Automatic position tracking started");
            Log.i(TAG, "KaZaTracker: - Provider: " + provider);
            Log.i(TAG, "KaZaTracker: - Min distance: " + AUTO_POSITION_MIN_DISTANCE_M + "m");
            Log.i(TAG, "KaZaTracker: - Min time: " + (AUTO_POSITION_MIN_TIME_MS / 1000 / 60) + " minutes");
            Log.i(TAG, "KaZaTracker: Position updates will be sent automatically when criteria are met");

        } catch (SecurityException e) {
            Log.e(TAG, "KaZaTracker: No permission for automatic position tracking: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "KaZaTracker: Failed to start automatic position tracking: " + e.getMessage());
        }
    }

    /**
     * Stop automatic position tracking
     */
    public void stopAutomaticPositionTracking() {
        if (autoPositionListener != null) {
            try {
                LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
                if (locationManager != null) {
                    locationManager.removeUpdates(autoPositionListener);
                    Log.i(TAG, "KaZaTracker: Automatic position tracking stopped");
                }
            } catch (Exception e) {
                Log.e(TAG, "KaZaTracker: Failed to stop automatic position tracking: " + e.getMessage());
            }
            autoPositionListener = null;
        }
    }

    /**
     * Update the last position request time (for burst mode tracking)
     */
    public void updateLastPositionRequestTime() {
        this.lastPositionRequestTime = System.currentTimeMillis();
    }

    /**
     * Get the last position request time
     */
    public long getLastPositionRequestTime() {
        return lastPositionRequestTime;
    }
}
