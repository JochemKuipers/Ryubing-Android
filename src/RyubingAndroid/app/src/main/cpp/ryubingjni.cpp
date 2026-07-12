#include <dlfcn.h>
#include <jni.h>
#include <cstring>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <vulkan/vulkan.h>

#include "ryubing_interop.h"

#ifdef RYUBING_USE_ADRENOTOOLS
#include "adrenotools/driver.h"
#endif

#define LOG_TAG "RyubingJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
JavaVM *g_vm = nullptr;
ANativeWindow *g_window = nullptr;
void *g_vulkan_driver = nullptr;
int g_surface_rotation = 0;

using SetBuffersTransformFn = int32_t (*)(ANativeWindow *, int32_t);

SetBuffersTransformFn GetSetBuffersTransformFn() {
    static SetBuffersTransformFn fn = reinterpret_cast<SetBuffersTransformFn>(
        dlsym(RTLD_DEFAULT, "ANativeWindow_setBuffersTransform"));
    return fn;
}

void ApplyNativeWindowTransform(ANativeWindow *window, int /*androidRotation*/) {
    if (window == nullptr) {
        return;
    }

    SetBuffersTransformFn setTransform = GetSetBuffersTransformFn();
    if (setTransform == nullptr) {
        return;
    }

    // Vulkan already honors SurfaceCapabilities.CurrentTransform via swapchain
    // preTransform. Applying a matching buffer rotation here double-rotates output.
    setTransform(window, ANATIVEWINDOW_TRANSFORM_IDENTITY);
}
} // namespace

static char *GetStringUtf8(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return nullptr;
    }
    const char *utf = env->GetStringUTFChars(value, nullptr);
    if (utf == nullptr) {
        return nullptr;
    }
    auto *copy = new char[strlen(utf) + 1];
    strcpy(copy, utf);
    env->ReleaseStringUTFChars(value, utf);
    return copy;
}

extern "C" uint64_t ryubingjni_create_surface(void *instanceHandle) {
    if (g_window == nullptr) {
        LOGE("create_surface called with no ANativeWindow set");
        return 0;
    }

    auto instance = reinterpret_cast<VkInstance>(instanceHandle);

    VkAndroidSurfaceCreateInfoKHR createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    createInfo.window = g_window;

    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkResult result = vkCreateAndroidSurfaceKHR(instance, &createInfo, nullptr, &surface);
    if (result != VK_SUCCESS) {
        LOGE("vkCreateAndroidSurfaceKHR failed: %d", result);
        return 0;
    }

    return reinterpret_cast<uint64_t>(surface);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT void JNICALL
Java_org_ryubing_android_RyubingNative_setSurface(JNIEnv *env, jclass, jobject surface) {
    if (g_window != nullptr) {
        ANativeWindow_release(g_window);
        g_window = nullptr;
    }

    if (surface != nullptr) {
        g_window = ANativeWindow_fromSurface(env, surface);
        ApplyNativeWindowTransform(g_window, g_surface_rotation);
        LOGI("ANativeWindow acquired: %p", g_window);
    } else {
        LOGI("Surface cleared");
    }
}

JNIEXPORT void JNICALL
Java_org_ryubing_android_RyubingNative_setSurfaceRotation(JNIEnv *, jclass, jint rotation) {
    g_surface_rotation = rotation;
    if (g_window != nullptr) {
        ApplyNativeWindowTransform(g_window, g_surface_rotation);
    }
}

JNIEXPORT void JNICALL
Java_org_ryubing_android_RyubingNative_registerSurfaceProvider(JNIEnv *, jclass) {
    ryubing_set_surface_provider(&ryubingjni_create_surface);
    LOGI("Surface provider registered with libryubing");
}

JNIEXPORT jlong JNICALL
Java_org_ryubing_android_RyubingNative_loadVulkanDriver(
        JNIEnv *env,
        jclass,
        jstring hook_lib_dir,
        jstring custom_driver_dir,
        jstring custom_driver_name) {
#ifndef RYUBING_USE_ADRENOTOOLS
    LOGE("loadVulkanDriver called but adrenotools support was not compiled in");
    return 0;
#else
    if (g_vulkan_driver != nullptr) {
        dlclose(g_vulkan_driver);
        g_vulkan_driver = nullptr;
    }

    char *hookLibDir = GetStringUtf8(env, hook_lib_dir);
    char *customDriverDir = GetStringUtf8(env, custom_driver_dir);
    char *customDriverName = GetStringUtf8(env, custom_driver_name);

    if (hookLibDir == nullptr || customDriverDir == nullptr || customDriverName == nullptr) {
        delete[] hookLibDir;
        delete[] customDriverDir;
        delete[] customDriverName;
        LOGE("loadVulkanDriver received null path argument");
        return 0;
    }

    LOGI("Loading custom Vulkan driver '%s' from '%s' (hooks in '%s')",
         customDriverName, customDriverDir, hookLibDir);

    g_vulkan_driver = adrenotools_open_libvulkan(
            RTLD_NOW,
            ADRENOTOOLS_DRIVER_CUSTOM,
            nullptr,
            hookLibDir,
            customDriverDir,
            customDriverName,
            nullptr,
            nullptr);

    delete[] hookLibDir;
    delete[] customDriverDir;
    delete[] customDriverName;

    if (g_vulkan_driver == nullptr) {
        LOGE("adrenotools_open_libvulkan failed");
        return 0;
    }

    return reinterpret_cast<jlong>(g_vulkan_driver);
#endif
}

} // extern "C"
