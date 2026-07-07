// libryubingjni.so — Android platform shim for the Ryubing emulator core.
//
// This is deliberately small: it owns the ANativeWindow behind the Compose
// SurfaceView and creates the VkSurfaceKHR the managed renderer draws into. The
// surface factory is handed to libryubing.so as a plain function pointer so the
// NativeAOT image never needs to touch JNI.

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <vulkan/vulkan.h>

#include "ryubing_interop.h"

#define LOG_TAG "RyubingJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    JavaVM *g_vm = nullptr;
    ANativeWindow *g_window = nullptr;
}

// Surface factory passed to libryubing.so. Signature matches RyubingCreateSurfaceFn.
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

// Called from Kotlin when the SurfaceView surface is created/changed.
JNIEXPORT void JNICALL
Java_org_ryubing_android_RyubingNative_setSurface(JNIEnv *env, jclass, jobject surface) {
    if (g_window != nullptr) {
        ANativeWindow_release(g_window);
        g_window = nullptr;
    }

    if (surface != nullptr) {
        g_window = ANativeWindow_fromSurface(env, surface);
        LOGI("ANativeWindow acquired: %p", g_window);
    } else {
        LOGI("Surface cleared");
    }
}

// Registers this shim's surface factory with the managed core. Call after both
// libraries are loaded and before ryubing_load_application.
JNIEXPORT void JNICALL
Java_org_ryubing_android_RyubingNative_registerSurfaceProvider(JNIEnv *, jclass) {
    ryubing_set_surface_provider(&ryubingjni_create_surface);
    LOGI("Surface provider registered with libryubing");
}

} // extern "C"
