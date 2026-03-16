cmake_minimum_required(VERSION 3.22.1)
project(inference)

set(CMAKE_CXX_STANDARD 17)

# Build whisper.cpp and llama.cpp in separate scopes to avoid ggml conflicts.
# We use ExternalProject or simply build them one at a time.
# Since they both define a "ggml" target, we must rename one.

# ===== llama.cpp (build first, has newer ggml) =====
set(LLAMA_DIR ${CMAKE_SOURCE_DIR}/llama.cpp)
if(EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    set(GGML_SHARED OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_SHARED OFF CACHE BOOL "" FORCE)
    add_subdirectory(${LLAMA_DIR} llama_build)
    set(LLAMA_AVAILABLE ON)
else()
    set(LLAMA_AVAILABLE OFF)
endif()

# ===== whisper.cpp =====
# Whisper also uses ggml but an older version.
# Since llama.cpp already defined the ggml target, we skip whisper's ggml
# and build only the whisper library, pointing it at llama's ggml.
set(WHISPER_DIR ${CMAKE_SOURCE_DIR}/whisper.cpp)
if(EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    # Don't add whisper as subdirectory (ggml conflict).
    # Instead, build whisper source files directly.
    file(GLOB WHISPER_SOURCES ${WHISPER_DIR}/src/*.cpp)
    add_library(whisper_lib STATIC ${WHISPER_SOURCES})
    target_compile_definitions(whisper_lib PRIVATE WHISPER_VERSION="1.0.0")
    target_include_directories(whisper_lib PUBLIC
        ${WHISPER_DIR}/include
        ${WHISPER_DIR}/ggml/include
    )
    # Link against llama's ggml (newer, superset)
    target_link_libraries(whisper_lib ggml ggml-base ggml-cpu)
    set(WHISPER_AVAILABLE ON)
else()
    set(WHISPER_AVAILABLE OFF)
endif()

# ===== JNI Bridge =====
add_library(inference SHARED inference_jni.cpp)

target_link_libraries(inference android log)

if(WHISPER_AVAILABLE)
    target_link_libraries(inference whisper_lib)
    target_compile_definitions(inference PRIVATE HAS_WHISPER)
    target_include_directories(inference PRIVATE ${WHISPER_DIR}/include)
endif()

if(LLAMA_AVAILABLE)
    target_link_libraries(inference llama)
    target_compile_definitions(inference PRIVATE HAS_LLAMA)
    target_include_directories(inference PRIVATE ${LLAMA_DIR}/include)
endif()
