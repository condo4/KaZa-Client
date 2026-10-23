// KzRequest / KzTable against a fake server speaking the real frame protocol.
// Build: cmake -S tests/services -B <dir> && cmake --build <dir> && <dir>/tst_kzrequest
#include <QtTest>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTimeZone>
#include "kzrequest.h"
#include "kztable.h"
#include "kzservicelink.h"

using Response = KaZaProtocol::ServiceResponse;

class TstKzRequest : public QObject
{
    Q_OBJECT

    struct Seen {
        quint32 id;
        QString service;
        QVariantMap params;
    };

    QTcpServer m_listener;
    QTcpSocket m_clientSocket;
    QTcpSocket *m_serverSocket {nullptr};
    KaZaProtocol *m_clientProto {nullptr};
    KaZaProtocol *m_serverProto {nullptr};
    QList<Seen> m_requests;
    QList<quint32> m_cancels;

    void connectLink(bool ready = true, bool services = true)
    {
        KzServiceLink::instance()->setReady(ready);
        KzServiceLink::instance()->setServicesAvailable(services);
    }

    Response table(quint32 id, const QStringList &columns, const QList<QList<QVariant>> &rows)
    {
        Response r;
        r.id = id;
        r.kind = KaZaProtocol::ServiceKindTable;
        r.columns = columns;
        r.rows = rows;
        return r;
    }

private slots:
    void init()
    {
        QVERIFY(m_listener.listen(QHostAddress::LocalHost));
        m_clientSocket.connectToHost(QHostAddress::LocalHost, m_listener.serverPort());
        QVERIFY(m_listener.waitForNewConnection(3000));
        m_serverSocket = m_listener.nextPendingConnection();
        QVERIFY(m_clientSocket.waitForConnected(3000));
        m_clientProto = new KaZaProtocol(&m_clientSocket);
        m_serverProto = new KaZaProtocol(m_serverSocket);
        m_requests.clear();
        m_cancels.clear();
        QObject::connect(m_serverProto, &KaZaProtocol::frameServiceRequest, this,
                         [this](quint32 id, QString service, QVariantMap params) { m_requests.append({id, service, params}); });
        QObject::connect(m_serverProto, &KaZaProtocol::frameServiceCancel, this,
                         [this](quint32 id) { m_cancels.append(id); });
        KzServiceLink::instance()->attach(m_clientProto);
        connectLink(false, false);
    }

    void cleanup()
    {
        KzServiceLink::instance()->attach(nullptr);
        delete m_clientProto;
        delete m_serverProto;
        m_clientSocket.abort();
        delete m_serverSocket;
        m_listener.close();
    }

    void sendsAndMapsATable()
    {
        connectLink();
        KzRequest req;
        req.setService("svc.table");
        const QDateTime when(QDate(2026, 3, 1), QTime(12, 0), QTimeZone::UTC);
        req.setParams({{"start", when}, {"metric", "temp"}, {"n", 3}});
        QSignalSpy finished(&req, &KzRequest::finished);
        req.send();
        QCOMPARE(req.state(), KzRequest::Loading);
        QTRY_COMPARE(m_requests.size(), 1);
        QCOMPARE(m_requests.at(0).service, QStringLiteral("svc.table"));
        QCOMPARE(m_requests.at(0).params.value("start").toDateTime(), when);
        QCOMPARE(m_requests.at(0).params.value("n").toInt(), 3);

        Response r = table(m_requests.at(0).id, {"t", "v"}, {{when, 1.5}, {when.addDays(1), 2.5}});
        r.truncated = true;
        m_serverProto->sendServiceResponse(r);
        QTRY_COMPARE(finished.count(), 1);
        QCOMPARE(req.state(), KzRequest::Ready);
        QCOMPARE(req.columns(), QStringList({"t", "v"}));
        QCOMPARE(req.rows().size(), 2);
        QCOMPARE(req.rows().at(1).toList().at(1).toDouble(), 2.5);
        QVERIFY(req.truncated());
        QVERIFY(!req.loading());
    }

    void mapsAValueAndAnError()
    {
        connectLink();
        KzRequest req;
        req.setService("svc.value");
        req.send();
        QTRY_COMPARE(m_requests.size(), 1);
        Response v;
        v.id = m_requests.at(0).id;
        v.kind = KaZaProtocol::ServiceKindValue;
        v.value = 42;
        m_serverProto->sendServiceResponse(v);
        QTRY_COMPARE(req.state(), KzRequest::Ready);
        QCOMPARE(req.value().toInt(), 42);

        QSignalSpy failed(&req, &KzRequest::failed);
        req.send();
        QTRY_COMPARE(m_requests.size(), 2);
        Response e;
        e.id = m_requests.at(1).id;
        e.status = KaZaProtocol::ServiceInvalidParams;
        e.errorCode = "metric";
        e.errorMessage = "value not allowed";
        m_serverProto->sendServiceResponse(e);
        QTRY_COMPARE(failed.count(), 1);
        QCOMPARE(req.state(), KzRequest::Error);
        QCOMPARE(req.status(), int(KaZaProtocol::ServiceInvalidParams));
        QCOMPARE(req.errorCode(), QStringLiteral("metric"));
        QVERIFY(req.rows().isEmpty());
    }

    void waitsForTheConnection()
    {
        KzRequest req;
        req.setService("svc");
        req.send();
        QCOMPARE(req.state(), KzRequest::Loading);
        QTest::qWait(150);
        QCOMPARE(m_requests.size(), 0);
        KzServiceLink::instance()->setReady(true);            // CONNECTED, CAPS not seen yet
        QTest::qWait(100);
        QCOMPARE(m_requests.size(), 0);
        KzServiceLink::instance()->handleCaps("CAPS:services=1");
        QTRY_COMPARE(m_requests.size(), 1);
    }

    void newSendSupersedesAndStaleAnswerIsIgnored()
    {
        connectLink();
        KzRequest req;
        req.setService("svc");
        req.send();
        QTRY_COMPARE(m_requests.size(), 1);
        req.send();
        QTRY_COMPARE(m_requests.size(), 2);
        QTRY_COMPARE(m_cancels.size(), 1);
        QCOMPARE(m_cancels.at(0), m_requests.at(0).id);
        QVERIFY(m_requests.at(1).id != m_requests.at(0).id);

        m_serverProto->sendServiceResponse(table(m_requests.at(0).id, {"a"}, {{1}}));    // stale
        QTest::qWait(150);
        QCOMPARE(req.state(), KzRequest::Loading);
        m_serverProto->sendServiceResponse(table(m_requests.at(1).id, {"a"}, {{2}}));
        QTRY_COMPARE(req.state(), KzRequest::Ready);
        QCOMPARE(req.rows().at(0).toList().at(0).toInt(), 2);
    }

    void autoSendCoalescesChanges()
    {
        connectLink();
        KzRequest req;
        req.setAutoSend(true);
        req.componentComplete();
        req.setService("svc");
        req.setParams({{"a", 1}});
        req.setParams({{"a", 2}});            // same tick: a single request with the last values
        QTRY_COMPARE(m_requests.size(), 1);
        QCOMPARE(m_requests.at(0).params.value("a").toInt(), 2);

        m_serverProto->sendServiceResponse(table(m_requests.at(0).id, {"a"}, {}));
        QTRY_COMPARE(req.state(), KzRequest::Ready);

        req.setParams({{"a", 2}});            // identical: nothing to do
        QTest::qWait(100);
        QCOMPARE(m_requests.size(), 1);
        req.setParams({{"a", 3}});
        QTRY_COMPARE(m_requests.size(), 2);
        QCOMPARE(m_requests.at(1).params.value("a").toInt(), 3);
    }

    void autoSendWaitsForComponentComplete()
    {
        connectLink();
        KzRequest req;
        req.setAutoSend(true);
        req.setService("svc");
        QTest::qWait(100);
        QCOMPARE(m_requests.size(), 0);
        req.componentComplete();
        QTRY_COMPARE(m_requests.size(), 1);
    }

    void timeoutCodes()
    {
        // not connected at all
        {
            KzRequest req;
            req.setService("svc");
            req.setTimeout(150);
            req.send();
            QTRY_COMPARE(req.state(), KzRequest::Error);
            QCOMPARE(req.errorCode(), QStringLiteral("not_connected"));
        }
        // connected, server without services
        {
            KzServiceLink::instance()->setReady(true);
            KzRequest req;
            req.setService("svc");
            req.setTimeout(150);
            req.send();
            QTRY_COMPARE(req.state(), KzRequest::Error);
            QCOMPARE(req.errorCode(), QStringLiteral("unsupported"));
            QCOMPARE(m_requests.size(), 0);
        }
        // sent but never answered: the server is told to forget it
        {
            connectLink();
            KzRequest req;
            req.setService("svc");
            req.setTimeout(150);
            req.send();
            QTRY_COMPARE(req.state(), KzRequest::Error);
            QCOMPARE(req.errorCode(), QStringLiteral("timeout"));
            QTRY_COMPARE(m_cancels.size(), 1);
        }
    }

    void cancelGoesIdleAndNotifiesTheServer()
    {
        connectLink();
        KzRequest req;
        req.setService("svc");
        req.send();
        QTRY_COMPARE(m_requests.size(), 1);
        req.cancel();
        QCOMPARE(req.state(), KzRequest::Idle);
        QTRY_COMPARE(m_cancels.size(), 1);
        m_serverProto->sendServiceResponse(table(m_requests.at(0).id, {"a"}, {}));    // late answer: ignored
        QTest::qWait(150);
        QCOMPARE(req.state(), KzRequest::Idle);
    }

    void connectionLostWithoutAutoSendFails()
    {
        connectLink();
        KzRequest req;
        req.setService("svc");
        req.send();
        QTRY_COMPARE(m_requests.size(), 1);
        KzServiceLink::instance()->setServicesAvailable(false);
        KzServiceLink::instance()->setReady(false);
        QCOMPARE(req.state(), KzRequest::Error);
        QCOMPARE(req.errorCode(), QStringLiteral("disconnected"));
    }

    void connectionLostWithAutoSendResendsOnReconnect()
    {
        connectLink();
        KzRequest req;
        req.setAutoSend(true);
        req.componentComplete();
        req.setService("svc");
        QTRY_COMPARE(m_requests.size(), 1);
        KzServiceLink::instance()->setServicesAvailable(false);
        KzServiceLink::instance()->setReady(false);
        QCOMPARE(req.state(), KzRequest::Loading);
        connectLink();
        QTRY_COMPARE(m_requests.size(), 2);
        m_serverProto->sendServiceResponse(table(m_requests.at(1).id, {"a"}, {{7}}));
        QTRY_COMPARE(req.state(), KzRequest::Ready);
    }

    void emptyServiceIsAnError()
    {
        connectLink();
        KzRequest req;
        req.send();
        QCOMPARE(req.state(), KzRequest::Error);
        QCOMPARE(req.errorCode(), QStringLiteral("no_service"));
        QCOMPARE(m_requests.size(), 0);
    }

    // ---- KzTable ----------------------------------------------------------------------------

    void tableExposesRowsAndColumns()
    {
        connectLink();
        KzRequest req;
        req.setService("svc");
        KzTable model;
        model.setRequest(&req);
        QCOMPARE(model.rowCount(), 0);
        req.send();
        QTRY_COMPARE(m_requests.size(), 1);
        const QDateTime t0(QDate(2026, 3, 1), QTime(0, 0), QTimeZone::UTC);
        m_serverProto->sendServiceResponse(table(m_requests.at(0).id, {"day", "lo", "hi"},
            {{QDate(2026, 3, 1), 1.0, 5.0}, {QDate(2026, 3, 2), -3.0, 9.5}}));
        QTRY_COMPARE(model.rowCount(), 2);
        QCOMPARE(model.columnCount(), 3);
        QCOMPARE(model.columns(), QStringList({"day", "lo", "hi"}));
        // QDate columns are handed to the charts as QDateTime
        QCOMPARE(model.data(model.index(0, 0)).typeId(), int(QMetaType::QDateTime));
        QCOMPARE(model.data(model.index(1, 0)).toDateTime().date(), QDate(2026, 3, 2));
        QCOMPARE(model.data(model.index(1, 1)).toDouble(), -3.0);
        QCOMPARE(model.dataMin(), -3.0);
        QCOMPARE(model.dataMax(), 9.5);
        QCOMPARE(model.dataStart().date(), QDate(2026, 3, 1));
        QCOMPARE(model.dataEnd().date(), QDate(2026, 3, 2));
        QCOMPARE(model.columnIndex("hi"), 2);
        QCOMPARE(model.columnIndex("nope"), -1);
        // roles named after the columns (TableView delegates)
        const auto roles = model.roleNames();
        QCOMPARE(model.data(model.index(0, 0), roles.key("hi")).toDouble(), 5.0);
        QCOMPARE(model.headerData(1, Qt::Horizontal).toString(), QStringLiteral("lo"));
    }

    void tableSkipsNullRowsOnRequest()
    {
        connectLink();
        KzRequest req;
        req.setService("svc");
        KzTable model;
        model.setRequest(&req);
        req.send();
        QTRY_COMPARE(m_requests.size(), 1);
        const QDateTime t(QDate(2026, 3, 1), QTime(0, 0), QTimeZone::UTC);
        m_serverProto->sendServiceResponse(table(m_requests.at(0).id, {"t", "min", "avg", "max"},
            {{t, 1.0, 2.0, 3.0}, {t.addSecs(3600), QVariant(), QVariant(), QVariant()}, {t.addSecs(7200), 2.0, 3.0, 4.0}}));
        QTRY_COMPARE(model.rowCount(), 3);
        model.setSkipNulls(true);
        QCOMPARE(model.rowCount(), 2);
        QCOMPARE(model.dataMax(), 4.0);
        model.setSkipNulls(false);
        QCOMPARE(model.rowCount(), 3);
    }

    void tableFollowsRequestChangesAndErrors()
    {
        connectLink();
        KzRequest req;
        req.setService("svc");
        KzTable model;
        model.setRequest(&req);
        QSignalSpy reset(&model, &QAbstractItemModel::modelReset);
        req.send();
        QTRY_COMPARE(m_requests.size(), 1);
        m_serverProto->sendServiceResponse(table(m_requests.at(0).id, {"a"}, {{1}, {2}}));
        QTRY_COMPARE(model.rowCount(), 2);
        req.send();
        QTRY_COMPARE(m_requests.size(), 2);
        Response e;
        e.id = m_requests.at(1).id;
        e.status = KaZaProtocol::ServiceTimeout;
        e.errorCode = "timeout";
        m_serverProto->sendServiceResponse(e);
        QTRY_COMPARE(model.rowCount(), 0);      // an error empties the table
        QVERIFY(reset.count() >= 2);
        model.setRequest(nullptr);
        QCOMPARE(model.rowCount(), 0);
    }
};

QTEST_MAIN(TstKzRequest)
#include "tst_kzrequest.moc"
