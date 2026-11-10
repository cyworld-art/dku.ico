cmake_minimum_required(VERSION 3.22.1)
project(malnote_whisper LANGUAGES C CXX)

# 빌드 시 whisper.cpp 를 고정된 커밋(v1.9.5)으로 받아온다.
include(FetchContent)

set(CMAKE_BUILD_TYPE Release)
set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE              OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
# Snapdragon 8 Gen 3 (Cortex-X4/A720/A520): dotprod + fp16 + i8mm 지원
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)

FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG        d1be6fde11ac6e0407606b4e42fe72d34add8037 # v1.9.5
    GIT_SHALLOW    FALSE
)
FetchContent_MakeAvailable(whisper)

add_library(malnote_whisper SHARED jni.cpp)
target_compile_features(malnote_whisper PRIVATE cxx_std_17)
target_link_libraries(malnote_whisper PRIVATE whisper android log)
