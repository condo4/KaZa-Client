#ifndef KAZAOBJECT_H
#define KAZAOBJECT_H

#include <QObject>
#include <QVariant>

class KzObject;

class KaZaObject : public QObject
{
    Q_OBJECT
    QList<KzObject *> m_linked;
    mutable quint32 m_refcount {0};

public:
    explicit KaZaObject(QObject *parent = nullptr);

    QVariant value() const;
    void setValue(QVariant newValue);

    QString name() const;
    void setName(const QString &newName);

    QString unit() const;
    void setUnit(const QString &newName);

    void kzRegister(KzObject* kzobject);
    void kzUnregister(KzObject* kzobject);

    void changeRequest(QVariant newValue);
    void refresh();

    void put();
    void get();

signals:
    void valueChanged();
    void nameChanged();
    void unitChanged();
    void changeRequested(QVariant value);

private:
    QString m_name;
    QString m_unit;
    QVariant m_value;
};

#endif // KAZAOBJECT_H
