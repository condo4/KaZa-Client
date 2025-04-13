#include "kazaobject.h"


KaZaObject::KaZaObject(QObject *parent)
    : QObject{parent}
{}

quint32 KaZaObject::refcount() const
{
    return m_refcount;
}

QString KaZaObject::name() const {
    return m_name;
}

void KaZaObject::setName(const QString &newName) {
    if(m_name != newName) {
        m_name = newName;
        emit nameChanged();
    }
}

QString KaZaObject::unit() const {
    return m_unit;
}

void KaZaObject::setUnit(const QString &newUnit) {
    if(m_unit != newUnit) {
        m_unit = newUnit;
        emit unitChanged();
    }
}

QVariant KaZaObject::value() const {
    return m_value;
}

void KaZaObject::setValue(QVariant newValue) {
    if(m_value != newValue)
    {
        m_value = newValue;
        emit valueChanged();
    }
}

void KaZaObject::changeRequest(QVariant newValue, bool confirm) {
    emit changeRequested(newValue, confirm);
}

void KaZaObject::refresh() {
    emit changeRequested(QVariant(), false);
}

void KaZaObject::put()
{
    m_refcount -= 1;
}

void KaZaObject::get()
{
    if(m_refcount > 1)
        emit valueChanged();
    m_refcount += 1;
}
