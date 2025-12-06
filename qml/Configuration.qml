import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Controls.Material

Page {
    id: page
    title: qsTr("Configuration")

    Material.theme: Material.Light
    Material.accent: Material.Blue

    ScrollView {
        anchors.fill: parent
        contentWidth: availableWidth

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 20
            spacing: 20

            // Welcome header
            Label {
                text: "<h2>Welcome to KaZa Client</h2>" +
                      "<p>Please configure the connection to your KaZa server.</p>"
                textFormat: Text.RichText
                Layout.fillWidth: true
                wrapMode: Text.WordWrap
            }

            // Control Server Configuration group
            GroupBox {
                title: qsTr("Server Configuration")
                Layout.fillWidth: true

                GridLayout {
                    columns: 2
                    rowSpacing: 10
                    columnSpacing: 10
                    anchors.fill: parent

                    Label { text: qsTr("Server Host:") }
                    TextField {
                        id: controlHost
                        Layout.fillWidth: true
                        placeholderText: qsTr("e.g., 192.168.1.100")
                    }

                    Label { text: qsTr("Control Port:") }
                    TextField {
                        id: controlPort
                        Layout.fillWidth: true
                        placeholderText: qsTr("e.g., 43500")
                        text: "43500"
                        validator: IntValidator {
                            bottom: 1
                            top: 65535
                        }
                        inputMethodHints: Qt.ImhDigitsOnly
                    }

                    Label { text: qsTr("Admin Password:") }
                    TextField {
                        id: adminPassword
                        Layout.fillWidth: true
                        placeholderText: qsTr("Admin password from kazad.conf")
                        echoMode: TextInput.Password
                    }

                    Label { text: qsTr("Username:") }
                    TextField {
                        id: username
                        Layout.fillWidth: true
                        placeholderText: qsTr("e.g., john")
                    }

                    Label { text: qsTr("User Password:") }
                    TextField {
                        id: userPassword
                        Layout.fillWidth: true
                        placeholderText: qsTr("Password to encrypt your client key")
                        echoMode: TextInput.Password
                    }

                    Label { text: "" }
                    Button {
                        text: qsTr("Connect")
                        Layout.fillWidth: true
                        highlighted: true
                        enabled: !fetchingConfig
                        onClicked: {
                            fetchConfiguration()
                        }
                    }
                }
            }

            // Error message display
            Label {
                id: errorLabel
                color: "red"
                visible: text.length > 0
                text: manager ? manager.errorMsg : ""
                Layout.fillWidth: true
                wrapMode: Text.WordWrap
            }

            // Success message display
            Label {
                id: successLabel
                color: "green"
                visible: text.length > 0
                text: ""
                Layout.fillWidth: true
                wrapMode: Text.WordWrap
            }

            Item {
                Layout.fillHeight: true
            }
        }
    }

    // Properties to track state
    property bool fetchingConfig: false
    property bool configurationFetched: false

    function fetchConfiguration() {
        // Validate inputs
        if (controlHost.text.length === 0) {
            errorLabel.text = "Please enter the server host."
            controlHost.forceActiveFocus()
            return
        }

        if (adminPassword.text.length === 0) {
            errorLabel.text = "Please enter the admin password."
            adminPassword.forceActiveFocus()
            return
        }

        if (username.text.length === 0) {
            errorLabel.text = "Please enter a username."
            username.forceActiveFocus()
            return
        }

        if (userPassword.text.length === 0) {
            errorLabel.text = "Please enter a user password to encrypt your client key."
            userPassword.forceActiveFocus()
            return
        }

        // Clear previous messages
        errorLabel.text = ""
        successLabel.text = ""

        // Set fetching state
        fetchingConfig = true
        configurationFetched = false

        // Call backend to fetch configuration
        var success = manager.setConfiguration(
            controlHost.text,
            parseInt(controlPort.text),
            adminPassword.text,
            username.text,
            userPassword.text
        )

        fetchingConfig = false

        if (success) {
            configurationFetched = true
            successLabel.text = "Configuration retrieved successfully!\n" +
                              "You can now click OK to save and connect."

            // The manager already saved the configuration and connected
            // We could optionally navigate away or show a success message
        } else {
            configurationFetched = false
            errorLabel.text = manager.errorMsg || "Failed to fetch configuration from server."
        }
    }
}
