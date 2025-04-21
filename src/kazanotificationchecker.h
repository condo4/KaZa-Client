#ifndef KAZANOTIFICATIONCHECKER_H
#define KAZANOTIFICATIONCHECKER_H

#include <QObject>

class KaZaNotificationChecker : public QObject
{
    Q_OBJECT

    QString m_clientCert;
    QString m_caCert;
    QString m_clientKey;
    QString m_clientPassword;
    QString m_host;
    QString m_user;
    uint16_t m_port;
public:
    explicit KaZaNotificationChecker(QString clientCert, QString caCert, QString clientKey, QString clientPassword,  QString host, QString user, uint16_t port, QObject *parent = nullptr);

public slots:
    void executeTask();
    void sendNotification(const QString &title, const QString &message);

signals:
};

#endif // KAZANOTIFICATIONCHECKER_H
