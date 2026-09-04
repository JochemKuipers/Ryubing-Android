// SPDX-License-Identifier: MIT
//
// Guest/host CPU context structures for NCE.
//
// Ported from eden's src/core/arm/nce/guest_context.h and the
// NativeExecutionParameters struct from k_thread.h. These are shared
// between the patcher's generated code (which references them by fixed
// offsets — see asm_defs.h) and the C++ signal handlers, so the layout
// must not change without updating the assembly definitions.

#pragma once

#include <atomic>
#include <array>
#include <cstddef>
#include <cstdint>

#include "asm_defs.h"

#if defined(__aarch64__)
#include <ucontext.h>
#if defined(__linux__) || defined(__ANDROID__)
#include <asm/sigcontext.h>
#endif
#endif

namespace Ryubing::Nce {

#if defined(__aarch64__)
// Exposed for the alignment-fault interpreter (signal path).
fpsimd_context* GetFloatingPointState(mcontext_t& host_ctx);
#endif

using u32 = std::uint32_t;
using u64 = std::uint64_t;
using u128 = __uint128_t;

// Forward declaration (defined in core.h, phase 3).
struct NceCore;

// Halt reasons returned by the guest run loop. These match the bit layout
// used by the patcher's generated code (stored in GuestContext::esr_el1).
enum class HaltReason : u64 {
    StepThread = 0x00000001,
    DataAbort = 0x00000004,
    BreakLoop = 0x02000000,
    SupervisorCall = 0x04000000,
    InstructionBreakpoint = 0x08000000,
    PrefetchAbort = 0x20000000,
};

inline constexpr HaltReason operator|(HaltReason a, HaltReason b) {
    return static_cast<HaltReason>(static_cast<u64>(a) | static_cast<u64>(b));
}
inline constexpr HaltReason operator&(HaltReason a, HaltReason b) {
    return static_cast<HaltReason>(static_cast<u64>(a) & static_cast<u64>(b));
}
inline constexpr bool True(HaltReason a) {
    return static_cast<u64>(a) != 0;
}

// Host state saved when entering the guest and restored when exiting.
// Referenced by patcher-generated code via HostContext* offsets.
struct HostContext {
    alignas(16) std::array<u64, 12> host_saved_regs{};   // X19-X30 (callee-saved)
    alignas(16) std::array<u128, 8> host_saved_vregs{};  // V8-V15 (callee-saved)
    u64 host_sp{};
    void* host_tpidr_el0{};
};

// Guest CPU state. The patcher's save/load context stubs and the signal
// handlers read/write this struct at fixed offsets (see asm_defs.h).
struct GuestContext {
    std::array<u64, 31> cpu_registers{};  // X0-X30
    u64 sp{};
    u64 pc{};
    u32 fpcr{};
    u32 fpsr{};
    std::array<u128, 32> vector_registers{}; // V0-V31
    u32 pstate{};
    alignas(16) HostContext host_ctx{};
    u64 tpidrro_el0{};
    u64 tpidr_el0{};
    std::atomic<u64> esr_el1{};  // halt reason accumulator
    u32 nzcv{};
    u32 svc{};                   // SVC number being dispatched
    NceCore* parent{};           // owning core (for signal handlers)
};

// Per-thread parameters placed at a known address; the guest's TPIDR_EL0
// points here while running (the patcher reads it to reach the context).
// Layout must match asm_defs.h offsets exactly.
struct NativeExecutionParameters {
    u64 tpidr_el0{};          // +0x00 guest TLS pointer
    u64 tpidrro_el0{};        // +0x08 guest read-only TLS
    void* native_context{};   // +0x10 -> GuestContext
    std::atomic<u32> lock{1}; // +0x18 spinlock (1 = unlocked, 0 = locked)
    bool is_running{};        // +0x1C
    u32 magic{TlsMagic};      // +0x20 guard value
};

// Verify assembly offsets.
static_assert(offsetof(GuestContext, sp) == GuestContextSp);
static_assert(offsetof(GuestContext, host_ctx) == GuestContextHostContext);
static_assert(offsetof(HostContext, host_sp) == HostContextSpTpidrEl0);
static_assert(offsetof(HostContext, host_tpidr_el0) - 8 == HostContextSpTpidrEl0);
static_assert(offsetof(HostContext, host_tpidr_el0) == HostContextTpidrEl0);
static_assert(offsetof(HostContext, host_saved_regs) == HostContextRegs);
static_assert(offsetof(HostContext, host_saved_vregs) == HostContextVregs);
static_assert(offsetof(NativeExecutionParameters, native_context) == TpidrEl0NativeContext);
static_assert(offsetof(NativeExecutionParameters, lock) == TpidrEl0Lock);
static_assert(offsetof(NativeExecutionParameters, magic) == TpidrEl0TlsMagic);

} // namespace Ryubing::Nce
