#ifndef KZREQUEST_H
#define KZREQUEST_H

#include <QObject>
#include <QQmlParserStatus>
#include <QStringList>
#include <QTimer>
#include <QVariantList>
#include <QVariantMap>
#include "kazaprotocol.h"

// One call of a server service (KzService, see KaZa-Server doc/08-services.md): the client names the
// service and gives key/value parameters, it never sees nor sends SQL.
//
//   KzRequest {
//       service: "ecowitt.history"
//       params: ({ start: begin, end: end, step: "1 hour", metric: "temp" })
//       autoSend: true            // sends again, cancelling the previous one, when service/params change
//       onFinished: console.debug(rows.length)
//   }
class KzRequest : public QObject, public QQmlParserStatus
{
    Q_OBJECT
    Q_INTERFACES(QQmlParserStatus)

    Q_PROPERTY(QString service READ service WRITE setService NOTIFY serviceChanged FINAL)
    Q_PROPERTY(QVariantMap params READ params WRITE setParams NOTIFY paramsChanged FINAL)
    Q_PROPERTY(bool autoSend READ autoSend WRITE setAutoSend NOTIFY autoSendChanged FINAL)
    Q_PROPERTY(int timeout READ timeout WRITE setTimeout NOTIFY timeoutChanged FINAL)

    Q_PROPERTY(State state READ state NOTIFY stateChanged FINAL)
    Q_PROPERTY(bool loading READ loading NOTIFY stateChanged FINAL)
    Q_PROPERTY(int status READ status NOTIFY resultChanged FINAL)
    Q_PROPERTY(QString errorCode READ errorCode NOTIFY resultChanged FINAL)
    Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY resultChanged FINAL)
    Q_PROPERTY(QStringList columns READ columns NOTIFY resultChanged FINAL)
    Q_PROPERTY(QVariantList rows READ rows NOTIFY resultChanged FINAL)
    Q_PROPERTY(QVariant value READ value NOTIFY resultChanged FINAL)
    Q_PROPERTY(bool truncated READ truncated NOTIFY resultChanged FINAL)

public:
    enum State {
        Idle,
        Loading,    // waiting for the connection, or for the answer
        Ready,
        Error
    };
    Q_ENUM(State)

    explicit KzRequest(QObject *parent = nullptr);
    ~KzRequest() override;

    QString service() const { return m_service; }
    void setService(const QString &service);
    QVariantMap params() const { return m_params; }
    void setParams(const QVariantMap &params);
    bool autoSend() const { return m_autoSend; }
    void setAutoSend(bool autoSend);
    int timeout() const { return m_timeout; }
    void setTimeout(int ms);

    State state() const { return m_state; }
    bool loading() const { return m_state == Loading; }
    int status() const { return m_status; }
    QString errorCode() const { return m_errorCode; }
    QString errorMessage() const { return m_errorMessage; }
    QStringList columns() const { return m_columns; }
    QVariantList rows() const { return m_rows; }
    QVariant value() const { return m_value; }
    bool truncated() const { return m_truncated; }

    void classBegin() override {}
    void componentComplete() override;

public slots:
    // Sends the request (cancelling the one in flight). Waits for the connection if it is down.
    void send();
    void cancel();

signals:
    void serviceChanged();
    void paramsChanged();
    void autoSendChanged();
    void timeoutChanged();
    void stateChanged();
    void resultChanged();
    void finished();
    void failed();

private slots:
    void _linkChanged();
    void _response(const KaZaProtocol::ServiceResponse &response);
    void _timeout();

private:
    void _trySend();
    void _scheduleAutoSend();
    void _setState(State state);
    void _fail(const QString &code, const QString &message);
    void _abortInFlight();

    QString m_service;
    QVariantMap m_params;
    bool m_autoSend {false};
    int m_timeout {30000};
    bool m_complete {false};
    bool m_scheduled {false};

    State m_state {Idle};
    quint32 m_id {0};       // request being waited for, 0 when none
    bool m_sent {false};    // frame of m_id written on the current connection
    QTimer m_timer;

    int m_status {0};
    QString m_errorCode;
    QString m_errorMessage;
    QStringList m_columns;
    QVariantList m_rows;
    QVariant m_value;
    bool m_truncated {false};

    static quint32 s_idMax;
};

#endif // KZREQUEST_H
