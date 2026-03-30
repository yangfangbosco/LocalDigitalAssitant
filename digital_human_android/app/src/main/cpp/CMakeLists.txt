cmake_minimum_required(VERSION 3.22.1)
project(inference)

set(CMAKE_CXX_STANDARD 17)

# ===== whisper.cpp (with its own ggml) =====
set(WHISPER_DIR ${CMAKE_SOURCE_DIR}/whisper.cpp)
if(EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    add_subdirectory(${WHISPER_DIR} whisper_build)
    set(WHISPER_AVAILABLE ON)
else()
    set(WHISPER_AVAILABLE OFF)
    message(WARNING "whisper.cpp not found at ${WHISPER_DIR}")
endif()

# ===== JNI Bridge (ASR only) =====
add_library(inference SHARED inference_jni.cpp)

target_link_libraries(inference android log)

if(WHISPER_AVAILABLE)
    target_link_libraries(inference whisper)
    target_compile_definitions(inference PRIVATE HAS_WHISPER)
    target_include_directories(inference PRIVATE ${WHISPER_DIR}/include)
endif()
