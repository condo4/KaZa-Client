import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Controls.Material
import QtCore

Page {
    id: page
    title: qsTr("Configuration")

    Material.theme: Material.Light
    Material.accent: Material.Blue

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 20
        spacing: 20

        Settings {
            category: "configuration"
            property alias username: username.text
            property alias host: host.text
            property alias port: port.text
            property alias clientPassword: clientPassword.text
        }

        GridLayout {
            columns: 2
            rowSpacing: 10
            columnSpacing: 10
            Layout.fillWidth: true

            Label { text: qsTr("User:") }
            TextField {
                id: username
                Layout.fillWidth: true
                placeholderText: qsTr("Enter username")
            }

            Label { text: qsTr("Host:") }
            TextField {
                id: host
                Layout.fillWidth: true
                placeholderText: qsTr("Enter host")
            }

            Label { text: qsTr("Port:") }
            TextField {
                id: port
                Layout.fillWidth: true
                placeholderText: qsTr("Enter port")
                validator: IntValidator {bottom: 1024; top: 65540}
            }

            Label { text: qsTr("Client Key Password:") }
            TextField {
                id: clientPassword
                Layout.fillWidth: true
                placeholderText: qsTr("Enter password")
                echoMode: TextInput.Password
            }
        }

        RowLayout {
            Layout.alignment: Qt.AlignRight
            spacing: 10

            Button {
                text: qsTr("Cancel")
                onClicked: {
                    // Add cancel logic here
                }
            }

            Button {
                text: qsTr("OK")
                onClicked: {
                    manager.setConfiguration(host.text, port.text, clientPassword.text, username.text)
                }
            }
        }

        RowLayout {
            Label {
                id: errorMsg
                color: "red"
                visible: text.length > 0
                text: (knxiface) ? (knxiface.errorMsg) : ("")
            }
        }
    }
}
