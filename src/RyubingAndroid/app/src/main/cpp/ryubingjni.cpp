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
int32_t g_pending_transform = -1;
int32_t g_last_applied_transform = -1;

using SetBuffersTransformFn = int32_t (*)(ANativeWindow *, int32_t);

SetBuffersTransformFn GetSetBuffersTransformFn() {
    static SetBuffersTransformFn fn = reinterpret_cast<SetBuffersTransformFn>(
        dlsym(RTLD_DEFAULT, "ANativeWindow_setBuffersTransform"));
    return fn;
}

// Portrait-native handheld panels (AYN Thor, etc.) use a flipped natural orientation.
constexpr bool kInitialOrientationFlipped = true;

void ApplyCurrentTransform(ANativeWindow *window, int32_t transform) {
    if (window == nullptr) {
        return;
    }

    if (transform == g_last_applied_transform) {
        return;
    }

    SetBuffersTransformFn setTransform = GetSetBuffersTransformFn();
    if (setTransform == nullptr) {
        return;
    }

    int32_t nativeTransform = ANATIVEWINDOW_TRANSFORM_IDENTITY;
    transform = transform >> 1;

    // Map VkSurfaceTransformFlagBitsKHR (after >> 1) to ANativeWindow transform.
    switch (transform) {
        case 0x1:
            nativeTransform = ANATIVEWINDOW_TRANSFORM_IDENTITY;
            break;
        case 0x2:
            nativeTransform = ANATIVEWINDOW_TRANSFORM_ROTATE_90;
            break;
        case 0x4:
            nativeTransform = kInitialOrientationFlipped
                                  ? ANATIVEWINDOW_TRANSFORM_IDENTITY
                                  : ANATIVEWINDOW_TRANSFORM_ROTATE_180;
            break;
        case 0x8:
            nativeTransform = ANATIVEWINDOW_TRANSFORM_ROTATE_270;
            break;
        default:
            nativeTransform = ANATIVEWINDOW_TRANSFORM_IDENTITY;
            break;
    }

    setTransform(window, nativeTransform);
    g_last_applied_transform = transform;
}

int32_t AndroidRotationToTransform(int androidRotation) {
    switch (androidRotation) {
        case 0:
            return 0;
        case 1:
            return 4;
        case 2:
            return 3;
        case 3:
            return 7;
        default:
            return 0;
    }
}
} // namespace

extern "C" void ryubingjni_set_current_transform(int transform) {
    if (g_window != nullptr) {
        ApplyCurrentTransform(g_window, transform);
    }
}

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

    if (g_pending_transform >= 0) {
        ApplyCurrentTransform(g_window, g_pending_transform);
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
        if (g_pending_transform >= 0) {
            ApplyCurrentTransform(g_window, g_pending_transform);
        }
        LOGI("ANativeWindow acquired: %p", g_window);
    } else {
        g_pending_transform = -1;
        g_last_applied_transform = -1;
        LOGI("Surface cleared");
    }
}

JNIEXPORT void JNICALL
Java_org_ryubing_android_RyubingNative_setSurfaceRotation(JNIEnv *, jclass, jint rotation) {
    g_pending_transform = AndroidRotationToTransform(rotation);
    if (g_window != nullptr) {
        ApplyCurrentTransform(g_window, g_pending_transform);
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
