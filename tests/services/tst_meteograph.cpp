// Runs the real MeteoGraph.qml of the application against a fake server answering like the
// ecowitt.history / ecowitt.daily services. Set KAZA_METEOGRAPH_QML to the path of the page.
// Build: cmake -S tests/services -B <dir> && cmake --build <dir>
// Run:   KAZA_METEOGRAPH_QML=.../KaZaTrespeyres/Client/MeteoGraph.qml QT_QPA_PLATFORM=offscreen <dir>/tst_meteograph
#include <QtTest>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTimeZone>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQmlExpression>
#include <QQmlProperty>
#include <QQuickItem>
#include <QQuickWindow>
#include <QGuiApplication>
#include "kzrequest.h"
#include "kztable.h"
#include "kzservicelink.h"

using Response = KaZaProtocol::ServiceResponse;

class TstMeteoGraph : public QObject
{
    Q_OBJECT

    struct Seen { quint32 id; QString service; QVariantMap params; };

    QTcpServer m_listener;
    QTcpSocket m_clientSocket;
    QTcpSocket *m_serverSocket {nullptr};
    KaZaProtocol *m_clientProto {nullptr};
    KaZaProtocol *m_serverProto {nullptr};
    QList<Seen> m_requests;

    QVariant eval(QQmlContext *ctx, QObject *scope, const QString &expression)
    {
        QQmlExpression e(ctx, scope, expression);
        const QVariant v = e.evaluate();
        if (e.hasError())
            qWarning() << expression << e.error().toString();
        return v;
    }

private slots:
    void initTestCase()
    {
        qmlRegisterType<KzRequest>("org.kazoe.kaza", 1, 0, "KzRequest");
        qmlRegisterType<KzTable>("org.kazoe.kaza", 1, 0, "KzTable");
        QVERIFY(m_listener.listen(QHostAddress::LocalHost));
        m_clientSocket.connectToHost(QHostAddress::LocalHost, m_listener.serverPort());
        QVERIFY(m_listener.waitForNewConnection(3000));
        m_serverSocket = m_listener.nextPendingConnection();
        QVERIFY(m_clientSocket.waitForConnected(3000));
        m_clientProto = new KaZaProtocol(&m_clientSocket);
        m_serverProto = new KaZaProtocol(m_serverSocket);
        QObject::connect(m_serverProto, &KaZaProtocol::frameServiceRequest, this,
                         [this](quint32 id, QString service, QVariantMap params) { m_requests.append({id, service, params}); });
        KzServiceLink::instance()->attach(m_clientProto);
        KzServiceLink::instance()->setReady(true);
        KzServiceLink::instance()->setServicesAvailable(true);
    }

    void cleanupTestCase()
    {
        KzServiceLink::instance()->attach(nullptr);
        delete m_clientProto;
        delete m_serverProto;
    }

    void pageShowsHistoryThenDaily()
    {
        const QString path = qEnvironmentVariable("KAZA_METEOGRAPH_QML");
        if (path.isEmpty())
            QSKIP("KAZA_METEOGRAPH_QML not set");

        QQmlEngine engine;
        QQmlComponent harness(&engine);
        const QString harnessQml = QStringLiteral(
            "import QtQuick 2.9\n"
            "import QtQuick.Controls 2.3\n"
            "Item {\n"
            "    id: window\n"
            "    property bool darkMode: false\n"
            "    width: 400; height: 700\n"
            "    property alias page: loader.item\n"
            "    Loader { id: loader; anchors.fill: parent; source: \"%1\" }\n"
            "}\n").arg(QUrl::fromLocalFile(path).toString());
        harness.setData(harnessQml.toUtf8(), QUrl("file:///harness.qml"));
        QScopedPointer<QObject> root(harness.create());
        QVERIFY2(root, qPrintable(harness.errorString()));
        // GraphsView needs a window: render with the software backend so that no GPU is required
        QQuickWindow window;
        window.resize(400, 700);
        qobject_cast<QQuickItem *>(root.data())->setParentItem(window.contentItem());
        window.show();
        QObject *page = root->property("page").value<QObject *>();
        QVERIFY2(page, "MeteoGraph.qml did not load");
        QQmlContext *ctx = qmlContext(page);

        // First request: temperature, 24 h, 15 minute step
        QTRY_COMPARE_WITH_TIMEOUT(m_requests.size(), 1, 5000);
        QCOMPARE(m_requests.at(0).service, QStringLiteral("ecowitt.history"));
        QCOMPARE(m_requests.at(0).params.value("metric").toString(), QStringLiteral("temp"));
        QCOMPARE(m_requests.at(0).params.value("step").toString(), QStringLiteral("15 minutes"));
        const QDateTime start = m_requests.at(0).params.value("start").toDateTime();
        const QDateTime end = m_requests.at(0).params.value("end").toDateTime();
        QVERIFY(start.isValid() && end.isValid());
        QCOMPARE(start.secsTo(end), qint64(24 * 3600));

        Response h;
        h.id = m_requests.at(0).id;
        h.kind = KaZaProtocol::ServiceKindTable;
        h.columns = {"time", "min", "avg", "max"};
        for (int i = 0; i < 6; ++i)
            h.rows.append({start.addSecs(i * 3600), 10.0 + i, 12.0 + i, 14.0 + i});
        h.rows.append({start.addSecs(6 * 3600), QVariant(), QVariant(), QVariant()});   // bucket without data: skipped
        m_serverProto->sendServiceResponse(h);
        QTRY_COMPARE_WITH_TIMEOUT(eval(ctx, page, "seriesavg.count").toInt(), 6, 5000);
        QCOMPARE(eval(ctx, page, "seriesmin.count").toInt(), 6);
        QCOMPARE(eval(ctx, page, "request.state").toInt(), int(KzRequest::Ready));
        QCOMPARE(eval(ctx, page, "table.dataMax").toDouble(), 19.0);

        // Switching to the rain metric calls the daily service, with only start and end
        m_requests.clear();
        QVERIFY(QQmlProperty::write(page, "metricIndex", 7));
        QTRY_COMPARE_WITH_TIMEOUT(m_requests.size(), 1, 5000);
        QCOMPARE(m_requests.at(0).service, QStringLiteral("ecowitt.daily"));
        QCOMPARE(m_requests.at(0).params.keys(), QStringList({"end", "start"}));

        Response d;
        d.id = m_requests.at(0).id;
        d.kind = KaZaProtocol::ServiceKindTable;
        d.columns = {"day", "temp_min", "temp_avg", "temp_max", "humidity_min", "humidity_avg", "humidity_max",
                     "windspeed_avg", "windgust_max", "solar_max", "rain_total"};
        for (int i = 0; i < 3; ++i)
            d.rows.append({QDate(2026, 3, 1 + i), 1.0, 2.0, 3.0, 40.0, 50.0, 60.0, 5.0, 20.0, 300.0, 1.5 * i});
        m_serverProto->sendServiceResponse(d);
        QTRY_COMPARE_WITH_TIMEOUT(eval(ctx, page, "seriesavg.count").toInt(), 3, 5000);
        QCOMPARE(eval(ctx, page, "seriesavg.at(2).y").toDouble(), 3.0);      // rain_total of the last day

        // Moving back in time asks the same service for the previous window
        m_requests.clear();
        QVERIFY(QQmlProperty::write(page, "offset", 1));
        QTRY_COMPARE_WITH_TIMEOUT(m_requests.size(), 1, 5000);
        const QDateTime oldEnd = m_requests.at(0).params.value("end").toDateTime();
        QVERIFY(oldEnd < end);

        // A server error shows in the page state
        Response e;
        e.id = m_requests.at(0).id;
        e.status = KaZaProtocol::ServiceTimeout;
        e.errorCode = "timeout";
        m_serverProto->sendServiceResponse(e);
        QTRY_COMPARE_WITH_TIMEOUT(eval(ctx, page, "request.state").toInt(), int(KzRequest::Error), 5000);
        QCOMPARE(eval(ctx, page, "request.errorCode").toString(), QStringLiteral("timeout"));
    }
};

int main(int argc, char *argv[])
{
    QQuickWindow::setGraphicsApi(QSGRendererInterface::Software);
    QGuiApplication app(argc, argv);
    TstMeteoGraph tc;
    return QTest::qExec(&tc, argc, argv);
}
#include "tst_meteograph.moc"
