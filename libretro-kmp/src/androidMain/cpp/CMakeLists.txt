cmake_minimum_required(VERSION 3.22)
project(libretro_jni)

# Cores are opened at runtime with dlopen(): only libretro.h is needed at build time.
add_library(libretro_jni SHARED libretro_jni.c)
target_include_directories(libretro_jni PRIVATE ${CMAKE_SOURCE_DIR}/../../../../libs/include)

find_library(log-lib log)

target_link_libraries(libretro_jni ${log-lib} dl)
