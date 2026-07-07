// C ABI shared between the JNI shim (libryubingjni.so) and the managed core
// (libryubing.so). The struct layout MUST match RyubingCallbacks in
// src/LibRyubing/Interop.cs exactly (sequential layout, matching field order/types).
#ifndef RYUBING_INTEROP_H
#define RYUBING_INTEROP_H

#include <cstdint>

extern "C" {

// (VkInstance handle) -> VkSurfaceKHR handle. Implemented in ryubingjni.cpp and
// passed to libryubing.so via ryubing_set_surface_provider.
typedef uint64_t (*RyubingCreateSurfaceFn)(void *vkInstance);

// Outbound UI callbacks (managed core -> app). Implemented by the shim, which
// marshals to Kotlin. Order/types must match RyubingCallbacks in Interop.cs.
typedef struct {
    // (title, message, initialText, maxLength) -> malloc'd UTF-8 string or null.
    char *(*RequestTextInput)(const char *title, const char *message, const char *initialText, int maxLength);
    // (title, message) -> 1 accepted, 0 cancelled.
    int (*ShowMessageDialog)(const char *title, const char *message);
    // (title, message, buttonsJoinedByNewline) -> 1 if a non-OK button pressed.
    int (*ShowErrorDialog)(const char *title, const char *message, const char *buttons);
    // (stage, current, total).
    void (*ReportProgress)(const char *stage, int current, int total);
    // () -> selected user index or -1.
    int (*ShowUserSelector)();
} RyubingCallbacks;

// --- Entry points exported by libryubing.so (NativeAOT). Declared here so the
//     JNI shim can call them directly once both libraries are loaded. ---
int ryubing_initialize(const char *appDataPath);
void ryubing_set_surface_provider(RyubingCreateSurfaceFn createSurface);
void ryubing_set_callbacks(RyubingCallbacks *callbacks);
int ryubing_load_application(const char *path);
int ryubing_is_running();
void ryubing_set_button_state(int buttonMask);
void ryubing_set_stick_state(int rightStick, float x, float y);
void ryubing_set_motion_state(float ax, float ay, float az, float gx, float gy, float gz);
void ryubing_stop();
void ryubing_shutdown();

} // extern "C"

#endif // RYUBING_INTEROP_H
