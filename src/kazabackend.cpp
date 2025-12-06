#include "kazabackend.h"

#include <QFile>
#include <QSettings>
#include <QSslSocket>
#include <QSslConfiguration>
#include <QSslKey>
#include <QUrl>
#include <QThread>
#include <QTimer>
#include "kazaprotocol.h"

KaZaBackend *m_backend = nullptr;

#ifdef ANDROID
#include <QtCore/private/qandroidextras_p.h>
#include <jni.h>


QString m_clientCert;
QString m_caCert;
QString m_clientKey;
QString m_clientPassword;
QString m_host;
QString m_user;
uint16_t m_port;
bool m_configured {false};

// Fonction C++ qui sera appelée par Java
extern "C" {

JNIEXPORT void JNICALL Java_org_kaza_NotificationService_taskNative(JNIEnv *env, jobject obj)
{
    KaZaBackend::tick();
}

JNIEXPORT void JNICALL Java_org_kaza_NotificationService_nativeOnCreate(JNIEnv *env, jobject obj)
{
    qDebug() << "@@@@ C++ nativeOnCreate " << QThread::currentThreadId();
    m_backend = new KaZaBackend();
}

JNIEXPORT void JNICALL Java_org_kaza_NotificationService_nativeOnDestroy(JNIEnv *env, jobject obj)
{
    qDebug() << "@@@@ C++ nativeOnDestroy " << QThread::currentThreadId();

    if(m_backend)
    {
        delete m_backend;
    }
}

}
#endif

KaZaBackend::KaZaBackend(QObject *parent)
    : QObject{parent}
    , m_sslConf(QSslConfiguration::defaultConfiguration())
{
    QSettings settings;
    QString clientCert = settings.value("ssl/client_cert").toString();
    QString caCert = settings.value("ssl/cacert").toString();
    QString clientKey = settings.value("ssl/client_key").toString();
    QString clientPassword = settings.value("ssl/client_pass").toString();
    m_host = settings.value("ssl/host").toString();
    m_user = settings.value("username").toString();
    m_port = settings.value("ssl/port").toUInt();

    QFile caCertFile(QUrl(caCert).path());
    if(!caCertFile.open(QIODeviceBase::ReadOnly))
    {
        qDebug() << caCert << "not valid" << caCertFile.errorString();
        return;
    }
    QFile clientCertFile(QUrl(clientCert).path());
    if(!clientCertFile.open(QIODeviceBase::ReadOnly))
    {
        qDebug() << clientCert << "not valid";
        return;
    }
    QFile clientKeyFile(QUrl(clientKey).path());
    if(!clientKeyFile.open(QIODeviceBase::ReadOnly))
    {
        qDebug() << clientKey << "not valid";
        return;
    }

    QSslKey pkey(&clientKeyFile, QSsl::Rsa, QSsl::Pem, QSsl::PrivateKey, clientPassword.toUtf8());
    if(pkey.isNull())
    {
        qDebug() << "Client Key invalid";
        return;
    }
    QList<QSslCertificate> certificates = QSslCertificate::fromPath(QUrl(clientCert).path());
    if(certificates.size() != 1)
    {
        qDebug() << "certificates not valid 2";
        return;
    }

    m_sslConf.setCaCertificates(QSslCertificate::fromPath(QUrl(caCert).path()));
    m_sslConf.setLocalCertificate(certificates[0]);
    m_sslConf.setPrivateKey(pkey);

    // TODO: REMOVE AFTER DEBUG OK
    m_sslConf.setPeerVerifyMode(QSslSocket::VerifyNone);

    qDebug() << "@@@ KaZa Backend Created " << QThread::currentThreadId();
    m_backendThread = new QThread(this);
    moveToThread(m_backendThread);
    QObject::connect(m_backendThread, &QThread::started, this, &KaZaBackend::started);
    m_backendThread->start();
}

#define FILEPATH(p) QUrl(p).path()

bool KaZaBackend::_configureSslSocket()
{



    return true;
}

void KaZaBackend::_command(QString msg)
{
    qDebug() << "@@@ KaZa Backend "  << msg;
    QStringList m = msg.split(":");
    if(m[0] == "NOTIFY")
    {
#ifdef ANDROID
        QJniEnvironment env;
        QJniObject serviceInstance = QJniObject::callStaticObjectMethod(
            "org/kaza/NotificationService", "getInstance", "()Lorg/kaza/NotificationService;");

        if (!serviceInstance.isValid()) {
            qWarning() << "Impossible d'obtenir l'instance du service Java";
            return;
        }

        // Convertir les chaînes Qt en chaînes Java
        QJniObject jTitle = QJniObject::fromString("Notification");
        QJniObject jMessage = QJniObject::fromString(m[1]);

        serviceInstance.callMethod<void>(
            "notify",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            jTitle.object<jstring>(),
            jMessage.object<jstring>()
            );

        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            qWarning() << "Exception lors de l'appel à la méthode notify";
        }
#endif
    }
}

void KaZaBackend::_tick()
{
    qDebug() << "@@@ KaZa Backend _tick" << QThread::currentThreadId();
}

KaZaBackend::~KaZaBackend()
{
    qDebug() << "@@@ KaZa Backend Destroyed " << QThread::currentThreadId();
}


void KaZaBackend::started()
{
    qDebug() << "@@@ KaZa Backend start " << QThread::currentThreadId();
    m_ssl = new QSslSocket(this);
    m_ssl->setSslConfiguration(m_sslConf);
    m_protocol = new KaZaProtocol(m_ssl, this);

    QObject::connect( m_protocol, &KaZaProtocol::frameCommand, this, &KaZaBackend::_command, Qt::QueuedConnection);
    m_ssl->connectToHostEncrypted(m_host, m_port);
    bool ret = m_ssl->waitForConnected(5000);

    if(!ret)
    {
        qWarning() << "Fail to connect" << m_host;
        return;
    }
    qDebug() << "@@@ KaZa Backend connected " << QThread::currentThreadId();

    m_timer = new QTimer(this);
    QObject::connect(m_timer, &QTimer::timeout, [this](){
        qDebug() << "@@@ KaZa Backend Tick";
        m_protocol->sendCommand("TICK");
    });
    m_timer->setInterval(1000);
    m_timer->setSingleShot(false);
    //m_timer->start();

    qDebug() << "@@@ KaZa Backend thread running " << m_backendThread->isRunning();

}

void KaZaBackend::tick(){
    qDebug() << "TICK";
    if(m_backend)
    {
        m_backend->_tick();
    }
}
