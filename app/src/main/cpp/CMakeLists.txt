# v2.16.0 拍照搜题 OCR：MNN 推理胶水层。
# libMNN.so / libc++_shared.so 来自 MNN 3.6.1 官方 android SDK（vendor 在 jniLibs/，
# arm64-v8a 已 16KB 对齐），本模块只编译薄封装 libdroneocr.so，STL 用静态链接避免重复打包。
cmake_minimum_required(VERSION 3.22)
project(droneocr CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libMNN.so)

add_library(droneocr SHARED ocr_mnn.cpp)

target_include_directories(droneocr PRIVATE ${CMAKE_SOURCE_DIR})
target_link_libraries(droneocr MNN log android)

# 新设备 16KB 页面要求：显式按 16KB 对齐链接
target_link_options(droneocr PRIVATE "-Wl,-z,max-page-size=16384")
