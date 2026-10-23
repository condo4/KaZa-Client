#ifndef KZTABLE_H
#define KZTABLE_H

#include <QAbstractTableModel>
#include <QDateTime>
#include <QPointer>
#include <QVariantList>

class KzRequest;

// Table model over the result of a KzRequest (kind "table"), for TableView or the XYModelMapper of a
// graph. Columns are those returned by the service; a date column is exposed as QDateTime. Same
// helpers as KzEvolution (dataStart/End/Min/Max, formatDate) so existing graph pages port easily.
class KzTable : public QAbstractTableModel
{
    Q_OBJECT

    Q_PROPERTY(KzRequest *request READ request WRITE setRequest NOTIFY requestChanged FINAL)
    // Rows with a null in any column after the first are left out (a bucket without data must not draw a point at 0)
    Q_PROPERTY(bool skipNulls READ skipNulls WRITE setSkipNulls NOTIFY skipNullsChanged FINAL)
    Q_PROPERTY(QStringList columns READ columns NOTIFY columnsChanged FINAL)
    Q_PROPERTY(QDateTime dataStart READ dataStart NOTIFY dataChanged FINAL)
    Q_PROPERTY(QDateTime dataEnd READ dataEnd NOTIFY dataChanged FINAL)
    Q_PROPERTY(double dataMin READ dataMin NOTIFY dataChanged FINAL)
    Q_PROPERTY(double dataMax READ dataMax NOTIFY dataChanged FINAL)

public:
    explicit KzTable(QObject *parent = nullptr);

    int rowCount(const QModelIndex &parent = QModelIndex()) const override;
    int columnCount(const QModelIndex &parent = QModelIndex()) const override;
    QVariant data(const QModelIndex &index, int role = Qt::DisplayRole) const override;
    QVariant headerData(int section, Qt::Orientation orientation, int role = Qt::DisplayRole) const override;
    QHash<int, QByteArray> roleNames() const override;

    KzRequest *request() const { return m_request; }
    void setRequest(KzRequest *request);
    bool skipNulls() const { return m_skipNulls; }
    void setSkipNulls(bool skip);
    QStringList columns() const { return m_columns; }
    QDateTime dataStart() const;
    QDateTime dataEnd() const;
    double dataMin() const;
    double dataMax() const;

public slots:
    int columnIndex(const QString &name) const;
    QString formatDate(const QDateTime &date, const QString &format) const;

signals:
    void requestChanged();
    void skipNullsChanged();
    void columnsChanged();
    void dataChanged();

private slots:
    void _reload();

private:
    static QVariant normalized(const QVariant &cell);

    QPointer<KzRequest> m_request;
    bool m_skipNulls {false};
    QStringList m_columns;
    QList<QVariantList> m_rows;
};

#endif // KZTABLE_H
