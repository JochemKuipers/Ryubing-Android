// SPDX-License-Identifier: MIT
//
// C ABI implementation for libryubing-nce.

#include <cstring>
#include <mutex>
#include <unordered_map>
#include <vector>

#include <android/log.h>

#include "nce.h"
#include "core.h"
#include "instructions.h"
#include "patcher.h"

#define LOG_TAG "RyubingNCE"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

using Ryubing::Nce::Patcher;
using Ryubing::Nce::NceCore;
using Ryubing::Nce::NativeExecutionParameters;
using Ryubing::Nce::GuestContext;
using Ryubing::Nce::CodeSegment;
using Ryubing::Nce::EntryTrampolines;

// Core handle registry (protected by a mutex; cores are per-thread).
std::mutex g_core_mutex;
std::unordered_map<int32_t, std::unique_ptr<NceCore>> g_cores;
int32_t g_next_core_handle = 1;

// Trampoline registry: guest code address -> patch entry point, used by
// nce_run_thread to re-enter the guest efficiently after an SVC. Populated
// by nce_patch_module, cleared by nce_clear_trampolines. Only one guest
// process runs at a time, so a single global map suffices.
std::unordered_map<uint64_t, uint64_t> g_trampolines;

NceCore* GetCore(int32_t handle) {
    std::lock_guard lock(g_core_mutex);
    auto it = g_cores.find(handle);
    return it != g_cores.end() ? it->second.get() : nullptr;
}

// The internal NativeExecutionParameters must match the fixed layout in
// asm_defs.h (the patcher's generated code reads these offsets).
static_assert(offsetof(NativeExecutionParameters, tpidr_el0) == 0x00);
static_assert(offsetof(NativeExecutionParameters, tpidrro_el0) == 0x08);
static_assert(offsetof(NativeExecutionParameters, native_context) == 0x10);
static_assert(offsetof(NativeExecutionParameters, lock) == 0x18);
static_assert(offsetof(NativeExecutionParameters, is_running) == 0x1C);
static_assert(offsetof(NativeExecutionParameters, magic) == 0x20);

// Upper bound on patch-section growth per module (generous).
constexpr uint64_t PatchGrowthEstimate(uint64_t image_size) {
    // Each SVC trampoline is ~40 instructions, MRS/MSR handlers ~5-10.
    // A pathological module could have 1 SVC per 4 bytes; use 16x growth
    // as a hard cap estimate, minimum 64KB for the shared save/load stubs.
    return (image_size / 2) + (64 * 1024);
}

} // namespace

extern "C" {

int32_t nce_get_abi_version(void) {
    return RYUBING_NCE_ABI_VERSION;
}

const char* nce_get_version_string(void) {
    return "ryubing-nce 0.4.0 (phase 3: execution core)";
}

int32_t nce_patch_module(
    uint8_t* program_image,
    uint64_t image_capacity,
    uint64_t image_size,
    uint64_t code_offset,
    uint64_t code_size,
    uint64_t base_virtual_addr,
    uint64_t* out_image_size,
    NcePatchResult* out_result
) {
    if (out_image_size == nullptr || out_result == nullptr) {
        return -1;
    }
    if (code_offset + code_size > image_size) {
        LOGE("patch_module: code segment [%llu..%llu) exceeds image size %llu",
             (unsigned long long)code_offset,
             (unsigned long long)(code_offset + code_size),
             (unsigned long long)image_size);
        return -1;
    }
    if ((code_offset & 3) != 0 || (code_size & 3) != 0) {
        LOGE("patch_module: code segment must be 4-byte aligned");
        return -1;
    }

    // Query mode: report the required capacity.
    if (program_image == nullptr) {
        *out_image_size = image_size + PatchGrowthEstimate(image_size);
        memset(out_result, 0, sizeof(*out_result));
        return 0;
    }

    // Copy into a working vector (the patcher resizes the image).
    std::vector<uint8_t> image(program_image, program_image + image_size);

    CodeSegment code{};
    code.offset = static_cast<size_t>(code_offset);
    code.addr = base_virtual_addr;
    code.size = static_cast<uint32_t>(code_size);

    Patcher patcher{};
    if (!patcher.PatchText(image, code)) {
        LOGE("patch_module: PatchText failed (module too far from patch section?)");
        return -2;
    }

    EntryTrampolines trampolines;
    if (!patcher.RelocateAndCopy(base_virtual_addr, code, image, &trampolines)) {
        LOGE("patch_module: RelocateAndCopy failed");
        return -2;
    }

    // Register the trampolines so nce_run_thread can find the re-entry
    // point for each patched SVC site.
    for (const auto& [guest_addr, patch_addr] : trampolines) {
        g_trampolines[guest_addr] = patch_addr;
    }

    const uint64_t final_size = static_cast<uint64_t>(image.size());
    if (final_size > image_capacity) {
        LOGE("patch_module: patched image (%llu) exceeds capacity (%llu)",
             (unsigned long long)final_size, (unsigned long long)image_capacity);
        *out_image_size = final_size;
        return -1;
    }

    // Copy the patched image back to the caller's buffer.
    memcpy(program_image, image.data(), final_size);

    // Fill in the result.
    memset(out_result, 0, sizeof(*out_result));
    out_result->success = 1;
    out_result->patched_image_size = final_size;

    const auto mode = patcher.GetPatchMode();
    out_result->patch_mode = static_cast<uint32_t>(mode);
    switch (mode) {
        case Ryubing::Nce::PatchMode::PreText:
            out_result->patch_offset = 0;
            out_result->patch_size = patcher.GetSectionSize();
            break;
        case Ryubing::Nce::PatchMode::PostData:
            out_result->patch_offset = image_size; // Patch appended after original image
            out_result->patch_size = patcher.GetSectionSize();
            break;
        case Ryubing::Nce::PatchMode::Split:
            out_result->pre_patch_offset = 0;
            out_result->pre_patch_size = patcher.GetPreSectionSize();
            out_result->patch_offset = final_size - patcher.GetSectionSize();
            out_result->patch_size = patcher.GetSectionSize();
            break;
        default:
            break;
    }

    // Debug counters (scanned in a second pass; cheap for typical modules).
    {
        const auto text = std::span<const uint8_t>{image}.subspan(
            static_cast<size_t>(code_offset), static_cast<size_t>(code_size));
        const auto words = std::span<const uint32_t>{
            reinterpret_cast<const uint32_t*>(text.data()), text.size() / sizeof(uint32_t)};
        uint32_t svc_count = 0, sysreg_count = 0, excl_count = 0;
        constexpr uint32_t StartIndex = 0x24 / sizeof(uint32_t);
        for (uint32_t i = StartIndex; i < static_cast<uint32_t>(words.size()); i++) {
            const uint32_t inst = words[i];
            if (Ryubing::Nce::SVC{inst}.Verify()) {
                svc_count++;
            } else if (Ryubing::Nce::MRS{inst}.Verify() || Ryubing::Nce::MSR{inst}.Verify()) {
                sysreg_count++;
            } else if (Ryubing::Nce::Exclusive{inst}.Verify()) {
                excl_count++;
            }
        }
        out_result->patched_svc_count = svc_count;
        out_result->patched_sysreg_count = sysreg_count;
        out_result->converted_exclusive_count = excl_count;
    }

    LOGI("patch_module: ok — image %llu -> %llu bytes, mode=%u, svc=%u, sysreg=%u, excl=%u",
         (unsigned long long)image_size, (unsigned long long)final_size,
         out_result->patch_mode, out_result->patched_svc_count,
         out_result->patched_sysreg_count, out_result->converted_exclusive_count);

    *out_image_size = final_size;
    return 0;
}


// --- Signal handling and core management (phase 2) ---

int32_t nce_initialize(void) {
    return Ryubing::Nce::InstallSignalHandlers();
}

int32_t nce_thread_init(void) {
    return Ryubing::Nce::SetupThreadSignalStack();
}

int32_t nce_core_create(void) {
    auto core = std::make_unique<NceCore>();
    if (core->Initialize() != 0) {
        LOGE("nce_core_create: Initialize failed");
        return -1;
    }

    std::lock_guard lock(g_core_mutex);
    int32_t handle = g_next_core_handle++;
    g_cores[handle] = std::move(core);
    return handle;
}

void nce_core_destroy(int32_t core_handle) {
    std::lock_guard lock(g_core_mutex);
    g_cores.erase(core_handle);
}

uint64_t nce_run_thread(int32_t core_handle, uint64_t trampoline_addr) {
    NceCore* core = GetCore(core_handle);
    if (core == nullptr) {
        return static_cast<uint64_t>(Ryubing::Nce::HaltReason::BreakLoop);
    }

    // Auto-lookup: when no trampoline is specified, check whether the
    // current guest PC has a registered re-entry point (i.e., we are
    // resuming right after an SVC). This matches eden's post-handler logic.
    if (trampoline_addr == 0) {
        const uint64_t pc = core->GetGuestContext().pc;
        auto it = g_trampolines.find(pc);
        if (it != g_trampolines.end()) {
            trampoline_addr = it->second;
        }
    }

    return core->RunThread(trampoline_addr);
}

void nce_signal_interrupt(int32_t core_handle) {
    NceCore* core = GetCore(core_handle);
    if (core == nullptr) {
        return;
    }
    core->SignalInterrupt();
}

void nce_get_context(int32_t core_handle, NceGuestContextView* out_view) {
    NceCore* core = GetCore(core_handle);
    if (core == nullptr || out_view == nullptr) {
        return;
    }

    const auto& ctx = core->GetGuestContext();
    std::memcpy(out_view->x, ctx.cpu_registers.data(), sizeof(out_view->x));
    out_view->sp = ctx.sp;
    out_view->pc = ctx.pc;
    out_view->pstate = ctx.pstate;
    out_view->fpcr = ctx.fpcr;
    out_view->fpsr = ctx.fpsr;
    out_view->tpidr_el0 = ctx.tpidr_el0;
    out_view->tpidrro_el0 = ctx.tpidrro_el0;
}

void nce_set_context(int32_t core_handle, const NceGuestContextView* in_view) {
    NceCore* core = GetCore(core_handle);
    if (core == nullptr || in_view == nullptr) {
        return;
    }

    auto& ctx = core->GetGuestContext();
    std::memcpy(ctx.cpu_registers.data(), in_view->x, sizeof(ctx.cpu_registers));
    ctx.sp = in_view->sp;
    ctx.pc = in_view->pc;
    ctx.pstate = in_view->pstate;
    ctx.fpcr = in_view->fpcr;
    ctx.fpsr = in_view->fpsr;
    ctx.tpidr_el0 = in_view->tpidr_el0;
    ctx.tpidrro_el0 = in_view->tpidrro_el0;
}


// --- SVC dispatch support (phase 3) ---

uint32_t nce_get_svc_number(int32_t core_handle) {
    NceCore* core = GetCore(core_handle);
    if (core == nullptr) {
        return 0;
    }
    return core->GetGuestContext().svc;
}

void nce_clear_trampolines(void) {
    g_trampolines.clear();
}

} // extern "C"

