#include "kazabackend.h"


#ifdef ANDROID
#include <QtCore/private/qandroidextras_p.h>
#include <jni.h>

KaZaBackend *m_backend = nullptr;
QThread *m_backendThread = nullptr;

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

JNIEXPORT void JNICALL Java_org_kaza_LocalService_nativeOnCreate(JNIEnv *env, jobject obj)
{
    qDebug() << "@@@@ C++ nativeOnCreate " << QThread::currentThreadId();
    m_backend = new KaZaBackend();
    m_backendThread = new QThread();
    m_backend->moveToThread(m_backendThread);

    QObject::connect(m_backendThread, &QThread::started, m_backend, &KaZaBackend::start);
    QObject::connect(m_backendThread, &QThread::finished, m_backendThread, &QThread::deleteLater);
    QObject::connect(m_backend, &QObject::destroyed, m_backendThread, &QThread::quit);
    m_backendThread->start();
}

JNIEXPORT void JNICALL Java_org_kaza_LocalService_nativeOnDestroy(JNIEnv *env, jobject obj)
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
{
    qDebug() << "@@@ KaZa Backend Created " << QThread::currentThreadId();
}

KaZaBackend::~KaZaBackend()
{
    qDebug() << "@@@ KaZa Backend Destroyed " << QThread::currentThreadId();
}

void KaZaBackend::configure(QString clientCert, QString caCert, QString clientKey, QString clientPassword, QString host, QString user, uint16_t port)
{
    if(!m_configured)
    {
        m_clientCert = clientCert;
        m_caCert = caCert;
        m_clientKey = clientKey;
        m_clientPassword = clientPassword;
        m_host = host;
        m_user = user;
        m_port = port;
        m_configured = true;
        qDebug() << "@@@ KaZa Backend configured";
    }
}

void KaZaBackend::start()
{
    qDebug() << "@@@ KaZa Backend start " << QThread::currentThreadId();
}


