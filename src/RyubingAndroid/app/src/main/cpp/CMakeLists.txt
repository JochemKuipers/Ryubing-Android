cmake_minimum_required(VERSION 3.22.1)
project(ryubingjni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

#
# libryubingjni.so — the thin C++ platform shim.
#
# Responsibilities the managed NativeAOT core (libryubing.so) cannot do itself:
#   * turn a Java android.view.Surface into an ANativeWindow
#   * create a VkSurfaceKHR from that window (vkCreateAndroidSurfaceKHR)
#   * (optionally) load a custom Vulkan driver (Turnip) via adrenotools
#   * hold the JavaVM for any reverse-JNI needs
#
# It owns a surface factory and hands it to libryubing.so by calling the core export
# `ryubing_set_surface_provider` directly (see registerSurfaceProvider in ryubingjni.cpp),
# so this shim links against libryubing.so at build time.
#

add_library(ryubingjni SHARED
    ryubingjni.cpp
)

target_compile_definitions(ryubingjni PRIVATE
    VK_USE_PLATFORM_ANDROID_KHR
)

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)

# --- Link against the NativeAOT core (libryubing.so) ---
# The core is published into the app's jniLibs by the :libryubing:assemble Gradle task,
# which runs as a preBuild dependency, so the .so is present before this build links.
# The shim calls core exports directly (e.g. ryubing_set_surface_provider), making this
# a hard link-time dependency; at runtime the Android loader resolves it from jniLibs.
set(RYUBING_CORE "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libryubing.so")
add_library(ryubing SHARED IMPORTED)
set_target_properties(ryubing PROPERTIES IMPORTED_LOCATION "${RYUBING_CORE}")

# --- Optional: adrenotools for custom Vulkan (Turnip) driver loading ---
# Provide the checkout via -DADRENOTOOLS_DIR=... (git submodule under libraries/).
# When absent, the shim builds without custom-driver support and uses the system loader.
option(USE_ADRENOTOOLS "Enable custom Vulkan driver loading via adrenotools" OFF)
set(ADRENOTOOLS_DIR "" CACHE PATH "Path to a libadrenotools checkout")

if(USE_ADRENOTOOLS AND EXISTS "${ADRENOTOOLS_DIR}/CMakeLists.txt")
    add_subdirectory("${ADRENOTOOLS_DIR}" adrenotools_build)
    target_compile_definitions(ryubingjni PRIVATE RYUBING_USE_ADRENOTOOLS)
    target_link_libraries(ryubingjni adrenotools)
    message(STATUS "ryubingjni: adrenotools enabled from ${ADRENOTOOLS_DIR}")
else()
    message(STATUS "ryubingjni: building without adrenotools (system Vulkan loader only)")
endif()

target_link_libraries(ryubingjni
    ryubing
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
)
