#ifndef KAZAAPPLICATIONMANAGER_H
#define KAZAAPPLICATIONMANAGER_H

#include <QAbstractSocket>
#include <QObject>
#include <QSettings>
#include <QSslError>
#include <QSslSocket>
#include "kazaprotocol.h"

class QSslSocket;
class Packet;
class PacketManager;
class KaZaObject;

class KazaApplicationManager : public QObject
{
    Q_OBJECT

    Q_PROPERTY(bool configured READ configured WRITE setConfigured NOTIFY configuredChanged FINAL)
    Q_PROPERTY(bool connected READ connected NOTIFY connectedChanged FINAL)
    Q_PROPERTY(bool debug READ debug NOTIFY debugChanged FINAL)
    Q_PROPERTY(bool secured READ secured NOTIFY securedChanged FINAL)
    Q_PROPERTY(bool ready READ ready NOTIFY readyChanged FINAL)
    Q_PROPERTY(QString login READ login NOTIFY loginChanged FINAL)
    Q_PROPERTY(QString homepage READ homepage NOTIFY homepageChanged FINAL)
    Q_PROPERTY(bool show READ show WRITE setShow NOTIFY showChanged FINAL)
    Q_PROPERTY(QString errorMsg READ errorMsg WRITE setErrorMsg NOTIFY errorMsgChanged FINAL)

    QSettings m_settings;
    QSslSocket m_ssl;
    KaZaProtocol m_protocol;
    QList<KaZaObject *> m_kobjects;
    QString m_appChecksum;
    QString m_appFile;
    QString m_appWanted;

    bool m_debug {true};
    bool m_configured {false};
    bool m_inline;
    bool m_connected;
    QString m_homepage;
    bool m_secured {false};
    bool m_ready {false};
    bool m_show {false};
    QString m_host;
    uint16_t m_port;
    bool m_appStarted {false};

    static KazaApplicationManager *m_instance;

public:
    explicit KazaApplicationManager(QObject *parent = nullptr);
    virtual ~KazaApplicationManager();

    bool configured() const;
    void setConfigured(bool newConfigured);
    void connectClient();
    bool connected() const;
    QString homepage() const;
    static KaZaObject *getKaZaObject(const QString &name);
    static void putKaZaObject(KaZaObject * obj);
    static KaZaProtocol *protocol();
    bool debug() const;
    bool secured() const;
    bool ready() const;
    QString login() const;
    bool show() const;
    void setShow(bool newShow);
    QString errorMsg() const;
    void setErrorMsg(const QString &newErrorMsg);

public slots:
    bool setConfiguration(QString host, uint16_t port, QString clientPassword, QString username);
    void suspend();
    void resume();
    void applicationReday();

private slots:
    void _encrypted();
    void _startApplication();
    void _disconnected();
    void _sendObject(QVariant value);

signals:
    void configuredChanged();
    void connectedChanged(bool);
    void homepageChanged();
    void debugChanged();
    void securedChanged();
    void readyChanged();
    void loginChanged();
    void showChanged();
    void paramRecived(QString name);
    void error(QString msg);
    void applicationVersionsChanged();
    void currentApplicationChanged();
    void errorMsgChanged();

private:
    bool _tryConnectClient(const QString &clientCert, const QString &caCert, const QString &clientKey, const QString &clientPassword, const QString &host, uint16_t port);
    void __appStateChange(Qt::ApplicationState state);
    void _calculateAppChecksum();

    void _processFrameSystem(const QString &command);
    void _processFrameFile(const QString &fileid, QByteArray data);
    void _processFrameObjectValue(quint16 id, QVariant value);

    QStringList m_applicationVersions;
    QString m_currentApplication;
    QString m_errorMsg;
};



#endif // KAZAAPPLICATIONMANAGER_H
