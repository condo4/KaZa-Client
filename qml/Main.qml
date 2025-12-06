import QtQuick
import QtQuick.Window
import QtQuick.Controls
import QtWebView

ApplicationWindow {
    id: mainwindow
    width: 1080/2.5
    height: 2030/2.5
    visible: true
    title: qsTr("KaZa")

    property var callBackClose

    property bool configured: manager ? manager.configured : false
    property string homepage: (manager && manager.show) ? manager.homepage : "Homepage.qml"

    Component.onCompleted: {
        manager.applicationReday()
    }

    StackView {
        id: stackView
        anchors.fill: parent

        initialItem: Loader {
            id: homepage
            source: mainwindow.configured ? mainwindow.homepage  : "Configuration.qml"
        }
    }

    onClosing: function(close) {
        if(mainwindow.callBackClose)
            mainwindow.callBackClose(close)
    }
}
