cmake_minimum_required(VERSION 3.16)

project(KaZa VERSION 2.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(VERSION_CODE 240926)
add_definitions("-DVERSION_CODE=${VERSION_CODE}")
add_definitions("-DVERSION_NAME=${CMAKE_PROJECT_VERSION}")

find_package(Qt6 6.5 REQUIRED COMPONENTS Quick QuickControls2 Network Charts Xml)

qt_standard_project_setup(REQUIRES 6.5)

qt_add_executable(appKaZa
    src/main.cpp
    src/KazaApplicationManager.h	src/KazaApplicationManager.cpp
    src/kzobject.h                  src/kzobject.cpp
    src/kzhistory.h                 src/kzhistory.cpp
    src/kazaobject.h                src/kazaobject.cpp
    src/dbquery.h                   src/dbquery.cpp
    protocol/kazaprotocol.h         protocol/kazaprotocol.cpp
)

file(GLOB_RECURSE AndroidFiles "${CMAKE_CURRENT_SOURCE_DIR}/Android")

set_source_files_properties(
  ${AndroidFiles}
  PROPERTIES
  HEADER_FILE_ONLY TRUE
)

qt_add_resources(appKaZa "KaZa"
    PREFIX "/"
    FILES
        qml/Main.qml
        qml/Configuration.qml
        qml/Homepage.qml
        qml/ForceInstall.qml
        qml/KazaSettings.qml
)

target_include_directories(appKaZa PUBLIC protocol/)

target_link_libraries(appKaZa
    PRIVATE Qt6::Quick Qt6::QuickControls2 Qt6::Network Qt6::Charts Qt6::Xml Qt6::CorePrivate
)

if (ANDROID)
    message(STATUS "Build for ANDROID")
    set(PROJECT_NAME appKaZa)
    include(${ANDROID_SDK_ROOT}/ssl/CMakeLists.txt)
    add_custom_target(androidfiles SOURCES ${AndroidFiles})
    set_property(TARGET appKaZa APPEND PROPERTY QT_ANDROID_PACKAGE_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/android)
endif(ANDROID)


include(GNUInstallDirs)
install(TARGETS appKaZa
    BUNDLE DESTINATION .
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
)
