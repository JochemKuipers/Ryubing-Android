// SPDX-License-Identifier: MIT
//
// Gated NCE logging. Level is set from the Android settings UI via
// nce_set_debug_level (mirrors Eden's GpuLogLevel-style verbosity knob).
// Logcat tag: RyubingNCE (included by scripts/android-deploy.sh logcat).

#pragma once

#include <android/log.h>
#include <atomic>
#include <cstdint>

#define NCE_LOG_TAG "RyubingNCE"

#define NCE_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, NCE_LOG_TAG, __VA_ARGS__)
#define NCE_LOGW(...) __android_log_print(ANDROID_LOG_WARN, NCE_LOG_TAG, __VA_ARGS__)
#define NCE_LOGI(...) __android_log_print(ANDROID_LOG_INFO, NCE_LOG_TAG, __VA_ARGS__)
#define NCE_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, NCE_LOG_TAG, __VA_ARGS__)

// 0=Off 1=Errors 2=Standard 3=Verbose
enum class NceDebugLevel : int32_t {
    Off = 0,
    Errors = 1,
    Standard = 2,
    Verbose = 3,
};

inline std::atomic<int32_t> g_nce_debug_level{static_cast<int32_t>(NceDebugLevel::Off)};

inline int32_t NceDebugLevelValue() {
    return g_nce_debug_level.load(std::memory_order_relaxed);
}

inline bool NceDebugAtLeast(NceDebugLevel level) {
    return NceDebugLevelValue() >= static_cast<int32_t>(level);
}

#define NCE_LOG_ERROR(...)                                                                         \
    do {                                                                                           \
        if (NceDebugAtLeast(NceDebugLevel::Errors)) {                                              \
            NCE_LOGE(__VA_ARGS__);                                                                 \
        }                                                                                          \
    } while (0)

#define NCE_LOG_STD(...)                                                                           \
    do {                                                                                           \
        if (NceDebugAtLeast(NceDebugLevel::Standard)) {                                            \
            NCE_LOGI(__VA_ARGS__);                                                                 \
        }                                                                                          \
    } while (0)

#define NCE_LOG_VERBOSE(...)                                                                       \
    do {                                                                                           \
        if (NceDebugAtLeast(NceDebugLevel::Verbose)) {                                             \
            NCE_LOGD(__VA_ARGS__);                                                                 \
        }                                                                                          \
    } while (0)
