#include "kzport.h"
#include "KazaApplicationManager.h"


uint16_t KzPort::m_instance = 0;

KzPort::KzPort(QObject *parent)
    : QObject{parent}
{
    m_server.listen();
    m_protocol = KazaApplicationManager::protocol();
    QObject::connect(&m_server, &QTcpServer::newConnection, this, &KzPort::_newConnection);
    QObject::connect(m_protocol, &KaZaProtocol::frameSocketState, this, &KzPort::_socketState);
    QObject::connect(m_protocol, &KaZaProtocol::frameSocketData, this, &KzPort::_socketData);
}

QString KzPort::hostname() const
{
    return m_hostname;
}

void KzPort::setHostname(const QString &newHostname)
{
    if (m_hostname == newHostname)
        return;
    m_hostname = newHostname;
    emit hostnameChanged();
}

int KzPort::port() const
{
    return m_port;
}

void KzPort::setport(int newPort)
{
    if (m_port == newPort)
        return;
    m_port = newPort;
    emit portChanged();
}


uint16_t KzPort::localPort() const
{
    return m_server.serverPort();
}

void KzPort::_newConnection()
{
    uint16_t id = ++m_instance;
    m_connections[id] = m_server.nextPendingConnection();
    QObject::connect(m_connections[id], &QTcpSocket::readyRead, this, &KzPort::_socketDataWrite);
    QObject::connect(m_connections[id], &QTcpSocket::stateChanged, this, &KzPort::_socketNewState);
    m_protocol->sendSocketConnect(id, m_hostname, m_port);
}

void KzPort::_socketState(uint16_t id, uint16_t state)
{
    //qDebug() << "_socketState" << id << QAbstractSocket::SocketState(state);
}

void KzPort::_socketData(uint16_t id, QByteArray data)
{
    m_connections[id]->write(data);
}

void KzPort::_socketDataWrite()
{
    QTcpSocket *sock = qobject_cast<QTcpSocket*>(QObject::sender());
    if(sock)
    {
        QByteArray data = sock->readAll();
        uint16_t id = m_connections.key(sock);
        m_protocol->sendSocketData(id, data);
    }
}

void KzPort::_socketNewState(uint16_t state)
{
    QTcpSocket *sock = qobject_cast<QTcpSocket*>(QObject::sender());
    if(sock)
    {
        QByteArray data = sock->readAll();
        uint16_t id = m_connections.key(sock);
        m_protocol->sendSocketState(id, state);
    }
}
