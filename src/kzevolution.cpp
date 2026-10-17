#include "kzevolution.h"
#include "KazaApplicationManager.h"
#include "kazaprotocol.h"
#include <algorithm>

uint32_t KzEvolution::m_idMax = 0;

namespace {
quint8 stepFromString(const QString &step)
{
    if (step == "hour")  return KaZaProtocol::HistoryStepHour;
    if (step == "week")  return KaZaProtocol::HistoryStepWeek;
    if (step == "month") return KaZaProtocol::HistoryStepMonth;
    if (step == "year")  return KaZaProtocol::HistoryStepYear;
    return KaZaProtocol::HistoryStepDay;
}
}

KzEvolution::KzEvolution(QObject *parent)
    : QAbstractTableModel{parent}
{
    KaZaProtocol *protocol = KazaApplicationManager::protocol();
    QObject::connect(protocol, &KaZaProtocol::frameHistoryResult, this, &KzEvolution::_historyResult);
}

int KzEvolution::rowCount(const QModelIndex &parent) const
{
    Q_UNUSED(parent)
    return m_ts.count();
}

int KzEvolution::columnCount(const QModelIndex &parent) const
{
    Q_UNUSED(parent)
    return 4;
}

QVariant KzEvolution::data(const QModelIndex &index, int role) const
{
    Q_UNUSED(role)
    int row = index.row();
    if (row < 0 || row >= m_ts.count())
        return QVariant();

    switch (index.column()) {
    case COL_DATE: return m_ts.at(row);
    case COL_MIN:  return m_min.at(row);
    case COL_AVG:  return m_avg.at(row);
    case COL_MAX:  return m_max.at(row);
    }
    return QVariant();
}

QString KzEvolution::domain() const { return m_domain; }
void KzEvolution::setDomain(const QString &newDomain)
{
    if (m_domain == newDomain) return;
    m_domain = newDomain;
    emit domainChanged();
    _refresh();
}

QString KzEvolution::object() const { return m_object; }
void KzEvolution::setObject(const QString &newObject)
{
    if (m_object == newObject) return;
    m_object = newObject;
    emit objectChanged();
    _refresh();
}

QDateTime KzEvolution::start() const { return m_start; }
void KzEvolution::setStart(const QDateTime &newStart)
{
    if (m_start == newStart) return;
    m_start = newStart;
    emit startChanged();
    _refresh();
}

QDateTime KzEvolution::end() const { return m_end; }
void KzEvolution::setEnd(const QDateTime &newEnd)
{
    if (m_end == newEnd) return;
    m_end = newEnd;
    emit endChanged();
    _refresh();
}

QString KzEvolution::step() const { return m_step; }
void KzEvolution::setStep(const QString &newStep)
{
    if (m_step == newStep) return;
    m_step = newStep;
    emit stepChanged();
    _refresh();
}

QDateTime KzEvolution::dataStart() const
{
    return m_ts.isEmpty() ? QDateTime() : m_ts.first();
}

QDateTime KzEvolution::dataEnd() const
{
    return m_ts.isEmpty() ? QDateTime() : m_ts.last();
}

double KzEvolution::dataMin() const
{
    if (m_min.isEmpty()) return 0;
    return *std::min_element(m_min.constBegin(), m_min.constEnd());
}

double KzEvolution::dataMax() const
{
    if (m_max.isEmpty()) return 1;
    return *std::max_element(m_max.constBegin(), m_max.constEnd());
}

bool KzEvolution::loading() const { return m_loading; }
QString KzEvolution::error() const { return m_error; }

QString KzEvolution::formatDate(QDateTime date, QString format) const
{
    return date.toString(format);
}

void KzEvolution::refresh()
{
    _refresh();
}

void KzEvolution::_refresh()
{
    if (m_object.isEmpty() || !m_start.isValid() || !m_end.isValid() || m_start >= m_end)
        return;

    m_pendingId = ++m_idMax;
    m_loading = true;
    emit loadingChanged();

    KaZaProtocol *protocol = KazaApplicationManager::protocol();
    protocol->sendHistoryRequest(m_pendingId, m_domain, m_object, m_start, m_end, stepFromString(m_step));
}

void KzEvolution::_historyResult(uint32_t id, bool ok, QString error, QList<QDateTime> ts,
                                  QList<double> min, QList<double> avg, QList<double> max)
{
    if (id != m_pendingId)
        return;

    m_loading = false;
    emit loadingChanged();

    if (!ok) {
        m_error = error;
        emit errorChanged();
        qWarning() << "KzEvolution: history request failed:" << error;
        return;
    }

    if (!m_error.isEmpty()) {
        m_error.clear();
        emit errorChanged();
    }

    beginResetModel();
    m_ts = ts;
    m_min = min;
    m_avg = avg;
    m_max = max;
    endResetModel();

    emit dataStartChanged();
    emit dataEndChanged();
    emit dataMinChanged();
    emit dataMaxChanged();
}
