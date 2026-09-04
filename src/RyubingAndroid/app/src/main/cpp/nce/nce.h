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
// below via P/Invoke. SVC and fault exits return HaltReason bits to the
// managed Execute loop, which dispatches existing ExceptionCallbacks.

#ifndef RYUBING_NCE_H
#define RYUBING_NCE_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// --- Version / capability query (always available) ---

#define RYUBING_NCE_ABI_VERSION 4

int32_t nce_get_abi_version(void);

// Returns a human-readable version string (static storage, no free needed).
const char* nce_get_version_string(void);

// NCE debug verbosity (0=Off, 1=Errors, 2=Standard, 3=Verbose). Default Off.
// Safe to call before nce_initialize. Affects native logcat under tag RyubingNCE.
void nce_set_debug_level(int32_t level);

// --- Guest memory / page-fault integration (phase 5) ---

// Called from the SIGSEGV guest path when the fault address lies in the
// registered host-mapped guest address space. Return 1 if the access was
// resolved (guest should resume), 0 to fall through to the failed-fault path.
//   guest_va: page-aligned guest virtual address
//   size:     typically one page
//   is_write: 1 if the access was a store (ESR WnR), 0 if a load
typedef int32_t (*NcePageFaultHandler)(uint64_t guest_va, uint64_t size, int32_t is_write);

// Registers the identity-mapped guest window [window_base, window_base+size)
// and an optional page-fault callback (GPU tracking / remapping). Guest VAs
// are host pointers (Eden EnableDirectMappedAddress), so the fault address is
// passed to the handler untranslated. Pass handler=NULL to clear. Safe to call
// before or after nce_initialize.
void nce_set_memory_config(uint64_t window_base, uint64_t window_size,
                           NcePageFaultHandler handler);

// --- Self test ---

// Stage bits for NceSelfTestResult (must match LibRyubing.Nce.NceSelfTest.Stage).
typedef enum NceSelfTestStage {
    NCE_SELFTEST_SETUP         = 1u << 0, // scratch mapping, patching, core creation
    NCE_SELFTEST_EXECUTE       = 1u << 1, // guest code ran at all
    NCE_SELFTEST_LOAD_STORE    = 1u << 2, // plain LDR/STR through the identity window
    NCE_SELFTEST_SVC           = 1u << 3, // SVC trampoline halted with SupervisorCall + number
    NCE_SELFTEST_SVC_REGISTERS = 1u << 4, // X0 in/out fidelity across the SVC round trip
    NCE_SELFTEST_ALIGNMENT     = 1u << 5, // misaligned LDAR handled by the SIGBUS interpreter
    NCE_SELFTEST_DATA_ABORT    = 1u << 6, // access fault surfaced as HaltReason::DataAbort
    NCE_SELFTEST_CLEANUP       = 1u << 7, // scratch restored to PROT_NONE, trampolines restored
    NCE_SELFTEST_INTERRUPT     = 1u << 8, // SignalInterrupt breaks a spinning guest; idle-core interrupt does not block
} NceSelfTestStage;

// Layout must match LibRyubing.Nce.NceNative.NceSelfTestResult exactly.
typedef struct NceSelfTestResult {
    uint32_t stages_run;
    uint32_t stages_failed;
    uint32_t observed_svc_number;
    uint32_t reserved0;
    uint64_t observed_svc_x0;
    uint64_t observed_store_value;
    uint64_t observed_alignment_value;
    uint64_t observed_halt_reason;
    uint64_t observed_fault_pc;
    uint64_t scratch_address;
} NceSelfTestResult;

// Runs a tiny patched guest snippet through the real run loop, inside the top
// of the reserved identity window [window_base, window_base+window_size), and
// exercises load/store, the SVC trampoline round trip, the alignment-fault
// interpreter and the access-fault (DataAbort) path. Temporarily installs the
// window as the memory config (no page-fault handler) and restores the previous
// config and trampoline registry afterwards. Must be called on a thread that is
// not currently running guest code. Returns 0 when every stage passed.
int32_t nce_self_test(uint64_t window_base, uint64_t window_size, NceSelfTestResult* out_result);

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
// Layout must match LibRyubing.Nce.NceNative.NceGuestContextView exactly.
typedef struct NceGuestContextView {
    uint64_t x[31];        // X0-X30 (index 31 on the managed side maps to sp)
    uint64_t sp;
    uint64_t pc;
    uint32_t pstate;
    uint32_t fpcr;
    uint32_t fpsr;
    uint64_t tpidr_el0;
    uint64_t tpidrro_el0;
    // V0-V31 as little-endian ulong pairs (e0=low 64, e1=high 64). Align so
    // the managed Sequential layout and native memcpy stay in sync.
    uint64_t v[32][2];
} NceGuestContextView;

// NOTE: thread parameters (the TPIDR_EL0 target while the guest runs) are
// owned by the native core at a stable address — they must never live in
// GC-managed memory. The C ABI therefore does not expose them.

// Installs the NCE signal handlers (SIGUSR2, SIGURG, SIGBUS, SIGSEGV) using
// the real libc sigaction (bypassing the ART signal chain on Android).
// Call once from any thread before creating cores. Idempotent.
// Returns 0 on success.
int32_t nce_initialize(void);

// Sets up the calling thread's alternate signal stack. Prefer letting
// nce_core_create do this (single ownership). Safe to call independently
// only if no core will be created on this thread afterward.
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

