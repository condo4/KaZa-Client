import QtQuick
import QtQuick.Window
import QtQuick.Controls.Material
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

    // Qt >= 6.12 no longer sends a close request on Android Back: if nobody accepts the key it
    // just moves the task to the background. Accept it here and go through onClosing, so the
    // client application's callBackClose (stack pop, or let the window close) still applies.
    Shortcut {
        sequences: ["Back"]
        context: Qt.ApplicationShortcut
        onActivated: mainwindow.close()
    }

    onClosing: function(close) {
        if(mainwindow.callBackClose)
            mainwindow.callBackClose(close)
    }
}
