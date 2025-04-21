#include "kazanotificationchecker.h"
#include <kazaprotocol.h>
#include <QDebug>
#include <QSslSocket>
#include <QSslConfiguration>
#include <QSslKey>
#include <QFile>
#include <QUrl>
#include <QEventLoop>
#include <QTimer>
#ifdef Q_OS_ANDROID
#include <QJniObject>
#include <QJniEnvironment>
#endif

#define FILEPATH(p) QUrl(p).path()

KaZaNotificationChecker::KaZaNotificationChecker(QString clientCert, QString caCert, QString clientKey, QString clientPassword,  QString host, QString user, uint16_t port, QObject *parent)
    : QObject{parent}
    , m_clientCert(clientCert)
    , m_caCert(caCert)
    , m_clientKey(clientKey)
    , m_clientPassword(clientPassword)
    , m_host(host)
    , m_user(user)
    , m_port(port)
{}

void KaZaNotificationChecker::executeTask()
{
    try {
        QSslSocket ssl;

        QFile caCertFile(FILEPATH(m_caCert));
        if(!caCertFile.open(QIODeviceBase::ReadOnly))
        {
            qDebug() << m_caCert << "not valid" << caCertFile.errorString();
            return;
        }
        QFile clientCertFile(FILEPATH(m_clientCert));
        if(!clientCertFile.open(QIODeviceBase::ReadOnly))
        {
            qDebug() << m_clientCert << "not valid";
            return;
        }
        QFile clientKeyFile(FILEPATH(m_clientKey));
        if(!clientKeyFile.open(QIODeviceBase::ReadOnly))
        {
            qDebug() << m_clientKey << "not valid";
            return;
        }

        QSslConfiguration sslConf(QSslConfiguration::defaultConfiguration());
        QSslKey pkey(&clientKeyFile, QSsl::Rsa, QSsl::Pem, QSsl::PrivateKey, m_clientPassword.toUtf8());
        if(pkey.isNull())
        {
            qDebug() << "Client Key invalid";
            return;
        }
        QList<QSslCertificate> certificates = QSslCertificate::fromPath(FILEPATH(m_clientCert));
        if(certificates.size() != 1)
        {
            qDebug() << "certificates not valid";
            return;
        }

        sslConf.setCaCertificates(QSslCertificate::fromPath(FILEPATH(m_caCert)));
        sslConf.setLocalCertificate(certificates[0]);
        sslConf.setPrivateKey(pkey);

        // TODO: REMOVE AFTER DEBUG OK
        sslConf.setPeerVerifyMode(QSslSocket::VerifyNone);
        ssl.setSslConfiguration(sslConf);

        ssl.connectToHostEncrypted(m_host, m_port);
        bool ret = ssl.waitForConnected(5000);

        if(!ret)
        {
            qWarning() << "Fail to connect" << m_host;
            return;
        }

        QTimer timeout;
        QEventLoop loop;
        KaZaProtocol protocol(&ssl);
        QObject::connect(&timeout, &QTimer::timeout, &loop, &QEventLoop::quit);
        QObject::connect(&protocol, &KaZaProtocol::frameCommand, [this, &loop](QString cmd){
            QByteArray base64Data = cmd.mid(6).toUtf8();
            QByteArray compressedData = QByteArray::fromBase64(base64Data);
            QByteArray decompressedData = qUncompress(compressedData);
            QStringList lines = QString::fromUtf8(decompressedData).split('\n');
            int nb = (lines.count() - 1) / 3;
            for(int i = 0; i < nb; i++)
            {
                sendNotification(lines[i*3], lines[i*3+1]);
            }

            loop.quit();
        });
        timeout.start(5000);
        protocol.sendCommand("ALARMS:" + m_user);

        loop.exec();

        ssl.disconnectFromHost();
        ssl.waitForDisconnected(5000);
    }
    catch (const std::exception &e) {
        qWarning() << "Exception in network task:" << e.what();
    }
    catch (...) {
        qWarning() << "Unknown exception in network task";
    }
}

void KaZaNotificationChecker::sendNotification(const QString &title, const QString &message)
{
#ifdef Q_OS_ANDROID
    QJniEnvironment env;

    // Obtenir l'instance du service Java
    QJniObject serviceInstance = QJniObject::callStaticObjectMethod(
        "org/kaza/LocalService",
        "getInstance",
        "()Lorg/kaza/LocalService;"
        );

    if (!serviceInstance.isValid()) {
        qWarning() << "Impossible d'obtenir l'instance du service Java";
        return;
    }

    // Convertir les chaînes Qt en chaînes Java
    QJniObject jTitle = QJniObject::fromString(title);
    QJniObject jMessage = QJniObject::fromString(message);

    // Appeler la méthode notify du service
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
#else
    Q_UNUSED(title);
    Q_UNUSED(message);
    qDebug() << "Notification (non-Android):" << title << "-" << message;
#endif
}
