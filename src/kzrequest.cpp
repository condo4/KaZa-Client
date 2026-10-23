#include "kzrequest.h"
#include "kzservicelink.h"

quint32 KzRequest::s_idMax = 0;

KzRequest::KzRequest(QObject *parent)
    : QObject{parent}
{
    m_timer.setSingleShot(true);
    QObject::connect(&m_timer, &QTimer::timeout, this, &KzRequest::_timeout);
    KzServiceLink *link = KzServiceLink::instance();
    QObject::connect(link, &KzServiceLink::changed, this, &KzRequest::_linkChanged);
    QObject::connect(link, &KzServiceLink::response, this, &KzRequest::_response);
}

KzRequest::~KzRequest()
{
    _abortInFlight();
}

void KzRequest::setService(const QString &service)
{
    if(m_service == service)
        return;
    m_service = service;
    emit serviceChanged();
    _scheduleAutoSend();
}

void KzRequest::setParams(const QVariantMap &params)
{
    if(m_params == params)
        return;
    m_params = params;
    emit paramsChanged();
    _scheduleAutoSend();
}

void KzRequest::setAutoSend(bool autoSend)
{
    if(m_autoSend == autoSend)
        return;
    m_autoSend = autoSend;
    emit autoSendChanged();
    _scheduleAutoSend();
}

void KzRequest::setTimeout(int ms)
{
    ms = qMax(100, ms);
    if(m_timeout == ms)
        return;
    m_timeout = ms;
    emit timeoutChanged();
}

void KzRequest::componentComplete()
{
    m_complete = true;
    _scheduleAutoSend();
}

// Property changes of a same QML evaluation arrive one after the other: coalesce them in one request
void KzRequest::_scheduleAutoSend()
{
    if(!m_autoSend || !m_complete || m_scheduled)
        return;
    m_scheduled = true;
    QTimer::singleShot(0, this, [this]() {
        m_scheduled = false;
        if(m_autoSend && !m_service.isEmpty())
            send();
    });
}

void KzRequest::_setState(State state)
{
    if(m_state == state)
        return;
    m_state = state;
    emit stateChanged();
}

void KzRequest::_abortInFlight()
{
    m_timer.stop();
    if(m_id && m_sent)
    {
        KzServiceLink *link = KzServiceLink::instance();
        if(link->usable())
            link->protocol()->sendServiceCancel(m_id);
    }
    m_id = 0;
    m_sent = false;
}

void KzRequest::send()
{
    if(m_service.isEmpty())
    {
        _fail(QStringLiteral("no_service"), QStringLiteral("no service name"));
        return;
    }
    _abortInFlight();
    m_id = ++s_idMax;
    _setState(Loading);
    // The timeout covers the wait for the connection as well
    m_timer.start(m_timeout);
    _trySend();
}

void KzRequest::cancel()
{
    if(m_state != Loading)
        return;
    _abortInFlight();
    _setState(Idle);
}

void KzRequest::_trySend()
{
    if(m_state != Loading || !m_id || m_sent)
        return;
    KzServiceLink *link = KzServiceLink::instance();
    if(!link->usable())
        return;     // sent when the link comes back (_linkChanged)
    link->protocol()->sendServiceRequest(m_id, m_service, m_params);
    m_sent = true;
}

void KzRequest::_linkChanged()
{
    KzServiceLink *link = KzServiceLink::instance();
    if(m_state != Loading)
        return;
    if(m_sent && !link->ready())
    {
        // The connection dropped with the request in flight: the answer will never come
        if(m_autoSend)
        {
            m_sent = false;     // asked again when the session is back
            m_timer.start(m_timeout);
        }
        else
        {
            _fail(QStringLiteral("disconnected"), QStringLiteral("connection lost"));
        }
        return;
    }
    _trySend();
}

void KzRequest::_fail(const QString &code, const QString &message)
{
    _abortInFlight();
    m_status = KaZaProtocol::ServiceInternal;
    m_errorCode = code;
    m_errorMessage = message;
    m_columns.clear();
    m_rows.clear();
    m_value.clear();
    m_truncated = false;
    emit resultChanged();
    _setState(Error);
    emit failed();
}

void KzRequest::_timeout()
{
    if(m_state != Loading)
        return;
    KzServiceLink *link = KzServiceLink::instance();
    if(m_sent)
        _fail(QStringLiteral("timeout"), QStringLiteral("no answer from the server"));
    else if(link->ready())
        _fail(QStringLiteral("unsupported"), QStringLiteral("the server does not provide services"));
    else
        _fail(QStringLiteral("not_connected"), QStringLiteral("not connected to the server"));
}

void KzRequest::_response(const KaZaProtocol::ServiceResponse &response)
{
    if(m_state != Loading || !m_id || response.id != m_id)
        return;     // stale answer (superseded or cancelled request)
    m_timer.stop();
    m_id = 0;
    m_sent = false;

    m_status = response.status;
    m_errorCode = response.errorCode;
    m_errorMessage = response.errorMessage;
    m_columns = response.columns;
    m_rows.clear();
    m_rows.reserve(response.rows.size());
    for(const QList<QVariant> &row : response.rows)
        m_rows.append(QVariant(QVariantList(row.cbegin(), row.cend())));
    m_value = response.value;
    m_truncated = response.truncated;
    emit resultChanged();

    if(response.status == KaZaProtocol::ServiceOk)
    {
        _setState(Ready);
        emit finished();
    }
    else
    {
        _setState(Error);
        emit failed();
    }
}
