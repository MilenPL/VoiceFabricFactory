cmake_minimum_required(VERSION 3.22)
project(voiceforge C)

set(CMAKE_C_STANDARD 99)

add_library(voiceforge SHARED lame_jni.c)

target_include_directories(voiceforge PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/lame/include)

# Static LAME 3.100 built per-ABI with the NDK clang (see tools/onnx_export/
# README or the build log; sources: lame-3.100, --disable-frontend --disable-decoder).
set(LAME_LIB ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/libmp3lame.a)
if(NOT EXISTS ${LAME_LIB})
    message(FATAL_ERROR "Missing static LAME for ABI ${ANDROID_ABI}: ${LAME_LIB}")
endif()

target_link_libraries(voiceforge ${LAME_LIB} m)
