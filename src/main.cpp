#include <QApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QStandardPaths>
#include <QQuickStyle>
#include <QDirIterator>
#include "KazaApplicationManager.h"
#include "kzobject.h"
#include "kzhistory.h"
#include "kzport.h"
#include "kazaservicebridge.h"
#include <QtWebView>

#define xstr(s) str(s)
#define str(s) #s
#define VERSION xstr(VERSION_NAME)

#ifdef ANDROID
#include <QtCore/private/qandroidextras_p.h>
#endif

int main(int argc, char *argv[])
{
    QtWebView::initialize();
    QString version(VERSION);
    qInfo().noquote() << "KaZa " << version;
    QApplication app(argc, argv);
    app.setDesktopFileName("org.kazoe.kaza");
    app.setApplicationDisplayName("KaZa");
    app.setOrganizationName("KaZoe");

#ifdef ANDROID
    auto activity = QJniObject(QNativeInterface::QAndroidApplication::context());
    QAndroidIntent serviceIntent(activity.object(), "org/kaza/NotificationService");
    QJniObject result = activity.callObjectMethod(
        "startService",
        "(Landroid/content/Intent;)Landroid/content/ComponentName;",
        serviceIntent.handle().object());
    qDebug() << "START NOTIFICATION SERVICE RESULT: " << result.toString();

    auto task = QNativeInterface::QAndroidApplication::runOnAndroidMainThread([=]() {
        qDebug() << "@@@@@@@@@@@@ >>> runOnAndroidMainThread";
    });
#endif

    QQmlApplicationEngine engine;
    KazaApplicationManager manager;
    KazaServiceBridge serviceBridge;
    QQuickStyle::setStyle("Material");

    qmlRegisterType<KzObject>("org.kazoe.kaza", 1, 0, "KzObject");
    qmlRegisterType<KzHistory>("org.kazoe.kaza", 1, 0, "KzHistory");
    qmlRegisterType<KzPort>("org.kazoe.kaza", 1, 0, "KzPort");
    engine.rootContext()->setContextProperty("manager", &manager);
    engine.rootContext()->setContextProperty("knxiface", &manager); // For QML compatibility with KaZa 1.0
    engine.rootContext()->setContextProperty("serviceBridge", &serviceBridge);
    engine.rootContext()->setContextProperty("version", version);
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed,
        &app, []() { QCoreApplication::exit(-1); },
        Qt::QueuedConnection);
    engine.load("qrc:/qml/Main.qml");

    manager.setShow(true);

    int ret =  app.exec();

    manager.setShow(false);

    qDebug() << "CLOSE APP";

    return ret;
}
