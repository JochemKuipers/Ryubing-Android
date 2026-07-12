cmake_minimum_required(VERSION 3.22.1)
project(ryubingjni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

FetchContent_Declare(
    adrenotools
    GIT_REPOSITORY https://github.com/bylaws/libadrenotools.git
    GIT_TAG        8fae8ce254dfc1344527e05301e43f37dea2df80
)
FetchContent_MakeAvailable(adrenotools)

add_library(ryubingjni SHARED
    ryubingjni.cpp
)

target_compile_definitions(ryubingjni PRIVATE
    VK_USE_PLATFORM_ANDROID_KHR
    RYUBING_USE_ADRENOTOOLS
)

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)

set(RYUBING_CORE "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libryubing.so")
add_library(ryubing SHARED IMPORTED)
set_target_properties(ryubing PROPERTIES IMPORTED_LOCATION "${RYUBING_CORE}")

target_link_libraries(ryubingjni
    ryubing
    adrenotools
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
)
