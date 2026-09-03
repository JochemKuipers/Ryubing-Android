// SPDX-License-Identifier: MIT
//
// libryubing-nce: Native Code Execution support library for Ryubing-Android.
//
// This library implements the NCE CPU backend primitives that cannot be
// safely done from the managed (C# NativeAOT) side:
//   - Binary patching of guest ARM64 code (SVC/MRS/MSR/exclusive -> trampolines)
//   - Signal handlers for guest faults (SIGSEGV/SIGBUS) and control (SIGUSR2/SIGURG)
//   - Guest/host context save/restore across signal frames
//   - Guest thread execution management
//
// The managed core (libryubing.so) drives this library through the C ABI
// below via P/Invoke; callbacks back into managed code use a function-
// pointer table supplied at nce_initialize time.

#ifndef RYUBING_NCE_H
#define RYUBING_NCE_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// --- Version / capability query (always available) ---

#define RYUBING_NCE_ABI_VERSION 1

int32_t nce_get_abi_version(void);

// Returns a human-readable version string (static storage, no free needed).
const char* nce_get_version_string(void);

// --- Guest code patching (phase 1) ---

// Result of nce_patch_module. The caller (managed side) copies the patched
// image into guest memory; this struct tells it where the patch sections
// landed within the image.
typedef struct NcePatchResult {
    int32_t success;               // 1 on success
    uint64_t patched_image_size;   // Size of the expanded image
    uint64_t patch_offset;         // Post-text patch section offset (0 if none)
    uint64_t patch_size;           // Post-text patch section size (page-aligned)
    uint64_t pre_patch_offset;     // Pre-text patch section offset (0 if none)
    uint64_t pre_patch_size;       // Pre-text patch section size (0 if none)
    uint32_t patched_svc_count;    // SVCs patched (debug)
    uint32_t patched_sysreg_count; // MRS/MSR patched (debug)
    uint32_t converted_exclusive_count; // Exclusives made ordered (debug)
    uint32_t patch_mode;           // 0=none 1=pre-text 2=post-data 3=split
} NcePatchResult;

// Patches a guest module's .text so it can execute natively on the host CPU.
//
// The function writes the patched image back into the caller's buffer and
// reports the final size via *out_image_size. Call with program_image=NULL
// first to query the required capacity without patching.
//
// Returns 0 on success, -1 on invalid arguments, -2 on patch failure.
int32_t nce_patch_module(
    uint8_t* program_image,
    uint64_t image_capacity,
    uint64_t image_size,
    uint64_t code_offset,
    uint64_t code_size,
    uint64_t base_virtual_addr,
    uint64_t* out_image_size,
    NcePatchResult* out_result
);

#ifdef __cplusplus
} // extern "C"
#endif

#endif // RYUBING_NCE_H

