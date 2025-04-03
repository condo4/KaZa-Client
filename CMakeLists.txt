cmake_minimum_required(VERSION 3.16)

project(KaZa VERSION 2.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(VERSION_CODE 240926)
add_definitions("-DVERSION_CODE=${VERSION_CODE}")
add_definitions("-DVERSION_NAME=${CMAKE_PROJECT_VERSION}")

find_package(Qt6 6.8 REQUIRED COMPONENTS Quick QuickControls2 Network Charts Xml WebView)

qt_standard_project_setup(REQUIRES 6.8)

if(ANDROID)
    set(PLATFORM_FILES
        android/AndroidManifest.xml
        android/src/org/kaza/LocalService.java
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
    PRIVATE Qt6::Quick Qt6::QuickControls2 Qt6::Network Qt6::Charts Qt6::Xml Qt6::CorePrivate Qt6::WebView
)

if (ANDROID)
    message(STATUS "Build for ANDROID")
    include(${ANDROID_SDK_ROOT}/ssl/CMakeLists.txt)
    set_property(TARGET KaZa APPEND PROPERTY QT_ANDROID_PACKAGE_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/android)
endif(ANDROID)

qt_finalize_executable(KaZa)

include(GNUInstallDirs)
install(TARGETS KaZa
    BUNDLE DESTINATION .
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
)
