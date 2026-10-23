cmake_minimum_required(VERSION 3.16)
project(tst_kzrequest LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_AUTOMOC ON)

find_package(Qt6 REQUIRED COMPONENTS Core Network Qml Test)

set(SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../src)
set(PROTO ${CMAKE_CURRENT_SOURCE_DIR}/../../protocol)

add_executable(tst_kzrequest
    tst_kzrequest.cpp
    ${SRC}/kzrequest.cpp ${SRC}/kztable.cpp ${SRC}/kzservicelink.cpp
    ${PROTO}/kazaprotocol.cpp
)
target_include_directories(tst_kzrequest PRIVATE ${SRC} ${PROTO})
target_link_libraries(tst_kzrequest PRIVATE Qt6::Core Qt6::Network Qt6::Qml Qt6::Test)

enable_testing()
add_test(NAME tst_kzrequest COMMAND tst_kzrequest)

# Loads the real application page (MeteoGraph.qml) with a fake server; needs KAZA_METEOGRAPH_QML to run
find_package(Qt6 QUIET COMPONENTS Quick QuickControls2)
if(Qt6Quick_FOUND)
    add_executable(tst_meteograph
        tst_meteograph.cpp
        ${SRC}/kzrequest.cpp ${SRC}/kztable.cpp ${SRC}/kzservicelink.cpp
        ${PROTO}/kazaprotocol.cpp
    )
    target_include_directories(tst_meteograph PRIVATE ${SRC} ${PROTO})
    target_link_libraries(tst_meteograph PRIVATE Qt6::Core Qt6::Network Qt6::Qml Qt6::Quick Qt6::QuickControls2 Qt6::Test)
    add_test(NAME tst_meteograph COMMAND tst_meteograph)
endif()
