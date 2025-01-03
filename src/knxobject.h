#ifndef KNXOBJECT_H
#define KNXOBJECT_H

#include <QObject>
#include <QVariant>

class KnxObject : public QObject
{
    Q_OBJECT

    Q_PROPERTY(QString object READ object WRITE setObject NOTIFY objectChanged FINAL)
    Q_PROPERTY(QVariant value READ value NOTIFY valueChanged)
    Q_PROPERTY(QString unit READ unit NOTIFY unitChanged)

    QString m_object;
    uint16_t m_gad {0};
    uint16_t m_type {0};
    QString m_unit;
    QVariant m_value;
    bool m_registered {false};

public:
    explicit KnxObject(QObject *parent = nullptr);
    virtual ~KnxObject();

    void setObject(const QString &newObject);
    QString object() const;
    QVariant value() const;
    QString unit() const;

    void setKnxProperties(uint16_t gad, uint16_t type);

    uint16_t type() const;
    uint16_t gad() const;

public slots:
    void knxWrite(uint16_t gad, QVariant value);
    void unregister();
    void write(QVariant value);

signals:
    void objectChanged();
    void valueChanged();
    void unitChanged();

};

#endif // KNXOBJECT_H
