#ifndef KZEVOLUTION_H
#define KZEVOLUTION_H

#include <QAbstractTableModel>
#include <QObject>
#include <QDateTime>
#include <QList>

#define COL_DATE 0
#define COL_MIN 1
#define COL_AVG 2
#define COL_MAX 3

// QML counterpart of the server's FRAME_HISTORY/FRAME_HISTORYRESULT protocol.
// Same table-model column layout as KzHistory (COL_DATE/MIN/AVG/MAX) so it
// drops into the same XYModelMapper usage, but driven by domain/object/start/
// end/step properties instead of a raw SQL query string.
class KzEvolution : public QAbstractTableModel
{
    Q_OBJECT

    Q_PROPERTY(QString domain READ domain WRITE setDomain NOTIFY domainChanged FINAL)
    Q_PROPERTY(QString object READ object WRITE setObject NOTIFY objectChanged FINAL)
    Q_PROPERTY(QDateTime start READ start WRITE setStart NOTIFY startChanged FINAL)
    Q_PROPERTY(QDateTime end READ end WRITE setEnd NOTIFY endChanged FINAL)
    Q_PROPERTY(QString step READ step WRITE setStep NOTIFY stepChanged FINAL)

    Q_PROPERTY(QDateTime dataStart READ dataStart NOTIFY dataStartChanged)
    Q_PROPERTY(QDateTime dataEnd READ dataEnd NOTIFY dataEndChanged)
    Q_PROPERTY(double dataMin READ dataMin NOTIFY dataMinChanged)
    Q_PROPERTY(double dataMax READ dataMax NOTIFY dataMaxChanged)
    Q_PROPERTY(bool loading READ loading NOTIFY loadingChanged FINAL)
    Q_PROPERTY(QString error READ error NOTIFY errorChanged FINAL)

public:
    explicit KzEvolution(QObject *parent = nullptr);

    int rowCount(const QModelIndex &parent = QModelIndex()) const override;
    int columnCount(const QModelIndex &parent = QModelIndex()) const override;
    QVariant data(const QModelIndex &index, int role = Qt::DisplayRole) const override;

    QString domain() const;
    void setDomain(const QString &newDomain);
    QString object() const;
    void setObject(const QString &newObject);
    QDateTime start() const;
    void setStart(const QDateTime &newStart);
    QDateTime end() const;
    void setEnd(const QDateTime &newEnd);
    QString step() const;
    void setStep(const QString &newStep);

    QDateTime dataStart() const;
    QDateTime dataEnd() const;
    double dataMin() const;
    double dataMax() const;
    bool loading() const;
    QString error() const;

public slots:
    QString formatDate(QDateTime date, QString format) const;
    void refresh();

signals:
    void domainChanged();
    void objectChanged();
    void startChanged();
    void endChanged();
    void stepChanged();
    void dataStartChanged();
    void dataEndChanged();
    void dataMinChanged();
    void dataMaxChanged();
    void loadingChanged();
    void errorChanged();

private slots:
    void _historyResult(uint32_t id, bool ok, QString error, QList<QDateTime> ts,
                         QList<double> min, QList<double> avg, QList<double> max);

private:
    void _refresh();

    QString m_domain {"temperature"};
    QString m_object;
    QDateTime m_start;
    QDateTime m_end;
    QString m_step {"day"};

    QList<QDateTime> m_ts;
    QList<double> m_min;
    QList<double> m_avg;
    QList<double> m_max;

    uint32_t m_pendingId {0};
    bool m_loading {false};
    QString m_error;

    static uint32_t m_idMax;
};

#endif // KZEVOLUTION_H
