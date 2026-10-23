#include "kztable.h"
#include "kzrequest.h"

#include <QDate>
#include <algorithm>
#include <cmath>

KzTable::KzTable(QObject *parent)
    : QAbstractTableModel{parent}
{
}

void KzTable::setRequest(KzRequest *request)
{
    if(m_request == request)
        return;
    if(m_request)
        QObject::disconnect(m_request, nullptr, this, nullptr);
    m_request = request;
    if(m_request)
        QObject::connect(m_request, &KzRequest::resultChanged, this, &KzTable::_reload);
    emit requestChanged();
    _reload();
}

void KzTable::setSkipNulls(bool skip)
{
    if(m_skipNulls == skip)
        return;
    m_skipNulls = skip;
    emit skipNullsChanged();
    _reload();
}

// A date column is handed to the charts as QDateTime
QVariant KzTable::normalized(const QVariant &cell)
{
    if(cell.typeId() == QMetaType::QDate)
        return cell.toDate().startOfDay();
    return cell;
}

void KzTable::_reload()
{
    beginResetModel();
    const QStringList columns = m_request ? m_request->columns() : QStringList();
    const QVariantList source = m_request ? m_request->rows() : QVariantList();
    m_rows.clear();
    m_rows.reserve(source.size());
    for(const QVariant &r : source)
    {
        QVariantList row = r.toList();
        bool keep = true;
        for(QVariant &cell : row)
            cell = normalized(cell);
        if(m_skipNulls)
        {
            for(qsizetype i = 1; i < row.size(); ++i)
            {
                if(row.at(i).isNull())
                {
                    keep = false;
                    break;
                }
            }
        }
        if(keep)
            m_rows.append(row);
    }
    const bool columnsChange = (columns != m_columns);
    m_columns = columns;
    endResetModel();
    if(columnsChange)
        emit columnsChanged();
    emit dataChanged();
}

int KzTable::rowCount(const QModelIndex &parent) const
{
    return parent.isValid() ? 0 : m_rows.size();
}

int KzTable::columnCount(const QModelIndex &parent) const
{
    return parent.isValid() ? 0 : m_columns.size();
}

QVariant KzTable::data(const QModelIndex &index, int role) const
{
    const int row = index.row();
    if(row < 0 || row >= m_rows.size())
        return QVariant();
    int column = index.column();
    if(role >= Qt::UserRole)
        column = role - Qt::UserRole;       // role named after the column
    else if(role != Qt::DisplayRole)
        return QVariant();
    if(column < 0 || column >= m_rows.at(row).size())
        return QVariant();
    return m_rows.at(row).at(column);
}

QVariant KzTable::headerData(int section, Qt::Orientation orientation, int role) const
{
    if(orientation == Qt::Horizontal && role == Qt::DisplayRole && section >= 0 && section < m_columns.size())
        return m_columns.at(section);
    return QVariant();
}

QHash<int, QByteArray> KzTable::roleNames() const
{
    QHash<int, QByteArray> roles = QAbstractTableModel::roleNames();
    for(int i = 0; i < m_columns.size(); ++i)
        roles.insert(Qt::UserRole + i, m_columns.at(i).toUtf8());
    return roles;
}

int KzTable::columnIndex(const QString &name) const
{
    return m_columns.indexOf(name);
}

QString KzTable::formatDate(const QDateTime &date, const QString &format) const
{
    return date.toString(format);
}

QDateTime KzTable::dataStart() const
{
    return (m_rows.isEmpty() || m_rows.first().isEmpty()) ? QDateTime() : m_rows.first().first().toDateTime();
}

QDateTime KzTable::dataEnd() const
{
    return (m_rows.isEmpty() || m_rows.last().isEmpty()) ? QDateTime() : m_rows.last().first().toDateTime();
}

static bool numeric(const QVariant &v, double *out)
{
    switch(v.typeId())
    {
    case QMetaType::Double: case QMetaType::Float: case QMetaType::Int: case QMetaType::UInt:
    case QMetaType::LongLong: case QMetaType::ULongLong:
        *out = v.toDouble();
        return std::isfinite(*out);
    default:
        return false;
    }
}

double KzTable::dataMin() const
{
    bool found = false;
    double best = 0;
    for(const QVariantList &row : m_rows)
    {
        for(qsizetype i = 1; i < row.size(); ++i)
        {
            double v;
            if(numeric(row.at(i), &v) && (!found || v < best))
            {
                best = v;
                found = true;
            }
        }
    }
    return best;
}

double KzTable::dataMax() const
{
    bool found = false;
    double best = 1;
    for(const QVariantList &row : m_rows)
    {
        for(qsizetype i = 1; i < row.size(); ++i)
        {
            double v;
            if(numeric(row.at(i), &v) && (!found || v > best))
            {
                best = v;
                found = true;
            }
        }
    }
    return best;
}
