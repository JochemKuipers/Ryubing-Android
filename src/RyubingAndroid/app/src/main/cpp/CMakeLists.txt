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
# It exposes a function pointer (createVulkanSurface) that Kotlin passes to
# libryubing.so via `ryubing_set_surface_provider`, keeping JNI out of the AOT image.
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
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
)
