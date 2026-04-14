cmake_minimum_required(VERSION 3.22.1)
project("deepskynative")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LIBRAW_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/LibRaw-0.22.0)

file(GLOB LIBRAW_SOURCES CONFIGURE_DEPENDS
    "${LIBRAW_ROOT}/src/*.cpp"
    "${LIBRAW_ROOT}/src/decoders/*.cpp"
    "${LIBRAW_ROOT}/src/decompressors/*.cpp"
    "${LIBRAW_ROOT}/src/demosaic/*.cpp"
    "${LIBRAW_ROOT}/src/integration/*.cpp"
    "${LIBRAW_ROOT}/src/metadata/*.cpp"
    "${LIBRAW_ROOT}/src/postprocessing/*.cpp"
    "${LIBRAW_ROOT}/src/preprocessing/*.cpp"
    "${LIBRAW_ROOT}/src/tables/*.cpp"
    "${LIBRAW_ROOT}/src/utils/*.cpp"
    "${LIBRAW_ROOT}/src/write/*.cpp"
    "${LIBRAW_ROOT}/src/x3f/*.cpp"
)

add_library(libraw STATIC ${LIBRAW_SOURCES})
target_compile_definitions(libraw PRIVATE LIBRAW_NOTHREADS)
target_include_directories(
    libraw
    PUBLIC
    ${LIBRAW_ROOT}
    ${LIBRAW_ROOT}/libraw
    ${LIBRAW_ROOT}/internal
)

add_library(
    deepskynative
    SHARED
    deepsky_raw_jni.cpp
)

target_include_directories(
    deepskynative
    PRIVATE
    ${LIBRAW_ROOT}
    ${LIBRAW_ROOT}/libraw
    ${LIBRAW_ROOT}/internal
)

target_link_libraries(
    deepskynative
    libraw
    log
)

add_library(
    usbnative
    SHARED
    usb_native.c
)

target_link_libraries(
    usbnative
    log
)
