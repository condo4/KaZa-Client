#include <QApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QStandardPaths>
#include <QQuickStyle>
#include "KazaApplicationManager.h"
#include "kzobject.h"
#include "kzhistory.h"

#define xstr(s) str(s)
#define str(s) #s
#define VERSION xstr(VERSION_NAME)

int main(int argc, char *argv[])
{
    QString version(VERSION);
    qInfo().noquote() << "KaZa " << version;
    QApplication app(argc, argv);
    app.setDesktopFileName("org.kazoe.kaza");
    app.setApplicationDisplayName("KaZa");
    app.setOrganizationName("KaZoe");

    QQmlApplicationEngine engine;
    KazaApplicationManager manager;
    QQuickStyle::setStyle("Material");

    qmlRegisterType<KzObject>("org.kazoe.kaza", 1, 0, "KzObject");
    qmlRegisterType<KzHistory>("org.kazoe.kaza", 1, 0, "KzHistory");
    engine.rootContext()->setContextProperty("manager", &manager);
    engine.rootContext()->setContextProperty("knxiface", &manager); // For QML compatibility with KaZa 1.0
    engine.rootContext()->setContextProperty("version", version);
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed,
        &app, []() { QCoreApplication::exit(-1); },
        Qt::QueuedConnection);
    engine.loadFromModule("KaZa", "Main");

    manager.setShow(true);

    int ret =  app.exec();

    manager.setShow(false);

    qDebug() << "CLOSE APP";

    return ret;
}
