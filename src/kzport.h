#ifndef KZPORT_H
#define KZPORT_H

#include <QObject>
#include <QQmlParserStatus>
#include <QTcpServer>

class KaZaProtocol;

class KzPort : public QObject
{
    Q_OBJECT
    QTcpServer m_server;
    static uint16_t m_instance;
    KaZaProtocol *m_protocol {nullptr};
    QMap<uint16_t, QTcpSocket*> m_connections;

    Q_PROPERTY(QString hostname READ hostname WRITE setHostname NOTIFY hostnameChanged FINAL)
    Q_PROPERTY(int port READ port WRITE setport NOTIFY portChanged FINAL)
    Q_PROPERTY(uint16_t localPort READ localPort NOTIFY localPortChanged FINAL)


public:
    explicit KzPort(QObject *parent = nullptr);

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
    QString m_hostname;
    uint16_t m_port;

private slots:
    void _newConnection();
    void _socketState(uint16_t id, uint16_t state);
    void _socketData(uint16_t id, QByteArray data);
    void _socketDataWrite();
    void _socketNewState(uint16_t state);
};

#endif // KZPORT_H
