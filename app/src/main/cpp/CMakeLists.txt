cmake_minimum_required(VERSION 3.22)
project(qimeng_spng)

# libspng 静态库（本地源码，v0.7.4，MIT 协议）
# 使用 Android NDK 自带的 zlib（无需额外下载 miniz）
add_library(spng_static STATIC spng/spng.c)
target_include_directories(spng_static PUBLIC spng)
# Android NDK 的 zlib 库名是 z，不是 zlib
target_link_libraries(spng_static z)
# ARM NEON 优化（Android NDK 自动处理）
if(ANDROID_ABI STREQUAL "armeabi-v7a" OR ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(spng_static PRIVATE SPNG_ARM_NEON)
endif()

# JNI 桥接库
add_library(spng_jni SHARED spng_jni.c)
target_link_libraries(spng_jni spng_static log)
target_include_directories(spng_jni PRIVATE spng)

# 设置 C 标准
set_target_properties(spng_static spng_jni PROPERTIES C_STANDARD 11)
