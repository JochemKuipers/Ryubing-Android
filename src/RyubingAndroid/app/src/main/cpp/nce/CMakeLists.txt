# libryubing-nce: Native Code Execution support for Ryubing-Android.
#
# Builds libryubing-nce.so, the native NCE backend used by the managed
# emulator core (libryubing.so) via P/Invoke. See implementation_plan.md
# at the repo root for the overall architecture.
#
# Phase 0: scaffolding (version query only).
# Phase 1: binary patcher (oaknut codegen).

cmake_minimum_required(VERSION 3.22.1)
project(ryubing-nce LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# NCE is arm64-only by design (guest ARM64 code runs directly on the host).
if(NOT CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
    message(FATAL_ERROR "ryubing-nce requires an arm64 host (got ${CMAKE_SYSTEM_PROCESSOR})")
endif()

include(FetchContent)

# oaknut: AArch64 assembler library used by the patcher.
# Upstream tags have no "v" prefix (eden pins v2.0.3 from their fork;
# upstream 2.0.2 has the same API surface we use).
FetchContent_Declare(
    oaknut
    GIT_REPOSITORY https://github.com/merryhime/oaknut.git
    GIT_TAG        2.0.2
)
FetchContent_MakeAvailable(oaknut)

# Normalize the target name (upstream exposes oaknut::oaknut).
if(NOT TARGET oaknut::oaknut)
    if(TARGET oaknut)
        add_library(oaknut::oaknut ALIAS oaknut)
    else()
        message(FATAL_ERROR "oaknut target not found after FetchContent")
    endif()
endif()

add_library(ryubing-nce SHARED
    nce.cpp
    core.cpp
    guest_context.cpp
    patcher.cpp
    signal_handlers.cpp
    nce.S
)

target_include_directories(ryubing-nce PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# Enable assembly with C preprocessor (.S files).
enable_language(ASM)

target_link_libraries(ryubing-nce
    PRIVATE oaknut::oaknut
)

# Android logcat.
find_library(log-lib log)
if(log-lib)
    target_link_libraries(ryubing-nce PRIVATE ${log-lib})
endif()

# The managed core loads this library by soname "libryubing-nce.so"
# (DllImport("ryubing-nce") -> dlopen("libryubing-nce.so")); the JNI shim
# also has a DT_NEEDED on it so loading ryubingjni pulls it in eagerly.
set_target_properties(ryubing-nce PROPERTIES
    OUTPUT_NAME "ryubing-nce"
)


