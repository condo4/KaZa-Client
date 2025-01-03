#include "dbquery.h"
#include "KazaApplicationManager.h"
#include "kazaprotocol.h"
#include <QEventLoop>

uint32_t DbQuery::m_idmax = 0;
QMap<uint32_t, DbQuery *> DbQuery::m_pending;

uint32_t DbQuery::id() const
{
    return m_id;
}

QString DbQuery::query() const
{
    return m_query;
}

void DbQuery::exec()
{
    m_pending[m_id] = this;
    KaZaProtocol *protocol = KazaApplicationManager::protocol();
    protocol->sendDbQuery(m_id, m_query);
}

void DbQuery::setColumns(const QStringList &newColumns)
{
    m_columns = newColumns;
}

void DbQuery::addRow(QList<QVariant> &row)
{
    m_data.append(row);
}

void DbQuery::complete()
{

}

QString DbQuery::title(int index)
{
    if(index >= m_columns.length())
        return QString();
    return m_columns[index];
}

QVariant DbQuery::data(int row, int column)
{
    if(row >= m_data.count())
        return QVariant();
    if(column >= m_data[row].count())
        return QVariant();
    return m_data[row][column];
}

qsizetype DbQuery::rowCount()
{
    return m_data.count();
}

qsizetype DbQuery::columnCount()
{
    return m_columns.count();
}

DbQuery::DbQuery(const QString &query, QObject *parent)
    : QObject{parent}
    , m_query(query)
    , m_id(++m_idmax)
{
    KaZaProtocol *protocol = KazaApplicationManager::protocol();
    QObject::connect(protocol, &KaZaProtocol::frameDbQueryResult, this, &DbQuery::_dbResult);
}

void DbQuery::_dbResult(uint8_t id, const QStringList &culumns, const QList<QList<QVariant> > &result)
{
    if(id == m_id)
    {
        m_columns = culumns;
        m_data = result;
        m_complete = true;
        emit completed();
    }
}
