#ifndef KZPORT_H
#define KZPORT_H

#include <QObject>
#include <QQmlParserStatus>
#include <QTcpServer>
#include <QHash>
#include <QPointer>
#include <QAbstractSocket>

class KaZaProtocol;

class KzPort : public QObject
{
    Q_OBJECT
    QTcpServer m_server;
    static uint16_t m_instance;
    KaZaProtocol *m_protocol {nullptr};
    // Local sockets accepted by this instance, by tunnel id. Ids come from a counter shared by all
    // KzPort instances (every instance receives every socket frame and must ignore foreign ids).
    QHash<uint16_t, QPointer<QTcpSocket>> m_connections;

    Q_PROPERTY(QString hostname READ hostname WRITE setHostname NOTIFY hostnameChanged FINAL)
    Q_PROPERTY(int port READ port WRITE setport NOTIFY portChanged FINAL)
    Q_PROPERTY(uint16_t localPort READ localPort NOTIFY localPortChanged FINAL)


public:
    explicit KzPort(QObject *parent = nullptr);
    ~KzPort() override;

    QString hostname() const;
    void setHostname(const QString &newHostname);

    int port() const;
    void setport(int newPort);

    uint16_t localPort() const;

signals:
    void hostnameChanged();
    void portChanged();

    void localPortChanged();

private:
    uint16_t _allocateId();
    void _release(uint16_t id);
    QString m_hostname;
    uint16_t m_port {0};

private slots:
    void _newConnection();
    void _socketState(uint16_t id, uint16_t state);
    void _socketData(uint16_t id, QByteArray data);
    void _socketDataWrite();
    void _socketNewState(QAbstractSocket::SocketState state);
    void _closeAll();
};

#endif // KZPORT_H
