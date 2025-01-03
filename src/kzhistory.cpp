#include "kzhistory.h"
#include <cmath>
#include "dbquery.h"



void KzHistory::_refresh()
{
    QString query = m_query;
    query.replace("${TABLE}", m_table);
    m_dbquery = new DbQuery(query);
    QObject::connect(m_dbquery, &DbQuery::completed, this, &KzHistory::_dataCompleted);
    m_dbquery->exec();
}

KzHistory::KzHistory(QObject *parent)
    : QAbstractTableModel{parent}
{
    qDebug() << "Create KzHistory";
    void valueChanged();
    void unitChanged();
}

int KzHistory::rowCount(const QModelIndex &parent) const
{
    Q_UNUSED(parent)
    if(!m_dbquery) return 0;
    return m_dbquery->rowCount();
}

int KzHistory::columnCount(const QModelIndex &parent) const
{
    Q_UNUSED(parent)
    if(!m_dbquery) return 0;
    return m_dbquery->columnCount();
}

QVariant KzHistory::headerData(int section, Qt::Orientation orientation, int role) const
{
    Q_UNUSED(orientation)
    Q_UNUSED(role)
    if(!m_dbquery) return QVariant();
    return m_dbquery->title(section);
}

QVariant KzHistory::data(const QModelIndex &index, int role) const
{
    Q_UNUSED(role)
    if(!m_dbquery) return QVariant();
    return m_dbquery->data(index.row(), index.column());
}

QString KzHistory::formatDate(QDateTime date, QString format) const
{
    return date.toString(format);
}

const QDateTime KzHistory::dataStart() const
{
    if(!m_dbquery) return QDateTime();
    return m_dbquery->data(0,0).toDateTime();
}


const QDateTime KzHistory::dataEnd() const
{
    if(!m_dbquery) return QDateTime();
    if(m_dbquery->rowCount() == 0)
    {
        qWarning() << "No data available";
        return QDateTime();
    }
    return m_dbquery->data(m_dbquery->rowCount() - 1, 0).toDateTime();
}

int KzHistory::dataMin() const
{
    if(!m_dbquery) return 0;
    return m_datamin;
}

int KzHistory::dataMax() const
{
    if(!m_dbquery) return 1;
    return m_datamax;
}


const QDateTime &KzHistory::selected() const
{
    return m_selected;
}

void KzHistory::setSelected(const QDateTime &newSelected)
{
    if (m_selected == newSelected)
        return;
    m_selected = newSelected;
    qDebug() << "SELECT " << m_selected;
    emit selectedChanged();
}

QString KzHistory::query() const
{
    return m_query;
}

void KzHistory::setQuery(const QString &newQuery)
{
    if (m_query == newQuery)
        return;
    m_query = newQuery;

    emit queryChanged();
    _refresh();
}

QString KzHistory::table() const
{
    return m_table;
}

void KzHistory::_dataCompleted()
{
    qDebug() << "Data complete " << m_dbquery->rowCount();
    beginResetModel();
    m_datamax = m_dbquery->data(0, 1).toInt();
    m_datamin = m_dbquery->data(0, 1).toInt();

    for(int r = 0; r < m_dbquery->rowCount(); r++)
    {
        for(int c = 1; c < m_dbquery->columnCount(); c++)
        {
            if(m_dbquery->data(r, c).toInt() > m_datamax)
            {
                m_datamax = m_dbquery->data(r, c).toInt();
            }
            if(m_dbquery->data(r, c).toInt() < m_datamin)
            {
                m_datamin = m_dbquery->data(r, c).toInt();
            }
        }
    }
    endResetModel();
    emit dataMinChanged();
    emit dataMaxChanged();
    emit dataStartChanged();
    emit dataEndChanged();
}
