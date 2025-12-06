#ifndef KAZABACKEND_H
#define KAZABACKEND_H

#include <QObject>
#include <QSslConfiguration>

class QSslSocket;
class KaZaProtocol;
class QThread;
class QTimer;

class KaZaBackend : public QObject
{
    Q_OBJECT
    QSslConfiguration m_sslConf;
    QString m_host;
    QString m_user;
    uint16_t m_port;
    QSslSocket *m_ssl {nullptr};
    KaZaProtocol *m_protocol {nullptr};
    QThread *m_backendThread;
    QTimer *m_timer;

public:
    explicit KaZaBackend(QObject *parent = nullptr);
    virtual ~KaZaBackend();
    static void tick();

public slots:
    void started();

private slots:
    bool _configureSslSocket();
    void _command(QString msg);
    void _tick();

signals:



};

#endif // KAZABACKEND_H
