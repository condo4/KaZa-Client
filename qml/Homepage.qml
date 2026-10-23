import QtQuick

Rectangle {
    id: root

    gradient: Gradient {
        GradientStop { position: 0.0; color: "#1e3c72" }
        GradientStop { position: 1.0; color: "#2a5298" }
    }

    Column {
        anchors.centerIn: parent
        spacing: 24

        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            text: "KaZa"
            color: "white"
            font.pixelSize: 56
            font.bold: true
            font.letterSpacing: 4
        }

        // Spinning wheel: 12 dots with decreasing opacity
        Item {
            id: wheel
            width: 64
            height: 64
            anchors.horizontalCenter: parent.horizontalCenter

            RotationAnimator on rotation {
                from: 0
                to: 360
                duration: 1200
                loops: Animation.Infinite
                running: root.visible
            }

            Repeater {
                model: 12
                Rectangle {
                    width: 8
                    height: 8
                    radius: 4
                    color: "white"
                    opacity: (index + 1) / 12
                    x: wheel.width / 2 - width / 2 + Math.sin(index * Math.PI / 6) * 26
                    y: wheel.height / 2 - height / 2 - Math.cos(index * Math.PI / 6) * 26
                }
            }
        }

        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            text: manager.status
            color: "white"
            opacity: 0.8
            font.pixelSize: 16
            horizontalAlignment: Text.AlignHCenter
        }
    }
}
