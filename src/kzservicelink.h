#ifndef KZSERVICELINK_H
#define KZSERVICELINK_H

#include <QObject>
#include <QPointer>
#include "kazaprotocol.h"

// Connection state seen by the service requests (KzRequest): which protocol to talk to, whether the
// session is up and whether the server announced the `services` capability (SYSTEM command
// "CAPS:services=1"). KazaApplicationManager feeds it; keeping it separate lets KzRequest be tested
// without a TLS session.
class KzServiceLink : public QObject
{
    Q_OBJECT

public:
    static KzServiceLink *instance();

    void attach(KaZaProtocol *protocol);
    KaZaProtocol *protocol() const { return m_protocol; }

    bool ready() const { return m_ready; }
    bool servicesAvailable() const { return m_services; }
    // Requests can be sent now
    bool usable() const { return m_protocol && m_ready && m_services; }

    void setReady(bool ready);
    void setServicesAvailable(bool available);
    // "CAPS:services=1,..." as sent by the server
    void handleCaps(const QString &command);

signals:
    void changed();
    void response(const KaZaProtocol::ServiceResponse &response);

private:
    explicit KzServiceLink(QObject *parent = nullptr);

    QPointer<KaZaProtocol> m_protocol;
    bool m_ready {false};
    bool m_services {false};
};

#endif // KZSERVICELINK_H
