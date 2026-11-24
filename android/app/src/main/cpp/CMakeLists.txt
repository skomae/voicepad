cmake_minimum_required(VERSION 3.22)

project("voicepad-llm" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

if(NOT DEFINED LLAMA_CPP_DIR)
    message(FATAL_ERROR "LLAMA_CPP_DIR must point at the llama.cpp source directory.")
endif()

# Per-ABI knobs, mirroring llama.cpp's examples/llama.android.
if(DEFINED ANDROID_ABI)
    message(STATUS "voicepad-llm: Android ABI ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

add_subdirectory(${LLAMA_CPP_DIR} build-llama)

add_library(voicepad_llm SHARED llama_jni.cpp)

target_include_directories(voicepad_llm PRIVATE
        ${LLAMA_CPP_DIR}
        ${LLAMA_CPP_DIR}/common
        ${LLAMA_CPP_DIR}/include
        ${LLAMA_CPP_DIR}/ggml/include
        ${LLAMA_CPP_DIR}/ggml/src)

target_link_libraries(voicepad_llm
        llama
        llama-common
        android
        log)
