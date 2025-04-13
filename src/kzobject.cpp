#include "kzobject.h"
#include "KazaApplicationManager.h"
#include "kazaobject.h"

KzObject::KzObject(QObject *parent)
    : QObject{parent}
{}

KzObject::~KzObject()
{
    if(m_kazaobj)
    {
        KazaApplicationManager::putKaZaObject(m_kazaobj);
        m_kazaobj = nullptr;
    }
}

QString KzObject::object() const
{
    if(!m_kazaobj) return QString();
    return m_kazaobj->name();
}

void KzObject::setObject(const QString &newObject)
{
    if (m_object == newObject)
        return;
    m_object = newObject;
    m_kazaobj = KazaApplicationManager::getKaZaObject(newObject);
    QObject::connect(m_kazaobj, &KaZaObject::unitChanged, this, &KzObject::unitChanged);
    QObject::connect(m_kazaobj, &KaZaObject::valueChanged, this, &KzObject::valueChanged);
    QObject::connect(this, &KzObject::changeRequested, m_kazaobj, &KaZaObject::changeRequest);
    emit valueChanged();
}

QVariant KzObject::value() const
{
    if(!m_kazaobj) return QVariant();
    return m_kazaobj->value();
}

QString KzObject::unit() const
{
    if(!m_kazaobj) return QString();
    return m_kazaobj->unit();
}


void KzObject::set(const QVariant &newValue, bool confirm) {
    emit changeRequested(newValue, confirm);
}

void KzObject::refresh()
{
    if(!m_kazaobj) return;
    return m_kazaobj->refresh();
}
