#include "KazaApplicationManager.h"
#include "qdebug.h"

#include <QFile>
#include <QSslConfiguration>
#include <QSslSocket>
#include <QSslKey>
#include <QUrl>
#include <QRegularExpression>
#include <QStandardPaths>
#include <QDir>
#include <QResource>
#include <QDirIterator>
#include <QEventLoop>
#include <QCryptographicHash>
#include <QApplication>
#include <QDomDocument>
#include <QBuffer>
#include <QThread>

#include "kazaobject.h"
#include "kazanotificationchecker.h"
#include "kazaservicebridge.h"


#ifdef ANDROID
    #include <QtCore/private/qandroidextras_p.h>
    #include <QJniObject>
    #include <jni.h>

// Fonction C++ qui sera appelée par Java
extern "C" {

}
#endif

KazaApplicationManager *KazaApplicationManager::m_instance = nullptr;

KazaApplicationManager::KazaApplicationManager(QObject *parent)
    : QObject{parent}
    , m_protocol(&m_ssl)
    , m_configured(m_settings.value("configured").toBool())
    , m_homepage("Homepage.qml")
{
    QObject::connect(static_cast<QApplication *>(QApplication::instance()), &QApplication::applicationStateChanged, this, &KazaApplicationManager::__appStateChange);
    QObject::connect(&m_ssl, &QSslSocket::encrypted, this, &KazaApplicationManager::_encrypted);
    QObject::connect(&m_protocol, &KaZaProtocol::disconnectFromHost, this, &KazaApplicationManager::_disconnected);

    // Regular protocol signals
    QObject::connect(&m_protocol, &KaZaProtocol::frameCommand, this, &KazaApplicationManager::_processFrameSystem);
    QObject::connect(&m_protocol, &KaZaProtocol::frameFile, this, &KazaApplicationManager::_processFrameFile);
    QObject::connect(&m_protocol, &KaZaProtocol::frameOject, this, &KazaApplicationManager::_processFrameObjectValue);

    QObject::connect(&m_ssl, &QSslSocket::sslErrors, [this](const QList<QSslError> &errors){
        for(auto &e: errors)
        {
            qDebug() << "SSL ERROR: " << e;
        }
    });

    QObject::connect(&m_ssl, &QSslSocket::peerVerifyError, [this](const QSslError &error){
        qDebug() << "SSL Verify ERROR: " << error;
    });

    // TODO: REMOVE AFTER DEBUG OK
    m_instance = this;
    m_debug = true;

    if(!m_configured)
        qInfo() << "Kaza need to configure";


    QDir rep(QStandardPaths::standardLocations(QStandardPaths::AppDataLocation).at(0));
    if(!rep.exists())
    {
        rep.mkpath(rep.absolutePath());
    }
    _calculateAppChecksum();

    if(m_configured) connectClient();
}

KazaApplicationManager::~KazaApplicationManager()
{
    qDebug() << "KazaApplicationManager::~KazaApplicationManager()";
}


#define FILEPATH(p) QUrl(p).path()



bool KazaApplicationManager::setConfiguration(QString host,
                                              uint16_t port,
                                              QString adminPassword,
                                              QString username,
                                              QString userPassword)
{
    QDomDocument xml;
    QSslSocket socket;
    qDebug() << "setConfiguration(" + host + ":" + QString::number(port) + ", [admin], " + username + ", [userpass])";

    // Configure SSL socket for control port (VerifyNone mode, no client cert required for initial config)
    QSslConfiguration sslConf = QSslConfiguration::defaultConfiguration();
    sslConf.setPeerVerifyMode(QSslSocket::VerifyNone); // Control port doesn't require client verification
    socket.setSslConfiguration(sslConf);

    qDebug() << "Connecting to control port with SSL...";
    socket.connectToHostEncrypted(host, port);
    if(!socket.waitForEncrypted(10000)) // Wait up to 10 seconds for SSL handshake
    {
        qWarning() << "Failed to connect to control server:" << socket.errorString();
        setErrorMsg("Failed to connect to server: " + socket.errorString());
        return false;
    }

    qDebug() << "SSL connection established to control port";

    // Send new protocol command: clientconf? adminpass username userpass
    QString command = QString("clientconf? %1 %2 %3\n")
                          .arg(adminPassword)
                          .arg(username)
                          .arg(userPassword);
    socket.write(command.toUtf8());
    socket.flush();

    QByteArray data;
    while(!data.contains("</param>") && !data.contains("ERROR:"))
    {
        if(!socket.waitForReadyRead(5000))
        {
            qWarning() << "Timeout waiting for server response";
            setErrorMsg("Timeout waiting for server response");
            socket.close();
            return false;
        }
        data.append(socket.readAll());
    }
    socket.close();

    // Check for error response
    if(data.contains("ERROR:"))
    {
        QString errorMsg = QString::fromUtf8(data).trimmed();
        qWarning() << "Server returned error:" << errorMsg;
        setErrorMsg(errorMsg);
        return false;
    }

    qDebug() << "DATA:" << data;
    auto parseResult = xml.setContent(data);
    if(!parseResult)
    {
        qWarning() << "Failed to parse XML:" << parseResult.errorMessage
                   << "at line" << parseResult.errorLine
                   << "column" << parseResult.errorColumn;
        setErrorMsg("Failed to parse server response: " + parseResult.errorMessage);
        return false;
    }
    QString sslhost = xml.elementsByTagName("sslhost").item(0).toElement().text().trimmed();
    QString sslport = xml.elementsByTagName("sslport").item(0).toElement().text().trimmed();
    QString certificate = xml.elementsByTagName("certificate").item(0).toElement().text().trimmed();
    QString key = xml.elementsByTagName("key").item(0).toElement().text().trimmed();
    QString ca = xml.elementsByTagName("ca").item(0).toElement().text().trimmed();

    QStringList paths = QStandardPaths::standardLocations(QStandardPaths::AppConfigLocation);
    if(paths.isEmpty())
    {
        qWarning() << "ERROR: No AppConfigLocation to install";
        return false;
    }
    QDir output(paths.first());
    if(!output.exists())
    {
        output.mkpath(".");
    }

    QFile caCertFileStore(output.path() + "/ca.cert.pem");
    if(!caCertFileStore.open(QIODevice::ReadWrite))
    {
        qWarning() << "ERROR: Can't open " + output.path() + "/ca.cert.pem";
        return false;
    }
    caCertFileStore.write(ca.toUtf8());
    caCertFileStore.flush();
    caCertFileStore.close();

    QFile clientCertFileStore(output.path() + "/client.cert");
    if(!clientCertFileStore.open(QIODevice::ReadWrite))
    {
        qWarning() << "ERROR: Can't open " + output.path() + "/client.cert";
        return false;
    }
    clientCertFileStore.write(certificate.toUtf8());
    clientCertFileStore.flush();
    clientCertFileStore.close();

    QFile clientKeyFileStore(output.path() + "/client.key");
    if(!clientKeyFileStore.open(QIODevice::ReadWrite))
    {
        qWarning() << "ERROR: Can't open " + output.path() + "/client.key";
        return false;
    }
    clientKeyFileStore.write(key.toUtf8());
    clientKeyFileStore.flush();
    clientKeyFileStore.close();

    qDebug() << "Configuration registered, try connection";


    if(_configureSslSocket(m_ssl, output.path() + "/client.cert", output.path() + "/ca.cert.pem", output.path() + "/client.key", userPassword, sslhost, sslport.toInt()) == false)
        return false;

    qInfo().noquote() << "Kaza try connection to #" + host + "#:" + QString::number(port);
    m_ssl.connectToHostEncrypted(sslhost, sslport.toInt());
    qDebug() << "Try connection valid on" << sslhost << sslport.toInt();

    if(m_ssl.waitForEncrypted())
    {
        qDebug() << "Connection Ready";
        m_settings.setValue("ssl/cacert", output.path() + "/ca.cert.pem");
        m_settings.setValue("ssl/client_cert", output.path() + "/client.cert");
        m_settings.setValue("ssl/client_key", output.path() + "/client.key");
        m_settings.setValue("ssl/client_pass", userPassword);
        m_settings.setValue("ssl/host", sslhost);
        m_settings.setValue("ssl/port", sslport);
        m_settings.setValue("control/host", host);
        m_settings.setValue("control/port", port);
        m_settings.setValue("username", username);
        setConfigured(true);
        emit loginChanged();
        return true;
    }
    else
    {
        qWarning() << "Connection SSL Failed" << m_ssl.errorString() << sslhost;
        return false;
    }

    return false;
}

void KazaApplicationManager::suspend()
{
    qDebug() << "KazaApplicationManager: Suspending - disconnecting from host";
    // Disconnect asynchronously - don't block UI thread
    m_ssl.disconnectFromHost();
    // Don't wait - let the disconnected signal handle cleanup
}

void KazaApplicationManager::resume()
{
    qDebug() << "KazaApplicationManager: Resuming application";

    // Check if we're not already connected
    if(!m_ssl.isEncrypted())
    {
        qDebug() << "KazaApplicationManager: Not connected - reconnecting to" << m_host << ":" << m_port;

        // Connect asynchronously - don't block UI thread
        m_ssl.connectToHostEncrypted(m_host, m_port);

        // Don't wait for connection! Let the encrypted signal handler deal with it
        // The _encrypted() slot will handle re-registering objects when connected

        // Note: Object registration moved to _encrypted() handler to ensure
        // it only happens after successful connection, not blocking UI
    }
    else
    {
        qDebug() << "KazaApplicationManager: Already connected, no reconnection needed";
    }
}

void KazaApplicationManager::applicationReday()
{
    qDebug() << "Application ready";
}

void KazaApplicationManager::_encrypted()
{
    qInfo().noquote() << "SSL connected - starting version negotiation";

    m_secured = true;
    emit securedChanged();
    emit connectedChanged(m_ssl.isEncrypted());

    // Send version as FIRST frame after SSL handshake
    m_protocol.sendVersion(m_settings.value("username").toString(), m_devicename, 1);
}

void KazaApplicationManager::_startApplication()
{
    qDebug() << "_startApplication";
    if(m_appWanted != m_appChecksum)
    {
        qWarning() << "Bad application recived";
    }
    QResource::registerResource(m_appFile, "/application");
    m_homepage = "qrc:/application/main.qml";
    m_started = true;
    emit homepageChanged();
}


void KazaApplicationManager::_disconnected() {

}


void KazaApplicationManager::_sendObject(QVariant value, bool confirm)
{
    KaZaObject *obj = qobject_cast<KaZaObject *>(QObject::sender());
    if(!obj) {
        qWarning() << "Error in sendObject";
    }
    m_protocol.sendObject(m_kobjects.indexOf(obj), value, confirm);
}

bool KazaApplicationManager::_configureSslSocket(QSslSocket &ssl, const QString &clientCert, const QString &caCert, const QString &clientKey, const QString &clientPassword, const QString &host, uint16_t port)
{

    QFile caCertFile(FILEPATH(caCert));
    if(!caCertFile.open(QIODeviceBase::ReadOnly))
    {
        qDebug() << caCert << "not valid" << caCertFile.errorString();
        return false;
    }
    QFile clientCertFile(FILEPATH(clientCert));
    if(!clientCertFile.open(QIODeviceBase::ReadOnly))
    {
        qDebug() << clientCert << "not valid";
        return false;
    }
    QFile clientKeyFile(FILEPATH(clientKey));
    if(!clientKeyFile.open(QIODeviceBase::ReadOnly))
    {
        qDebug() << clientKey << "not valid";
        return false;
    }

    QSslConfiguration sslConf(QSslConfiguration::defaultConfiguration());
    QSslKey pkey(&clientKeyFile, QSsl::Rsa, QSsl::Pem, QSsl::PrivateKey, clientPassword.toUtf8());
    if(pkey.isNull())
    {
        setErrorMsg("Client Key invalid");
        return false;
    }
    QList<QSslCertificate> certificates = QSslCertificate::fromPath(FILEPATH(clientCert));
    if(certificates.size() != 1)
    {
        qDebug() << "certificates not valid 2";
        return false;
    }

    sslConf.setCaCertificates(QSslCertificate::fromPath(FILEPATH(caCert)));
    sslConf.setLocalCertificate(certificates[0]);
    sslConf.setPrivateKey(pkey);

    // TODO: REMOVE AFTER DEBUG OK
    if(m_debug) sslConf.setPeerVerifyMode(QSslSocket::VerifyNone);


    ssl.setSslConfiguration(sslConf);

    return true;
}

void KazaApplicationManager::__appStateChange(Qt::ApplicationState state)
{
    if(state == Qt::ApplicationInactive)
    {
        suspend();
    }

    if(state == Qt::ApplicationActive)
    {
        resume();
    }
}

bool KazaApplicationManager::configured() const
{
    return m_configured;
}

void KazaApplicationManager::setConfigured(bool newConfigured)
{
    if (m_configured == newConfigured)
        return;
    m_configured = newConfigured;
    m_settings.setValue("configured", m_configured);
    emit configuredChanged();
}

void KazaApplicationManager::connectClient()
{
    qDebug() << "connectClient";

    QString clientCert = m_settings.value("ssl/client_cert").toString();
    QString caCert = m_settings.value("ssl/cacert").toString();
    QString clientKey = m_settings.value("ssl/client_key").toString();
    QString clientPassword = m_settings.value("ssl/client_pass").toString();
    m_host = m_settings.value("ssl/host").toString();
    m_port = m_settings.value("ssl/port").toUInt();
    QString user = m_instance->m_settings.value("username").toString();

#ifdef ANDROID
    // Configure NotificationService with SSL parameters
    qDebug() << "=== Configuring NotificationService ===";
    KazaServiceBridge bridge;

    // Get device name from Android Build.MODEL
    QJniObject buildModel = QJniObject::getStaticObjectField(
        "android/os/Build",
        "MODEL",
        "Ljava/lang/String;"
    );
    m_devicename = buildModel.toString();
    qDebug() << "Device model:" << m_devicename;

    // Test communication first
    QString response = bridge.queryNotificationService("Connection");
    qDebug() << "Service bridge response:" << response;
    if (response == "OK") {
        qDebug() << "✓ Service communication successful";

        // Send configuration to service
        bool configured = bridge.configureService(clientCert, caCert, clientKey,
                                                   clientPassword, m_host, m_port, user);
        if (configured) {
            qDebug() << "✓ Service configured successfully";

            // Verify configuration
            QString configCheck = bridge.queryNotificationService("config");
            qDebug() << "Service configuration:" << configCheck;
        } else {
            qWarning() << "✗ Service configuration failed";
        }
    } else {
        qWarning() << "✗ Service communication failed:" << response;
    }
#else
    // Set device name for non-Android platforms
    m_devicename = "Desktop";
    qDebug() << "Device model:" << m_devicename;
#endif

    _configureSslSocket(m_ssl, clientCert, caCert, clientKey, clientPassword, m_host, m_port);

    qInfo().noquote() << "Kaza try connection to #" + m_host + "#:" + QString::number(m_port);
    m_ssl.connectToHostEncrypted(m_host, m_port);
}

bool KazaApplicationManager::connected() const
{
    return m_ssl.isEncrypted();
}

QString KazaApplicationManager::homepage() const
{
    return m_homepage;
}


KaZaObject *KazaApplicationManager::getKaZaObject(const QString &name) {
    if(!m_instance) return nullptr;

    for(KaZaObject *obj: m_instance->m_kobjects)
    {
        if(obj->name() == name)
        {
#ifdef DEBUG_OBJLIFECYCLE
            qDebug() << "KaZaObject: Find already existing object for " << name;
#endif
            obj->get();
            return obj;
        }
    }

    /* first ask for KaZaObject, create it */
    KaZaObject *obj = new KaZaObject();
    obj->setName(name);
    obj->get();
    m_instance->m_kobjects.append(obj);

#ifdef DEBUG_OBJLIFECYCLE
    qDebug() << "KaZaObject: Create a new object " << m_instance->m_kobjects.indexOf(obj) << " for " << name;
#endif
    m_instance->m_protocol.sendCommand("OBJ:" + name + ":" + QString::number(m_instance->m_kobjects.indexOf(obj)));
    QObject::connect(obj, &KaZaObject::changeRequested, m_instance, &KazaApplicationManager::_sendObject);
    return obj;
}

void KazaApplicationManager::putKaZaObject(KaZaObject *obj)
{
#ifdef DEBUG_OBJLIFECYCLE
    qDebug() << "KaZaObject: Release object " << obj->name();
#endif
    obj->put();
}

KaZaProtocol *KazaApplicationManager::protocol() {
    return &m_instance->m_protocol;
}



bool KazaApplicationManager::debug() const
{
    return m_debug;
}

bool KazaApplicationManager::secured() const
{
    return m_secured;
}

bool KazaApplicationManager::ready() const
{
    return m_ready;
}

QString KazaApplicationManager::login() const
{
    return m_settings.value("username").toString();
}

bool KazaApplicationManager::show() const
{
    return m_show;
}

void KazaApplicationManager::setShow(bool newShow)
{
    if (m_show == newShow)
        return;
    m_show = newShow;
    emit showChanged();
}


void KazaApplicationManager::_calculateAppChecksum() {
    m_appFile = QStandardPaths::standardLocations(QStandardPaths::AppDataLocation).at(0) + "/app.rcc";
    QFile f(m_appFile);
    if (f.open(QFile::ReadOnly)) {
        QCryptographicHash hash(QCryptographicHash::Algorithm::Md5);
        if (hash.addData(&f)) {
            m_appChecksum = hash.result().toBase64();
        }
    }
}

QString KazaApplicationManager::errorMsg() const
{
    return m_errorMsg;
}

void KazaApplicationManager::setErrorMsg(const QString &newErrorMsg)
{
    if (m_errorMsg == newErrorMsg)
        return;
    m_errorMsg = newErrorMsg;
    emit errorMsgChanged();
}


void KazaApplicationManager::_processFrameSystem(const QString &command)
{
    if(command.startsWith("APP:"))
    {
        if(!m_started)
        {
            if(m_settings.contains("Client/debug"))
            {
                m_homepage = "file://" + m_settings.value("Client/debug").toString() + "/main.qml";
                qDebug() << "USE DEBUG APPLICATION " << m_homepage;
                emit homepageChanged();
                return;
            }

            QStringList c = command.split(":");
            m_appWanted = c[1];
            if(c[1] != m_appChecksum)
            {
                qDebug() << "Need to download new version of application";
                m_protocol.sendCommand("APP?");
            }
            else
            {
                _startApplication();
            }
        }
    }
    else if(command.startsWith("OBJDESC"))
    {
        QStringList desc = command.split(":");
        if(desc.size() == 3)
        {
            for(KaZaObject *obj: m_kobjects)
            {
                if(obj->name() == desc[1])
                {
                    obj->setUnit(desc[2]);
                }
            }
        }
    }
    else if(command.startsWith("CONNECTED"))
    {
        qDebug() << "CONNECTED";
        qInfo().noquote() << "Version negotiation successful - connection ready";

        m_ready = true;
        emit readyChanged();

        // Re-register all referenced objects (for resume after sleep)
        // This ensures the server knows about objects that were previously registered
        for(KaZaObject *obj: m_instance->m_kobjects)
        {
            if(obj->refcount())
            {
                qDebug() << "KazaApplicationManager: Re-registering object:" << obj->name();
                m_protocol.sendCommand("OBJ:" + obj->name() + ":" + QString::number(m_instance->m_kobjects.indexOf(obj)));
            }
        }
    }
    else
    {
        qDebug() << "Unknown query" << command;
    }
}

void KazaApplicationManager::_processFrameFile(const QString &fileid, QByteArray data) {
    if(fileid == "APP")
    {
        qDebug() << "Create application " << m_appFile;
        QFile app(m_appFile);
        if(app.open(QIODevice::WriteOnly))
        {
            app.write(data);
        }
        app.close();
        _calculateAppChecksum();
        _startApplication();
    }
}


void KazaApplicationManager::_processFrameObjectValue(quint16 id, QVariant value) {
    if(id > m_kobjects.size())
    {
        qWarning() << "Invalid FrameObject " << id << "with" << value;
        return;
    }
    m_kobjects[id]->setValue(value);
#ifdef DEBUG_FRAME
    qDebug() << "_processFrameObjectValue " << id << m_kobjects[id]->name() << "=" << m_kobjects[id]->value();
#endif
}
