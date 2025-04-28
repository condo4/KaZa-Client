#ifndef KAZABACKEND_H
#define KAZABACKEND_H

#include <QObject>

class KaZaBackend : public QObject
{
    Q_OBJECT

public:
    explicit KaZaBackend(QObject *parent = nullptr);
    virtual ~KaZaBackend();

    static void configure(QString clientCert, QString caCert, QString clientKey, QString clientPassword,  QString host, QString user, uint16_t port);

public slots:
    void start();

signals:



};

#endif // KAZABACKEND_H
