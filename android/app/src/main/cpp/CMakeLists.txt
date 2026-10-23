cmake_minimum_required(VERSION 3.31.6)
project(bellpull_qwen LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
endif()
add_compile_definitions(GGML_MAX_NAME=128)
# Native inference must remain optimized in fixture/debug builds too.
add_compile_options(-O3)
get_filename_component(QWEN "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third-party/qwentts.cpp" ABSOLUTE)
# Only the inference library: no upstream CLI, HTTP server or extra dependencies.
add_subdirectory("${QWEN}/ggml" ggml)
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/version.h" "#pragma once\n#define QWEN_VERSION \"51512f129a7419567f4b8abfb06801451789b8f1\"\n")
add_library(bellpull_qwen SHARED qwen-jni.cpp "${QWEN}/src/qwen.cpp" "${QWEN}/src/pipeline-tts.cpp" "${QWEN}/src/pipeline-codec.cpp")
target_compile_definitions(bellpull_qwen PRIVATE QWEN_STATIC)
target_include_directories(bellpull_qwen PRIVATE "${QWEN}/src" "${CMAKE_CURRENT_BINARY_DIR}")
target_link_libraries(bellpull_qwen PRIVATE ggml ggml-base ggml-cpu)
# Isolate embedded GGML from any other native runtime and support 16 KiB pages.
target_link_options(bellpull_qwen PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,-z,max-page-size=16384")

# The only exported entry points belong to JNI.
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/exports.map" "{ global: Java_org_praxisog_hermes_QwenNative_*; local: *; };\n")
target_link_options(bellpull_qwen PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_BINARY_DIR}/exports.map")

# Pocket-only Sherpa source build. Never import the all-model AAR (eSpeak/piper).
include(FetchContent)
get_filename_component(SHERPA "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third-party/sherpa-onnx" ABSOLUTE)
# Isolate ONNX Runtime from Moonshine's differently versioned shared runtime.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(POCKET_ORT_HASH bc68410f35f13df82c260830bf476ee5565f5efd0ec7622297829a929613c6c0)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(POCKET_ORT_HASH f49dbf456ffe71dd417afb2123ef15202f8f57a4b2a05f12af1cf71f55448137)
else()
    message(FATAL_ERROR "Unsupported Pocket CPU ABI: ${ANDROID_ABI}")
endif()
FetchContent_Declare(pocket_ort
    URL "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-${ANDROID_ABI}-static_lib-1.28.2.zip"
    URL_HASH "SHA256=${POCKET_ORT_HASH}")
FetchContent_MakeAvailable(pocket_ort)
FetchContent_Declare(pocket_json
    URL "https://github.com/nlohmann/json/archive/refs/tags/v3.12.0.tar.gz"
    URL_HASH SHA256=4b92eb0c06d10683f7447ce9406cb97cd4b453be18d7279320f7b2f025c10187)
FetchContent_MakeAvailable(pocket_json)
find_package(Python3 REQUIRED COMPONENTS Interpreter)
set(POCKET_GENERATED "${CMAKE_CURRENT_BINARY_DIR}/pocket-adapter")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/prepare-pocket.py"
    "${SHERPA}/sherpa-onnx/csrc/offline-tts-pocket-impl.h"
    "${SHERPA}/sherpa-onnx/csrc/macros.h"
    "${SHERPA}/sherpa-onnx/csrc/offline-tts.cc")
execute_process(COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/prepare-pocket.py"
    "${SHERPA}" "${POCKET_GENERATED}" COMMAND_ERROR_IS_FATAL ANY)
file(GLOB POCKET_ORT_LIBS "${pocket_ort_SOURCE_DIR}/lib/lib*.a")
if(NOT POCKET_ORT_LIBS)
    message(FATAL_ERROR "Pinned Pocket ONNX Runtime static libraries missing")
endif()
set(POCKET_SOURCE "${SHERPA}/sherpa-onnx/csrc")
add_library(bellpull_pocket SHARED pocket-jni.cpp kokoro-jni.cpp
    "${POCKET_SOURCE}/offline-tts-pocket-model.cc"
    "${POCKET_SOURCE}/sentence-piece-tokenizer.cc"
    "${POCKET_SOURCE}/normal-data-generator.cc"
    "${POCKET_SOURCE}/onnx-utils.cc"
    "${POCKET_SOURCE}/text-utils.cc"
    "${POCKET_SOURCE}/file-utils.cc"
    "${POCKET_SOURCE}/resample.cc"
    "${POCKET_GENERATED}/pocket-generation.cc")
target_include_directories(bellpull_pocket PRIVATE "${POCKET_GENERATED}" "${SHERPA}" "${pocket_json_SOURCE_DIR}/include" "${pocket_ort_SOURCE_DIR}/include")
target_link_libraries(bellpull_pocket PRIVATE "-Wl,--start-group" ${POCKET_ORT_LIBS} "-Wl,--end-group" android log)
set(POCKET_EXPORTS "Java_org_praxisog_hermes_PocketNative_*; Java_org_praxisog_hermes_KokoroNative_*;")
if(BELLPULL_TTS_CANDIDATE_BENCHMARK)
    target_sources(bellpull_pocket PRIVATE candidate-tts-benchmark.cpp)
    string(APPEND POCKET_EXPORTS " Java_org_praxisog_hermes_CandidateTtsNative_*;")
endif()
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/pocket-exports.map" "{ global: ${POCKET_EXPORTS} local: *; };\n")
target_link_options(bellpull_pocket PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,-z,max-page-size=16384"
    "-Wl,--version-script=${CMAKE_CURRENT_BINARY_DIR}/pocket-exports.map")

# MOSS ships a binary SentencePiece BPE ModelProto with NFKC normalization.
# simple-sentencepiece reads a text unigram vocabulary and calls exit(-1) on this
# file. Use the offline ModelProto processor; no Android services are involved.
# License and bundled dependency notices are in assets/licenses/SentencePiece-*.
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(SPM_DISABLE_EMBEDDED_DATA ON CACHE BOOL "" FORCE)
FetchContent_Declare(sentencepiece
    URL "https://github.com/google/sentencepiece/archive/refs/tags/v0.2.1.tar.gz"
    URL_HASH SHA256=c1a59e9259c9653ad0ade653dadff074cd31f0a6ff2a11316f67bee4189a8f1b
    EXCLUDE_FROM_ALL)
FetchContent_MakeAvailable(sentencepiece)
set_target_properties(sentencepiece-static PROPERTIES POSITION_INDEPENDENT_CODE ON)
add_library(bellpull_moss SHARED moss-jni.cpp)
target_include_directories(bellpull_moss PRIVATE "${sentencepiece_SOURCE_DIR}/src")
target_link_libraries(bellpull_moss PRIVATE sentencepiece-static log)
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/moss-exports.map" "{ global: Java_org_praxisog_hermes_MossNative_*; local: *; };\n")
target_link_options(bellpull_moss PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,-z,max-page-size=16384"
    "-Wl,--version-script=${CMAKE_CURRENT_BINARY_DIR}/moss-exports.map")
