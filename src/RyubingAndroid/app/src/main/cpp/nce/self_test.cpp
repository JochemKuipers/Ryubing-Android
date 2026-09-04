// SPDX-License-Identifier: MIT
//
// nce_self_test: end-to-end check of the NCE machinery without a game.
//
// A tiny guest module is assembled in memory, run through the real patcher
// (so its SVC becomes a trampoline exactly like a game's), copied to an RWX
// scratch area at the top of the reserved identity window and executed via
// nce_run_thread on a fresh core. The snippet exercises, in order:
//
//   1. LDR/STR through the identity window            -> LOAD_STORE
//   2. SVC #0x7F  (trampoline halt, SVC number, X0)    -> SVC / SVC_REGISTERS
//   3. misaligned LDAR (SIGBUS -> one-insn interpreter)-> ALIGNMENT
//   4. LDR from a PROT_NONE page inside the window     -> DATA_ABORT
//   5. `b .` spin interrupted via SignalInterrupt       -> INTERRUPT
//      (SIGURG BreakLoop, then SignalInterrupt on the idle core must not block
//      and the pending BreakLoop must be returned without entering the guest)
//
// Everything the snippet touches lives inside the window, so the test also
// validates the "guest VA == host pointer" contract the rest of NCE relies on.

#include <atomic>
#include <chrono>
#include <cstring>
#include <cstdint>
#include <thread>
#include <vector>

#include <sys/mman.h>

#include "core.h"
#include "guest_context.h"
#include "nce.h"
#include "nce_internal.h"
#include "nce_log.h"

namespace {

using Ryubing::Nce::GuestContext;
using Ryubing::Nce::HaltReason;
using Ryubing::Nce::NceCore;

constexpr uint64_t PageSize = 0x1000;

// Scratch layout at the top of the window.
constexpr uint64_t CodeRegionSize = 0x20000;     // patched image (text + patch section)
constexpr uint64_t DataRegionSize = PageSize;    // guest data + stack
constexpr uint64_t FaultPageSize = PageSize;     // left PROT_NONE on purpose
constexpr uint64_t ScratchSize = CodeRegionSize + DataRegionSize + FaultPageSize;
constexpr uint64_t ScratchAlign = 0x10000;

// Unpatched text: 0x24 bytes of header the patcher skips (NSO MOD0 area), then code.
constexpr uint64_t TextSize = PageSize;
constexpr uint64_t CodeStart = 0x40;

// Data page offsets written by the snippet.
constexpr uint64_t OffInput = 0;      // host writes InputValue here
constexpr uint64_t OffStore = 8;      // snippet stores InputValue + 1
constexpr uint64_t OffSvcX0 = 16;     // snippet stores X0 as returned from the host after the SVC
constexpr uint64_t OffAlign = 24;     // snippet stores the value loaded by the misaligned LDAR
constexpr uint64_t OffReached = 32;   // snippet stores 2 just before the deliberate fault
constexpr uint64_t OffMisaligned = 0x21; // misaligned source for LDAR

constexpr uint64_t InputValue = 0x1122334455667788ull;
constexpr uint64_t AlignValue = 0xA5A5A5A55A5A5A5Aull;
constexpr uint64_t SvcInX0 = 0x1234;
constexpr uint64_t SvcOutX0 = 0x5678;
constexpr uint32_t SvcNumber = 0x7F;

constexpr uint32_t NOP = 0xD503201F;

// Guest snippet (A64). Entry: X1 = data page, X6 = fault address, SP = data page top.
// Encodings are fixed so the test does not depend on the assembler.
constexpr uint32_t kSnippet[] = {
    0xF9400022, // ldr  x2, [x1]          ; InputValue
    0x91000442, // add  x2, x2, #1
    0xF9000422, // str  x2, [x1, #8]      ; OffStore
    0xD2824680, // mov  x0, #0x1234       ; SvcInX0
    0xD4000001u | (SvcNumber << 5), // svc #0x7F
    0xF9000820, // str  x0, [x1, #16]     ; OffSvcX0 (host-modified X0)
    0x91008423, // add  x3, x1, #0x21     ; misaligned pointer
    0xC8DFFC64, // ldar x4, [x3]          ; alignment fault -> interpreter
    0xF9000C24, // str  x4, [x1, #24]     ; OffAlign
    0xD2800040, // mov  x0, #2
    0xF9001020, // str  x0, [x1, #32]     ; OffReached
    0xF94000C5, // ldr  x5, [x6]          ; PROT_NONE page -> DataAbort
    0xD4200000, // brk  #0                ; never reached
    0x14000000, // b    .                 ; spin loop for the interrupt stage (entered directly)
};
constexpr uint64_t FaultInsnIndex = 11;
constexpr uint64_t SpinInsnIndex = 13;
static_assert(kSnippet[FaultInsnIndex] == 0xF94000C5, "fault index must point at the LDR from the PROT_NONE page");
static_assert(kSnippet[SpinInsnIndex] == 0x14000000, "spin index must point at `b .`, not the brk before it");

struct Scratch {
    uint64_t base = 0;
    uint64_t code = 0;
    uint64_t data = 0;
    uint64_t fault = 0;
};

bool MapScratch(uint64_t window_base, uint64_t window_size, Scratch& out) {
    if (window_size < ScratchSize + ScratchAlign) {
        return false;
    }
    const uint64_t top = window_base + window_size;
    const uint64_t base = (top - ScratchSize) & ~(ScratchAlign - 1);

    void* code = mmap(reinterpret_cast<void*>(base), CodeRegionSize,
                      PROT_READ | PROT_WRITE | PROT_EXEC,
                      MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
    if (code == MAP_FAILED) {
        NCE_LOGE("NCE|SELFTEST mmap code failed at 0x%llx", (unsigned long long)base);
        return false;
    }
    void* data = mmap(reinterpret_cast<void*>(base + CodeRegionSize), DataRegionSize,
                      PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
    if (data == MAP_FAILED) {
        NCE_LOGE("NCE|SELFTEST mmap data failed");
        return false;
    }

    out.base = base;
    out.code = base;
    out.data = base + CodeRegionSize;
    out.fault = base + CodeRegionSize + DataRegionSize; // still PROT_NONE (window reservation)
    return true;
}

bool UnmapScratch(const Scratch& s) {
    if (s.base == 0) {
        return true;
    }
    // Restore the reservation exactly as MemoryBlock left it (PROT_NONE, no backing).
    void* r = mmap(reinterpret_cast<void*>(s.base), CodeRegionSize + DataRegionSize, PROT_NONE,
                   MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED | MAP_NORESERVE, -1, 0);
    return r != MAP_FAILED;
}

} // namespace

extern "C" int32_t nce_self_test(uint64_t window_base, uint64_t window_size,
                                 NceSelfTestResult* out_result) {
    using namespace Ryubing::Nce;

    if (out_result == nullptr) {
        return -1;
    }
    std::memset(out_result, 0, sizeof(*out_result));

    auto ran = [&](uint32_t stage) { out_result->stages_run |= stage; };
    auto fail = [&](uint32_t stage, const char* why) {
        out_result->stages_run |= stage;
        out_result->stages_failed |= stage;
        NCE_LOGE("NCE|SELFTEST stage=0x%x FAIL: %s", stage, why);
    };

    NCE_LOGI("NCE|SELFTEST begin window=[0x%llx,0x%llx)",
             (unsigned long long)window_base, (unsigned long long)(window_base + window_size));

    // --- Setup ---------------------------------------------------------------
    ran(NCE_SELFTEST_SETUP);

    if (window_base == 0 || window_size == 0) {
        fail(NCE_SELFTEST_SETUP, "no window");
        return -1;
    }

    if (nce_initialize() != 0) {
        fail(NCE_SELFTEST_SETUP, "nce_initialize failed");
        return -1;
    }

    Scratch scratch;
    if (!MapScratch(window_base, window_size, scratch)) {
        fail(NCE_SELFTEST_SETUP, "scratch mapping failed");
        return -1;
    }
    out_result->scratch_address = scratch.base;

    // Assemble the unpatched text: header area (skipped by the patcher) + snippet.
    std::vector<uint8_t> image(TextSize, 0);
    {
        auto* words = reinterpret_cast<uint32_t*>(image.data());
        for (size_t i = 0; i < TextSize / 4; i++) {
            words[i] = NOP;
        }
        std::memcpy(image.data() + CodeStart, kSnippet, sizeof(kSnippet));
    }

    // Patch it like a real module, with its load base being the scratch code area.
    const auto saved_trampolines = Internal::SnapshotTrampolines();
    const uint64_t saved_base = g_guest_host_base.load(std::memory_order_acquire);
    const uint64_t saved_size = g_guest_as_size.load(std::memory_order_acquire);
    const uintptr_t saved_handler = g_page_fault_handler.load(std::memory_order_acquire);

    auto restore_globals = [&]() {
        Internal::RestoreTrampolines(saved_trampolines);
        g_guest_host_base.store(saved_base, std::memory_order_release);
        g_guest_as_size.store(saved_size, std::memory_order_release);
        g_page_fault_handler.store(saved_handler, std::memory_order_release);
    };

    uint64_t required = 0;
    NcePatchResult patch{};
    if (nce_patch_module(nullptr, 0, TextSize, 0, TextSize, scratch.code, &required, &patch) != 0 ||
        required > CodeRegionSize) {
        fail(NCE_SELFTEST_SETUP, "patch capacity query failed / too large");
        UnmapScratch(scratch);
        return -1;
    }

    std::vector<uint8_t> patched(required, 0);
    std::memcpy(patched.data(), image.data(), image.size());
    uint64_t final_size = 0;
    if (nce_patch_module(patched.data(), required, TextSize, 0, TextSize, scratch.code, &final_size,
                         &patch) != 0 || patch.success == 0 || final_size > CodeRegionSize) {
        fail(NCE_SELFTEST_SETUP, "nce_patch_module failed");
        restore_globals();
        UnmapScratch(scratch);
        return -1;
    }
    if (patch.patched_svc_count != 1) {
        fail(NCE_SELFTEST_SETUP, "patcher did not find exactly one SVC");
    }

    std::memcpy(reinterpret_cast<void*>(scratch.code), patched.data(), final_size);
    __builtin___clear_cache(reinterpret_cast<char*>(scratch.code),
                            reinterpret_cast<char*>(scratch.code + final_size));

    // Guest data page.
    auto* data = reinterpret_cast<uint64_t*>(scratch.data);
    std::memset(data, 0, DataRegionSize);
    data[OffInput / 8] = InputValue;
    std::memcpy(reinterpret_cast<uint8_t*>(scratch.data) + OffMisaligned, &AlignValue, sizeof(AlignValue));

    // Memory config: the window, no managed handler (faults -> DataAbort).
    g_guest_host_base.store(window_base, std::memory_order_release);
    g_guest_as_size.store(window_size, std::memory_order_release);
    g_page_fault_handler.store(0, std::memory_order_release);

    NceCore core;
    if (core.Initialize() != 0) {
        fail(NCE_SELFTEST_SETUP, "core Initialize failed");
        restore_globals();
        UnmapScratch(scratch);
        return -1;
    }

    // Guest entry state. The code lives at text offset CodeStart; PreText mode shifts it.
    uint64_t text_base = scratch.code;
    if (patch.patch_mode == 1 /* PreText */) {
        text_base += patch.patch_size;
    } else if (patch.patch_mode == 3 /* Split */) {
        text_base += patch.pre_patch_size;
    }
    const uint64_t entry_pc = text_base + CodeStart;
    const uint64_t svc_pc = entry_pc + 4 * 4;
    const uint64_t fault_pc = entry_pc + FaultInsnIndex * 4;
    const uint64_t spin_pc = entry_pc + SpinInsnIndex * 4;

    GuestContext& ctx = core.GetGuestContext();
    ctx.cpu_registers.fill(0);
    ctx.vector_registers.fill(u128{});
    ctx.pc = entry_pc;
    ctx.sp = scratch.data + DataRegionSize;
    ctx.cpu_registers[1] = scratch.data;
    ctx.cpu_registers[6] = scratch.fault;
    ctx.tpidr_el0 = 0;
    ctx.tpidrro_el0 = 0;
    ctx.pstate = 0;
    ctx.fpcr = 0;
    ctx.fpsr = 0;
    ctx.esr_el1.store(0);
    ctx.svc = 0;

    // --- Run until the SVC -------------------------------------------------------
    ran(NCE_SELFTEST_EXECUTE);
    uint64_t hr = core.RunThread(0);
    out_result->observed_halt_reason = hr;

    if ((hr & static_cast<u64>(HaltReason::SupervisorCall)) == 0) {
        fail(NCE_SELFTEST_EXECUTE, "first halt was not SupervisorCall");
        NCE_LOGE("NCE|SELFTEST hr=0x%llx pc=0x%llx expected svc at 0x%llx",
                 (unsigned long long)hr, (unsigned long long)ctx.pc, (unsigned long long)svc_pc);
        restore_globals();
        UnmapScratch(scratch);
        return -1;
    }

    ran(NCE_SELFTEST_LOAD_STORE);
    out_result->observed_store_value = data[OffStore / 8];
    if (out_result->observed_store_value != InputValue + 1) {
        fail(NCE_SELFTEST_LOAD_STORE, "LDR/STR result mismatch");
    }

    ran(NCE_SELFTEST_SVC);
    out_result->observed_svc_number = ctx.svc;
    out_result->observed_svc_x0 = ctx.cpu_registers[0];
    if (ctx.svc != SvcNumber) {
        fail(NCE_SELFTEST_SVC, "SVC number mismatch");
    }
    if (ctx.pc != svc_pc + 4) {
        fail(NCE_SELFTEST_SVC, "post-SVC PC mismatch");
    }
    if (ctx.cpu_registers[0] != SvcInX0 || ctx.cpu_registers[1] != scratch.data ||
        ctx.cpu_registers[2] != InputValue + 1 || ctx.sp != scratch.data + DataRegionSize) {
        fail(NCE_SELFTEST_SVC_REGISTERS, "guest registers not preserved into the SVC halt");
    }

    // "HLE" result, then resume through the trampoline (auto-lookup by PC).
    ctx.cpu_registers[0] = SvcOutX0;

    // --- Run to completion (alignment fault, then deliberate data abort) ----------
    hr = core.RunThread(0);
    out_result->observed_halt_reason = hr;
    out_result->observed_fault_pc = ctx.pc;

    ran(NCE_SELFTEST_SVC_REGISTERS);
    if (data[OffSvcX0 / 8] != SvcOutX0) {
        fail(NCE_SELFTEST_SVC_REGISTERS, "X0 written by host did not reach the guest after resume");
    }

    ran(NCE_SELFTEST_ALIGNMENT);
    out_result->observed_alignment_value = data[OffAlign / 8];
    if (out_result->observed_alignment_value != AlignValue) {
        fail(NCE_SELFTEST_ALIGNMENT, "misaligned LDAR not emulated correctly");
    }

    ran(NCE_SELFTEST_DATA_ABORT);
    if (data[OffReached / 8] != 2) {
        fail(NCE_SELFTEST_DATA_ABORT, "guest did not reach the deliberate fault");
    }
    if ((hr & static_cast<u64>(HaltReason::DataAbort)) == 0) {
        fail(NCE_SELFTEST_DATA_ABORT, "access fault did not surface as DataAbort");
    }
    if (ctx.pc != fault_pc) {
        fail(NCE_SELFTEST_DATA_ABORT, "fault PC does not point at the faulting LDR");
    }

    // --- Interrupt protocol -------------------------------------------------------
    // Regression for the thread-parameters lock leak: a guest spinning in `b .` must be
    // broken out by SignalInterrupt from another thread (SIGURG -> BreakLoop), and a
    // SignalInterrupt aimed at the now idle core must return promptly instead of spinning
    // on a lock left behind by the previous guest exit.
    ran(NCE_SELFTEST_INTERRUPT);
    {
        using namespace std::chrono;

        ctx.pc = spin_pc;
        ctx.esr_el1.store(0);
        NCE_LOGI("NCE|SELFTEST interrupt step=spawn spin_pc=0x%llx", (unsigned long long)spin_pc);

        std::atomic<bool> run_done{false};
        std::atomic<int> signals_sent{0};
        std::thread interrupter([&] {
            // Keep poking until the run loop has returned: if a signal lands before guest
            // entry, RunThread returns the pending BreakLoop at once, which is also correct.
            for (int i = 0; i < 400 && !run_done.load(std::memory_order_acquire); i++) {
                std::this_thread::sleep_for(milliseconds(i == 0 ? 20 : 25));
                core.SignalInterrupt();
                signals_sent.fetch_add(1, std::memory_order_relaxed);
            }
        });

        const auto t_run = steady_clock::now();
        NCE_LOGI("NCE|SELFTEST interrupt step=run");
        hr = core.RunThread(0);
        run_done.store(true, std::memory_order_release);
        NCE_LOGI("NCE|SELFTEST interrupt step=returned hr=0x%llx pc=0x%llx", (unsigned long long)hr,
                 (unsigned long long)ctx.pc);
        interrupter.join();
        const auto run_ms = duration_cast<milliseconds>(steady_clock::now() - t_run).count();

        if ((hr & static_cast<u64>(HaltReason::BreakLoop)) == 0) {
            fail(NCE_SELFTEST_INTERRUPT, "spinning guest was not interrupted with BreakLoop");
        }
        if (ctx.pc != spin_pc) {
            fail(NCE_SELFTEST_INTERRUPT, "interrupted PC does not point at the spin loop");
        }
        if (run_ms > 5000) {
            fail(NCE_SELFTEST_INTERRUPT, "interrupt took more than 5 s to break the loop");
        }

        // Idle-core interrupt from another thread, with a watchdog so a regression shows
        // up as a FAIL rather than a hung emulator.
        NCE_LOGI("NCE|SELFTEST interrupt step=idle");
        std::atomic<bool> idle_done{false};
        std::thread idle_interrupter([&] {
            core.SignalInterrupt();
            idle_done.store(true, std::memory_order_release);
        });
        const auto t_idle = steady_clock::now();
        while (!idle_done.load(std::memory_order_acquire) &&
               steady_clock::now() - t_idle < seconds(2)) {
            std::this_thread::sleep_for(milliseconds(1));
        }
        if (!idle_done.load(std::memory_order_acquire)) {
            fail(NCE_SELFTEST_INTERRUPT, "SignalInterrupt on an idle core blocked (lock leaked across guest exit)");
            // Break the spinner so we can join it.
            nce_unlock_thread_parameters(&core.GetThreadParams());
        }
        idle_interrupter.join();

        // The pending BreakLoop must come back without entering the guest.
        NCE_LOGI("NCE|SELFTEST interrupt step=pending");
        const uint64_t before_pc = ctx.pc;
        hr = core.RunThread(0);
        if ((hr & static_cast<u64>(HaltReason::BreakLoop)) == 0 || ctx.pc != before_pc) {
            fail(NCE_SELFTEST_INTERRUPT, "pending BreakLoop was not returned immediately");
        }

        NCE_LOGI("NCE|SELFTEST interrupt hr=0x%llx run_ms=%lld signals=%d idle_ms=%lld",
                 (unsigned long long)hr, (long long)run_ms, signals_sent.load(),
                 (long long)duration_cast<milliseconds>(steady_clock::now() - t_idle).count());
    }

    // --- Cleanup ------------------------------------------------------------------
    ran(NCE_SELFTEST_CLEANUP);
    restore_globals();
    if (!UnmapScratch(scratch)) {
        fail(NCE_SELFTEST_CLEANUP, "could not restore scratch to PROT_NONE");
    }

    const bool ok = out_result->stages_failed == 0;
    NCE_LOGI("NCE|SELFTEST end %s ran=0x%x failed=0x%x svc=0x%x x0=0x%llx store=0x%llx align=0x%llx hr=0x%llx faultpc=0x%llx",
             ok ? "PASS" : "FAIL", out_result->stages_run, out_result->stages_failed,
             out_result->observed_svc_number, (unsigned long long)out_result->observed_svc_x0,
             (unsigned long long)out_result->observed_store_value,
             (unsigned long long)out_result->observed_alignment_value,
             (unsigned long long)out_result->observed_halt_reason,
             (unsigned long long)out_result->observed_fault_pc);
    return ok ? 0 : 1;
}
