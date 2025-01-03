#ifndef HISTORY_H
#define HISTORY_H

#include <QAbstractTableModel>
#include <QObject>
#include <QList>
#include <QDateTime>
#include <QPointF>

#define COL_DATE 0
#define COL_MIN 1
#define COL_AVG 2
#define COL_MAX 3

class DbQuery;

class KzHistory : public QAbstractTableModel
{
    Q_OBJECT

    void _refresh();

public:
    Q_PROPERTY(QDateTime dataStart READ dataStart NOTIFY dataStartChanged)
    Q_PROPERTY(QDateTime dataEnd READ dataEnd NOTIFY dataEndChanged)
    Q_PROPERTY(QDateTime selected READ selected WRITE setSelected NOTIFY selectedChanged)
    Q_PROPERTY(int dataMin READ dataMin NOTIFY dataMinChanged)
    Q_PROPERTY(int dataMax READ dataMax NOTIFY dataMaxChanged)
    Q_PROPERTY(int len READ columnCount NOTIFY lenChanged)

    Q_PROPERTY(QString query READ query WRITE setQuery NOTIFY queryChanged FINAL)
    Q_PROPERTY(QString table READ table NOTIFY tableChanged FINAL)

    explicit KzHistory(QObject *parent = nullptr);

    int rowCount(const QModelIndex &parent = QModelIndex()) const override;
    int columnCount(const QModelIndex &parent = QModelIndex()) const override;
    QVariant headerData(int section, Qt::Orientation orientation, int role = Qt::DisplayRole) const override;
    QVariant data(const QModelIndex &index, int role = Qt::DisplayRole) const override;

    const QString object() const;
    void setObject(const QString &newObject);

    const QDateTime dataStart() const;
    const QDateTime dataEnd() const;
    int dataMin() const;
    int dataMax() const;


    const QDateTime &selected() const;
    void setSelected(const QDateTime &newSelected);

    QString query() const;
    void setQuery(const QString &newQuery);
    QString table() const;

signals:
    void serieChanged();
    void dataStartChanged();
    void dataEndChanged();
    void dataMinChanged();
    void dataMaxChanged();
    void selectedChanged();
    void queryChanged();
    void lenChanged();
    void tableChanged();

private:
    QDateTime m_selected;
    QString m_table;
    QString m_query;
    DbQuery *m_dbquery {nullptr};
    int m_datamin;
    int m_datamax;

private slots:
    void _dataCompleted();

public slots:
    QString formatDate(QDateTime date, QString format) const;
};

#endif // HISTORY_H
