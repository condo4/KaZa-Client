#include "kzport.h"
#include "KazaApplicationManager.h"


uint16_t KzPort::m_instance = 0;

KzPort::KzPort(QObject *parent)
    : QObject{parent}
{
    // Loopback only: the tunnel must not be reachable from the LAN
    if(!m_server.listen(QHostAddress::LocalHost))
        qWarning() << "KzPort: cannot listen on loopback:" << m_server.errorString();
    m_protocol = KazaApplicationManager::protocol();
    QObject::connect(&m_server, &QTcpServer::newConnection, this, &KzPort::_newConnection);
    QObject::connect(m_protocol, &KaZaProtocol::frameSocketState, this, &KzPort::_socketState);
    QObject::connect(m_protocol, &KaZaProtocol::frameSocketData, this, &KzPort::_socketData);
    // The server forgets its tunnelled sockets when the session drops: ours would be dead ends
    QObject::connect(m_protocol, &KaZaProtocol::disconnectFromHost, this, &KzPort::_closeAll);
}

KzPort::~KzPort()
{
    _closeAll();
}

uint16_t KzPort::_allocateId()
{
    do {
        ++m_instance;
    } while(m_instance == 0 || m_connections.contains(m_instance));
    return m_instance;
}

void KzPort::_release(uint16_t id)
{
    QTcpSocket *sock = m_connections.take(id);
    if(sock)
    {
        sock->disconnect(this);
        sock->abort();
        sock->deleteLater();
    }
}

void KzPort::_closeAll()
{
    const QList<uint16_t> ids = m_connections.keys();
    for(uint16_t id: ids)
        _release(id);
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
    while(m_server.hasPendingConnections())
    {
        QTcpSocket *sock = m_server.nextPendingConnection();
        if(m_hostname.isEmpty() || m_port == 0)
        {
            qWarning() << "KzPort: hostname/port not set, connection refused";
            sock->abort();
            sock->deleteLater();
            continue;
        }
        uint16_t id = _allocateId();
        m_connections.insert(id, sock);
        QObject::connect(sock, &QTcpSocket::readyRead, this, &KzPort::_socketDataWrite);
        QObject::connect(sock, &QTcpSocket::stateChanged, this, &KzPort::_socketNewState);
        m_protocol->sendSocketConnect(id, m_hostname, m_port);
    }
}

void KzPort::_socketState(uint16_t id, uint16_t state)
{
    // The remote end closed its socket: flush what we have, then close ours
    QTcpSocket *sock = m_connections.value(id);
    if(!sock)
        return;
    if(state == QAbstractSocket::UnconnectedState)
    {
        sock->disconnectFromHost();
        if(sock->state() == QAbstractSocket::UnconnectedState)
            _release(id);
    }
}

void KzPort::_socketData(uint16_t id, QByteArray data)
{
    QTcpSocket *sock = m_connections.value(id);
    if(sock)
        sock->write(data);
}

void KzPort::_socketDataWrite()
{
    QTcpSocket *sock = qobject_cast<QTcpSocket*>(QObject::sender());
    if(!sock)
        return;
    uint16_t id = m_connections.key(sock, 0);
    if(id == 0)
        return;
    m_protocol->sendSocketData(id, sock->readAll());
}

void KzPort::_socketNewState(QAbstractSocket::SocketState state)
{
    QTcpSocket *sock = qobject_cast<QTcpSocket*>(QObject::sender());
    if(!sock)
        return;
    uint16_t id = m_connections.key(sock, 0);
    if(id == 0)
        return;
    m_protocol->sendSocketState(id, state);
    if(state == QAbstractSocket::UnconnectedState)
        _release(id);
}
