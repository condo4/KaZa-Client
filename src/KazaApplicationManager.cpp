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


#ifdef ANDROID
    #include <QtCore/private/qandroidextras_p.h>
    #include <jni.h>

// Fonction C++ qui sera appelée par Java
extern "C" {
JNIEXPORT void JNICALL
Java_org_kaza_LocalService_taskNative(JNIEnv *env, jobject obj)
{
    // Votre code à exécuter toutes les heures ici
    // Par exemple, vous pourriez émettre un signal vers votre application Qt
    // ou effectuer une tâche en arrière-plan
    KazaApplicationManager::tick();
}
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
                                              QString clientPassword,
                                              QString username)
{
    QDomDocument xml;
    QTcpSocket socket;
    qDebug() << "setConfguration(" + host + ":" + QString::number(port) + ", " + clientPassword + ", " + username +")";
    socket.connectToHost(host, port);
    if(!socket.waitForConnected())
        return false;

    QByteArray data;
    socket.write("clientconf?\n");

    while(!data.contains("</param>"))
    {
        socket.waitForReadyRead();
        data.append(socket.readAll());
    }
    socket.close();
    qDebug() << "DATA:" << data;
    xml.setContent(data);
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


    if(_configureSslSocket(m_ssl, output.path() + "/client.cert", output.path() + "/ca.cert.pem", output.path() + "/client.key", clientPassword, sslhost, sslport.toInt()) == false)
        return false;

    qInfo().noquote() << "Kaza try connection to #" + host + "#:" + QString::number(port);
    m_ssl.connectToHostEncrypted(host, port);
    qDebug() << "Try connection valid";

    if(m_ssl.waitForEncrypted())
    {
        qDebug() << "Connection Ready";
        m_settings.setValue("ssl/cacert", output.path() + "/ca.cert.pem");
        m_settings.setValue("ssl/client_cert", output.path() + "/client.cert");
        m_settings.setValue("ssl/client_key", output.path() + "/client.key");
        m_settings.setValue("ssl/client_pass", clientPassword);
        m_settings.setValue("ssl/host", sslhost);
        m_settings.setValue("ssl/port", sslport);
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
    m_ssl.disconnectFromHost();
    m_ssl.waitForDisconnected();
}

void KazaApplicationManager::resume()
{
    qDebug() << "Resume";
    if(!m_ssl.isEncrypted())
    {
        m_ssl.connectToHostEncrypted(m_host, m_port);
        m_ssl.waitForConnected();

        for(KaZaObject *obj: m_instance->m_kobjects)
        {
            if(obj->refcount())
            {
                m_protocol.sendCommand("OBJ:" + obj->name() + ":" + QString::number(m_instance->m_kobjects.indexOf(obj)));
            }
        }
    }
}

void KazaApplicationManager::applicationReday()
{
    qDebug() << "Application ready";
#ifdef ANDROID
    auto activity = QJniObject(QNativeInterface::QAndroidApplication::context());
    QAndroidIntent serviceIntent(activity.object(), "org/kaza/LocalService");
    QJniObject result = activity.callObjectMethod(
        "startService",
        "(Landroid/content/Intent;)Landroid/content/ComponentName;",
        serviceIntent.handle().object());
    qDebug() << "START SERVICE RESULT: " << result.toString();
#endif
}

void KazaApplicationManager::_encrypted()
{
    qInfo().noquote() << "KaZa is connected";

    m_ready = true;
    m_secured = true;
    emit readyChanged();
    emit securedChanged();
    emit connectedChanged(m_ssl.isEncrypted());
    m_protocol.sendCommand("USER:" + m_settings.value("username").toString());
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

void KazaApplicationManager::tick(){
    QString clientCert = m_instance->m_settings.value("ssl/client_cert").toString();
    QString caCert = m_instance->m_settings.value("ssl/cacert").toString();
    QString clientKey = m_instance->m_settings.value("ssl/client_key").toString();
    QString clientPassword = m_instance->m_settings.value("ssl/client_pass").toString();
    QString host = m_instance->m_settings.value("ssl/host").toString();
    QString user = m_instance->m_settings.value("username").toString();
    uint16_t port = m_instance->m_settings.value("ssl/port").toUInt();

    QThread* networkThread = new QThread();
    KaZaNotificationChecker* task = new KaZaNotificationChecker(clientCert, caCert, clientKey, clientPassword, host, user, port);
    task->moveToThread(networkThread);

    QObject::connect(networkThread, &QThread::started, task, &KaZaNotificationChecker::executeTask);
    QObject::connect(task, &QObject::destroyed, networkThread, &QThread::quit);
    QObject::connect(networkThread, &QThread::finished, networkThread, &QThread::deleteLater);

    networkThread->start();
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
