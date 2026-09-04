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

// --- Signal handling and core management (phase 2/3) ---

// Guest register state snapshot, used to get/set the core's context from
// managed code. Must not be accessed while the guest thread is running.
typedef struct NceGuestContextView {
    uint64_t x[31];        // X0-X30
    uint64_t sp;
    uint64_t pc;
    uint32_t pstate;
    uint32_t fpcr;
    uint32_t fpsr;
    uint64_t tpidr_el0;
    uint64_t tpidrro_el0;
} NceGuestContextView;

// NOTE: thread parameters (the TPIDR_EL0 target while the guest runs) are
// owned by the native core at a stable address — they must never live in
// GC-managed memory. The C ABI therefore does not expose them.

// Installs the NCE signal handlers (SIGUSR2, SIGURG, SIGBUS, SIGSEGV) using
// the real libc sigaction (bypassing the ART signal chain on Android).
// Call once from any thread before creating cores. Idempotent.
// Returns 0 on success.
int32_t nce_initialize(void);

// Sets up the calling thread's alternate signal stack. Call once from each
// thread that will run guest code (i.e., each emulated CPU core thread).
// Returns 0 on success.
int32_t nce_thread_init(void);

// Creates an NCE core bound to the calling thread. Returns a handle (>0),
// or -1 on failure. The handle is used by nce_run_thread etc. Each core
// must be created and used from the same thread.
int32_t nce_core_create(void);

// Destroys a core handle (does not stop a running guest).
void nce_core_destroy(int32_t core_handle);

// Runs guest code on the core starting from the current guest context.
// Blocks until the guest exits (SVC, interrupt, or fault).
//   trampoline_addr: address of a post-SVC re-entry trampoline (0=auto:
//   look up the trampoline for the current PC in the registry).
// Returns the halt reason (bit flags; see HaltReason in guest_context.h).
uint64_t nce_run_thread(int32_t core_handle, uint64_t trampoline_addr);

// Signals the running guest thread on this core to break out of the run
// loop (SIGURG). Safe to call from another thread.
void nce_signal_interrupt(int32_t core_handle);

// Gets/sets the core's guest register snapshot. Do not call while running.
void nce_get_context(int32_t core_handle, NceGuestContextView* out_view);
void nce_set_context(int32_t core_handle, const NceGuestContextView* in_view);

// --- SVC dispatch support (phase 3) ---

// Returns the SVC number recorded by the patcher's SVC trampoline (set in
// GuestContext::svc before returning to host). Only meaningful when the
// last halt reason included SupervisorCall.
uint32_t nce_get_svc_number(int32_t core_handle);

// Clears the trampoline registry (call when the guest process exits, before
// loading a new one). Trampolines are registered by nce_patch_module and
// used automatically by nce_run_thread to re-enter the guest efficiently
// after an SVC.
void nce_clear_trampolines(void);

#ifdef __cplusplus
} // extern "C"
#endif

#endif // RYUBING_NCE_H

