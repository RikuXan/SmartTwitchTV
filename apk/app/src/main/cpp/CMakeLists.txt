cmake_minimum_required(VERSION 3.22)
project(signalsmithstretch CXX)

add_library(signalsmithstretch SHARED signalsmith_stretch_jni.cpp)
target_include_directories(signalsmithstretch PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
target_compile_features(signalsmithstretch PRIVATE cxx_std_17)
target_compile_options(signalsmithstretch PRIVATE -O3 -fvisibility=hidden)
