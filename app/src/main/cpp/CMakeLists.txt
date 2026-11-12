cmake_minimum_required(VERSION 3.31.6)

project("trpg_llama" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# AGP passes -DCMAKE_BUILD_TYPE=Debug for the debug variant. Force RelWithDebInfo
# before llama.cpp/ggml are added so the debug APK still gets optimized native code
# (-O2 -g -DNDEBUG) while keeping symbols. The Gradle argument requests the same
# value; this guard makes the result independent of AGP argument ordering.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(CMAKE_BUILD_TYPE RelWithDebInfo CACHE STRING "Build type" FORCE)
endif()

message(STATUS "TRPG native build type: ${CMAKE_BUILD_TYPE}")
message(STATUS "TRPG native RelWithDebInfo C flags: ${CMAKE_C_FLAGS_RELWITHDEBINFO}")

# llama.cpp is fetched by scripts/fetch-llama.sh and pinned to v0.9.0.
set(LLAMA_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp")
if(NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp not found at ${LLAMA_SRC}. Run scripts/fetch-llama.sh before building.")
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
    set(GGML_CPU_KLEIDIAI ON)
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}. This app currently ships arm64-v8a only.")
endif()

add_subdirectory(${LLAMA_SRC} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

# llama-common calls llama_* APIs but upstream only declares that dependency at
# the final executable. Declare it here so libllama-common.so gets a DT_NEEDED
# entry on libllama.so and its symbols are guaranteed to resolve at runtime.
if(TARGET llama-common AND TARGET llama)
    target_link_libraries(llama-common PUBLIC llama)
endif()

if(NOT DEFINED TRPG_THREAD_PROFILE)
    set(TRPG_THREAD_PROFILE "A")
endif()
if(TRPG_THREAD_PROFILE STREQUAL "A")
    set(TRPG_THREAD_PROFILE_VALUE 0)
elseif(TRPG_THREAD_PROFILE STREQUAL "B")
    set(TRPG_THREAD_PROFILE_VALUE 1)
else()
    set(TRPG_THREAD_PROFILE_VALUE 2)
endif()

add_library(trpg_llama SHARED
    llama_jni.cpp
    debug_jni.cpp   # DEBUG MODE (temporary)
    utf8_utils.cpp
)

target_compile_definitions(trpg_llama PRIVATE
    GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
    GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
    TRPG_THREAD_PROFILE=${TRPG_THREAD_PROFILE_VALUE}
)

target_include_directories(trpg_llama PRIVATE
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
    ${LLAMA_SRC}/common
)

target_link_libraries(trpg_llama
    llama
    llama-common
    android
    log
)
