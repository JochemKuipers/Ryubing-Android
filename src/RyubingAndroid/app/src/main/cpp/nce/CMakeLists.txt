# libryubing-nce: Native Code Execution support for Ryubing-Android.
#
# Builds libryubing-nce.so, the native NCE backend used by the managed
# emulator core (libryubing.so) via P/Invoke. See implementation_plan.md
# at the repo root for the overall architecture.

cmake_minimum_required(VERSION 3.22.1)
project(ryubing-nce LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(RYUBING_NCE_BUILD_TESTS "Build host patcher unit tests (any arch)" OFF)

include(FetchContent)

# oaknut: AArch64 assembler library used by the patcher.
FetchContent_Declare(
    oaknut
    GIT_REPOSITORY https://github.com/merryhime/oaknut.git
    GIT_TAG        2.0.2
)
FetchContent_MakeAvailable(oaknut)

if(NOT TARGET oaknut::oaknut)
    if(TARGET oaknut)
        add_library(oaknut::oaknut ALIAS oaknut)
    else()
        message(FATAL_ERROR "oaknut target not found after FetchContent")
    endif()
endif()

# --- Host patcher + interpreter tests (no asm / signals; any host arch) ---
if(RYUBING_NCE_BUILD_TESTS)
    add_executable(ryubing_nce_patcher_test
        tests/patcher_test.cpp
        patcher.cpp
    )
    target_include_directories(ryubing_nce_patcher_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_link_libraries(ryubing_nce_patcher_test PRIVATE oaknut::oaknut)

    add_executable(ryubing_nce_interpreter_test
        tests/interpreter_test.cpp
        interpreter_visitor.cpp
    )
    target_include_directories(ryubing_nce_interpreter_test PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/include
    )

    enable_testing()
    add_test(NAME nce_patcher COMMAND ryubing_nce_patcher_test)
    add_test(NAME nce_interpreter COMMAND ryubing_nce_interpreter_test)
endif()

# --- Shared library for Android (arm64 host only) ---
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
    enable_language(ASM)

    add_library(ryubing-nce SHARED
        nce.cpp
        core.cpp
        guest_context.cpp
        patcher.cpp
        signal_handlers.cpp
        interpreter_visitor.cpp
        nce.S
    )

    target_include_directories(ryubing-nce PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/include
    )

    target_link_libraries(ryubing-nce
        PRIVATE oaknut::oaknut
    )

    find_library(log-lib log)
    if(log-lib)
        target_link_libraries(ryubing-nce PRIVATE ${log-lib})
    endif()

    set_target_properties(ryubing-nce PROPERTIES
        OUTPUT_NAME "ryubing-nce"
    )
elseif(NOT RYUBING_NCE_BUILD_TESTS)
    message(FATAL_ERROR "ryubing-nce shared library requires an arm64 host (got ${CMAKE_SYSTEM_PROCESSOR}); set -DRYUBING_NCE_BUILD_TESTS=ON to build host tests only")
endif()
