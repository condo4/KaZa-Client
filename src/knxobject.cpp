#include "knxobject.h"
#include "KazaApplicationManager.h"
#include <knxutils.h>


uint16_t KnxObject::gad() const
{
    return m_gad;
}

KnxObject::KnxObject(QObject *parent)
    : QObject{parent}
{
}

KnxObject::~KnxObject()
{
    if(m_registered)
    {
        qWarning() << "DELETE not DESTROY: " << m_object;
        m_registered = false;
    }
}

QString KnxObject::object() const
{
    return m_object;
}

void KnxObject::setObject(const QString &newObject)
{
    if (m_object == newObject)
        return;
    m_object = newObject;
    KazaApplicationManager::registerKnxObject(this);
    m_registered = true;
}

QVariant KnxObject::value() const
{
    return m_value;
}

QString KnxObject::unit() const
{
    return m_unit;
}

uint16_t KnxObject::type() const
{
    return m_type;
}

void KnxObject::setKnxProperties(uint16_t gad, uint16_t type)
{
    m_gad = gad;
    m_type = type;
    m_unit = getUnit(type);
    emit unitChanged();
}

void KnxObject::knxWrite(uint16_t gad, QVariant value)
{
    if(m_gad == gad && m_value != value)
    {
        m_value = value;
        emit valueChanged();
    }
}

void KnxObject::unregister()
{
    if(m_registered)
    {
        m_registered = false;
        KazaApplicationManager::unregisterKnxObject(this);
    }
}

void KnxObject::write(QVariant value)
{
    uint8_t cmd = KNX_WRITE;
    QByteArray frame;
    frame.append(cmd >> 2);
    frame.append((cmd & 0x3) << 6);
    knxEncode(m_type, value, frame);
    KazaApplicationManager::sendTelegram(0, m_gad, frame);
}
