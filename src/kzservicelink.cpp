#include "kzservicelink.h"

KzServiceLink::KzServiceLink(QObject *parent)
    : QObject{parent}
{
}

KzServiceLink *KzServiceLink::instance()
{
    static KzServiceLink *link = new KzServiceLink;
    return link;
}

void KzServiceLink::attach(KaZaProtocol *protocol)
{
    if(m_protocol)
        QObject::disconnect(m_protocol, &KaZaProtocol::frameServiceResponse, this, &KzServiceLink::response);
    m_protocol = protocol;
    if(m_protocol)
        QObject::connect(m_protocol, &KaZaProtocol::frameServiceResponse, this, &KzServiceLink::response);
    emit changed();
}

void KzServiceLink::setReady(bool ready)
{
    if(m_ready == ready)
        return;
    m_ready = ready;
    emit changed();
}

void KzServiceLink::setServicesAvailable(bool available)
{
    if(m_services == available)
        return;
    m_services = available;
    emit changed();
}

void KzServiceLink::handleCaps(const QString &command)
{
    // "CAPS:services=1,other=2"
    bool services = false;
    const QStringList parts = command.mid(5).split(',', Qt::SkipEmptyParts);
    for(const QString &p : parts)
    {
        if(p.trimmed() == QLatin1String("services=1"))
            services = true;
    }
    setServicesAvailable(services);
}
