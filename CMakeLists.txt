cmake_minimum_required(VERSION 3.16)

project(KaZa VERSION 2.5.2 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(QT_NO_PRIVATE_MODULE_WARNING ON)

set(VERSION_CODE 2512206)
add_definitions("-DVERSION_CODE=${VERSION_CODE}")
add_definitions("-DVERSION_NAME=${CMAKE_PROJECT_VERSION}")

find_package(Qt6 REQUIRED COMPONENTS CorePrivate Quick QuickControls2 Network Charts Xml WebView Positioning Location)

qt_standard_project_setup(REQUIRES 6.8)

if(ANDROID)
    set(PLATFORM_FILES
        android/AndroidManifest.xml
        android/build.gradle
        android/res/values/libs.xml
        android/res/xml/qtprovider_paths.xml
        android/gradle/wrapper/gradle-wrapper.jar
        android/gradle/wrapper/gradle-wrapper.properties
        android/gradle.properties android/gradlew
        android/gradlew.bat
        android/src/org/kaza/NotificationService.java
        android/src/org/kaza/BootReceiver.java
        android/src/org/kaza/KaZaProtocol.java
        android/src/org/kaza/KaZaTracker.java
        android/src/org/kaza/KaZaDeviceStateMonitor.java
    )
    include_directories("android")
endif(ANDROID)

qt_add_executable(KaZa
    MANUAL_FINALIZATION
    src/main.cpp
    src/KazaApplicationManager.h	src/KazaApplicationManager.cpp
    src/kzobject.h                  src/kzobject.cpp
    src/kzhistory.h                 src/kzhistory.cpp
    src/kazaobject.h                src/kazaobject.cpp
    src/dbquery.h                   src/dbquery.cpp
    src/kzport.h                    src/kzport.cpp
    protocol/kazaprotocol.h         protocol/kazaprotocol.cpp
    ${PLATFORM_FILES}
    src/kazanotificationchecker.h src/kazanotificationchecker.cpp
    src/kazabackend.h src/kazabackend.cpp
)

qt_add_resources(KaZa "KaZa"
    PREFIX "/"
    FILES
        qml/Main.qml
        qml/Configuration.qml
        qml/Homepage.qml
        qml/ForceInstall.qml
        qml/KazaSettings.qml
)

target_include_directories(KaZa PUBLIC protocol/)

target_link_libraries(KaZa
    PRIVATE Qt6::Quick Qt6::QuickControls2 Qt6::Network Qt6::Charts Qt6::Xml Qt6::CorePrivate Qt6::WebView Qt6::Positioning Qt6::Location
)

if (ANDROID)
    message(STATUS "Build for ANDROID")

    # Include OpenSSL libraries for Android
    include(/mnt/Work/SDK/Android/android_openssl/CMakeLists.txt)

    # Explicitly set OpenSSL libraries as extra libs for Qt 6
    set_property(TARGET KaZa APPEND PROPERTY QT_ANDROID_EXTRA_LIBS
        /mnt/Work/SDK/Android/android_openssl/ssl_3/${CMAKE_ANDROID_ARCH_ABI}/libcrypto_3.so
        /mnt/Work/SDK/Android/android_openssl/ssl_3/${CMAKE_ANDROID_ARCH_ABI}/libssl_3.so
    )

    set_property(TARGET KaZa APPEND PROPERTY QT_ANDROID_PACKAGE_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/android)

    # Disable automatic icon generation by Qt (we have custom icons in android/res)
    set_property(TARGET KaZa APPEND PROPERTY QT_ANDROID_NO_DEFAULT_ICON TRUE)
endif(ANDROID)

qt_finalize_executable(KaZa)

include(GNUInstallDirs)
install(TARGETS KaZa
    BUNDLE DESTINATION .
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
)
