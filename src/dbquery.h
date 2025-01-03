#ifndef DBQUERY_H
#define DBQUERY_H

#include <QObject>
#include <QVariant>

class DbQuery : public QObject
{
    Q_OBJECT

    QString m_query;
    uint32_t m_id;

    static uint32_t m_idmax;
    static QMap<uint32_t, DbQuery *> m_pending;
    QStringList m_columns;
    QList<QList<QVariant>> m_data;
    bool m_complete {false};

public:
    explicit DbQuery(const QString &query, QObject *parent = nullptr);
    uint32_t id() const;
    QString query() const;
    void exec();

    void setColumns(const QStringList &newColumns);
    void addRow(QList<QVariant> &row);
    void complete();
    QString title(int index);
    QVariant data(int row, int column);
    qsizetype rowCount();
    qsizetype columnCount();

private slots:
    void _dbResult(uint8_t id, const QStringList &culumns, const QList<QList<QVariant>> &result);

signals:
    void completed();
};



#endif // DBQUERY_H
