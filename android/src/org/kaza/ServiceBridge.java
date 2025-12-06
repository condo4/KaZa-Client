package org.kaza;

import android.util.Log;

/**
 * Bridge class for communication between Qt/C++ and NotificationService
 * This class provides static methods that can be called from Qt via JNI
 */
public class ServiceBridge {
    private static final String TAG = "ServiceBridge";

    /**
     * Query the NotificationService
     * Called from Qt C++ code via JNI
     *
     * @param query Query string (e.g., "status", "ping", "info")
     * @return Response string from the service
     */
    public static String queryService(String query) {
        Log.i(TAG, "ServiceBridge: Bridge query: " + query);

        NotificationService service = NotificationService.getInstance();

        if (service == null) {
            Log.w(TAG, "ServiceBridge: Service not running");
            return "ERROR: Service not running";
        }

        try {
            String response = service.query(query);
            Log.i(TAG, "ServiceBridge: Bridge response: " + response);
            return response;
        } catch (Exception e) {
            Log.e(TAG, "ServiceBridge: Bridge error: " + e.getMessage(), e);
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Check if the NotificationService is running
     *
     * @return true if service is running, false otherwise
     */
    public static boolean isServiceRunning() {
        boolean running = NotificationService.isServiceRunning();
        Log.i(TAG, "ServiceBridge: Service running check: " + running);
        return running;
    }

    /**
     * Send a notification via the service
     *
     * @param title Notification title
     * @param message Notification message
     * @return "OK" if successful, error message otherwise
     */
    public static String sendNotification(String title, String message) {
        Log.i(TAG, "ServiceBridge: Bridge notification: " + title);

        NotificationService service = NotificationService.getInstance();

        if (service == null) {
            Log.w(TAG, "ServiceBridge: Service not running");
            return "ERROR: Service not running";
        }

        try {
            service.notify(title, message);
            return "OK";
        } catch (Exception e) {
            Log.e(TAG, "ServiceBridge: Bridge notification error: " + e.getMessage(), e);
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Configure the NotificationService with SSL parameters
     *
     * @param clientCert Path to client certificate
     * @param caCert Path to CA certificate
     * @param clientKey Path to client key
     * @param clientPass Client key password
     * @param host Server host
     * @param port Server port
     * @param username Username
     * @return "OK" if successful, error message otherwise
     */
    public static String configureService(String clientCert, String caCert, String clientKey,
                                          String clientPass, String host, int port, String username) {
        Log.i(TAG, "ServiceBridge: Configuring service");
        Log.i(TAG, "ServiceBridge: Host: " + host + ":" + port);

        NotificationService service = NotificationService.getInstance();

        if (service == null) {
            Log.w(TAG, "ServiceBridge: Service not running");
            return "ERROR: Service not running";
        }

        try {
            String result = service.configure(clientCert, caCert, clientKey, clientPass, host, port, username);
            Log.i(TAG, "ServiceBridge: Configuration result: " + result);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "ServiceBridge: Configuration error: " + e.getMessage(), e);
            return "ERROR: " + e.getMessage();
        }
    }
}
