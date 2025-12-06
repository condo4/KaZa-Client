#ifndef KAZASERVICEBRIDGE_H
#define KAZASERVICEBRIDGE_H

#include <QObject>
#include <QString>

/**
 * Bridge class for communication between Qt/C++ and Android NotificationService
 * Provides methods to query and interact with the background service
 */
class KazaServiceBridge : public QObject
{
    Q_OBJECT

public:
    explicit KazaServiceBridge(QObject *parent = nullptr);

    /**
     * Query the NotificationService with a command string
     * @param query Command string (e.g., "status", "ping", "info", "pid")
     * @return Response string from the service
     *
     * Available commands:
     * - "status"  -> Returns "running" or "stopped"
     * - "ping"    -> Returns "pong" if service is alive
     * - "info"    -> Returns service information
     * - "pid"     -> Returns process ID of the service
     */
    Q_INVOKABLE QString queryNotificationService(const QString &query);

    /**
     * Check if the NotificationService is currently running
     * @return true if service is running, false otherwise
     */
    Q_INVOKABLE bool isServiceRunning();

    /**
     * Send a notification via the NotificationService
     * @param title Notification title
     * @param message Notification message
     * @return true if successful, false otherwise
     */
    Q_INVOKABLE bool sendNotification(const QString &title, const QString &message);

    /**
     * Configure the NotificationService with SSL parameters
     * @param clientCert Path to client certificate
     * @param caCert Path to CA certificate
     * @param clientKey Path to client key
     * @param clientPass Client key password
     * @param host Server host
     * @param port Server port
     * @param username Username
     * @return true if successful, false otherwise
     */
    Q_INVOKABLE bool configureService(const QString &clientCert, const QString &caCert,
                                      const QString &clientKey, const QString &clientPass,
                                      const QString &host, int port, const QString &username);

signals:
    void serviceQueryCompleted(const QString &response);
    void serviceError(const QString &error);
    void serviceConfigured(bool success);
};

#endif // KAZASERVICEBRIDGE_H
