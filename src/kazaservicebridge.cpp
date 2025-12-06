#include "kazaservicebridge.h"
#include <QDebug>

#ifdef Q_OS_ANDROID
#include <QJniObject>
#include <QJniEnvironment>
#endif

KazaServiceBridge::KazaServiceBridge(QObject *parent)
    : QObject{parent}
{
    qDebug() << "KazaServiceBridge created";
}

QString KazaServiceBridge::queryNotificationService(const QString &query)
{
#ifdef Q_OS_ANDROID
    qDebug() << "KazaServiceBridge: Querying service with:" << query;

    QJniEnvironment env;

    // Call ServiceBridge.queryService(String) static method
    QJniObject jQuery = QJniObject::fromString(query);
    QJniObject result = QJniObject::callStaticObjectMethod(
        "org/kaza/ServiceBridge",
        "queryService",
        "(Ljava/lang/String;)Ljava/lang/String;",
        jQuery.object<jstring>()
    );

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        QString error = "JNI Exception occurred";
        qWarning() << "KazaServiceBridge:" << error;
        emit serviceError(error);
        return "ERROR: " + error;
    }

    if (!result.isValid()) {
        QString error = "Invalid response from service";
        qWarning() << "KazaServiceBridge:" << error;
        emit serviceError(error);
        return "ERROR: " + error;
    }

    QString response = result.toString();
    qDebug() << "KazaServiceBridge: Service response:" << response;
    emit serviceQueryCompleted(response);
    return response;

#else
    Q_UNUSED(query);
    QString msg = "Service bridge only available on Android";
    qDebug() << "KazaServiceBridge:" << msg;
    return msg;
#endif
}

bool KazaServiceBridge::isServiceRunning()
{
#ifdef Q_OS_ANDROID
    qDebug() << "KazaServiceBridge: Checking if service is running";

    QJniEnvironment env;

    // Call ServiceBridge.isServiceRunning() static method
    bool running = QJniObject::callStaticMethod<jboolean>(
        "org/kaza/ServiceBridge",
        "isServiceRunning",
        "()Z"
    );

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        qWarning() << "KazaServiceBridge: JNI Exception checking service status";
        return false;
    }

    qDebug() << "KazaServiceBridge: Service running:" << running;
    return running;

#else
    qDebug() << "KazaServiceBridge: Service check only available on Android";
    return false;
#endif
}

bool KazaServiceBridge::sendNotification(const QString &title, const QString &message)
{
#ifdef Q_OS_ANDROID
    qDebug() << "KazaServiceBridge: Sending notification:" << title;

    QJniEnvironment env;

    // Call ServiceBridge.sendNotification(String, String) static method
    QJniObject jTitle = QJniObject::fromString(title);
    QJniObject jMessage = QJniObject::fromString(message);

    QJniObject result = QJniObject::callStaticObjectMethod(
        "org/kaza/ServiceBridge",
        "sendNotification",
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        jTitle.object<jstring>(),
        jMessage.object<jstring>()
    );

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        qWarning() << "KazaServiceBridge: JNI Exception sending notification";
        return false;
    }

    if (!result.isValid()) {
        qWarning() << "KazaServiceBridge: Invalid response from sendNotification";
        return false;
    }

    QString response = result.toString();
    qDebug() << "KazaServiceBridge: Notification response:" << response;

    return response == "OK";

#else
    Q_UNUSED(title);
    Q_UNUSED(message);
    qDebug() << "KazaServiceBridge: Notification only available on Android";
    return false;
#endif
}

bool KazaServiceBridge::configureService(const QString &clientCert, const QString &caCert,
                                          const QString &clientKey, const QString &clientPass,
                                          const QString &host, int port, const QString &username)
{
#ifdef Q_OS_ANDROID
    qDebug() << "KazaServiceBridge: Configuring service";
    qDebug() << "KazaServiceBridge: Host:" << host << ":" << port;
    qDebug() << "KazaServiceBridge: Username:" << username;

    QJniEnvironment env;

    // Call ServiceBridge.configureService(...) static method
    QJniObject jClientCert = QJniObject::fromString(clientCert);
    QJniObject jCaCert = QJniObject::fromString(caCert);
    QJniObject jClientKey = QJniObject::fromString(clientKey);
    QJniObject jClientPass = QJniObject::fromString(clientPass);
    QJniObject jHost = QJniObject::fromString(host);
    QJniObject jUsername = QJniObject::fromString(username);

    QJniObject result = QJniObject::callStaticObjectMethod(
        "org/kaza/ServiceBridge",
        "configureService",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;",
        jClientCert.object<jstring>(),
        jCaCert.object<jstring>(),
        jClientKey.object<jstring>(),
        jClientPass.object<jstring>(),
        jHost.object<jstring>(),
        port,
        jUsername.object<jstring>()
    );

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        qWarning() << "KazaServiceBridge: JNI Exception configuring service";
        emit serviceConfigured(false);
        return false;
    }

    if (!result.isValid()) {
        qWarning() << "KazaServiceBridge: Invalid response from configureService";
        emit serviceConfigured(false);
        return false;
    }

    QString response = result.toString();
    qDebug() << "KazaServiceBridge: Configuration response:" << response;

    bool success = response == "OK";
    emit serviceConfigured(success);
    return success;

#else
    Q_UNUSED(clientCert);
    Q_UNUSED(caCert);
    Q_UNUSED(clientKey);
    Q_UNUSED(clientPass);
    Q_UNUSED(host);
    Q_UNUSED(port);
    Q_UNUSED(username);
    qDebug() << "KazaServiceBridge: Configuration only available on Android";
    return false;
#endif
}

