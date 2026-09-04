// SPDX-License-Identifier: MIT
//
// NCE core: per-thread execution state and one-time signal setup.
//
// The signal handlers (nce.S) are installed once per process; each guest
// thread then sets up its own signal stack and NativeExecutionParameters
// (TPIDR_EL0 points there while the guest runs).

#pragma once

#include <atomic>
#include <memory>
#include <cstdint>

#include "guest_context.h"

namespace Ryubing::Nce {

// Signal stack size per thread (matches eden).
inline constexpr uint32_t SignalStackSize = 128 * 1024;

// One-time global initialization: installs SIGUSR2/SIGURG/SIGBUS/SIGSEGV
// handlers via the real libc sigaction (bypassing any ART signal chain on
// Android). Returns 0 on success.
int InstallSignalHandlers();

// Per-thread setup: allocates the alternate signal stack and calls
// sigaltstack. Must be called from the thread that will run guest code.
// Returns 0 on success.
int SetupThreadSignalStack();

// Forward declarations for assembly functions (implemented in nce.S).
// These "return" via the signal mechanism: the call site's X30 is saved in
// HostContext on the way into the guest, and the break/SVC handlers restore
// it (with X0 = halt reason) on the way back out.
extern "C" {
uint64_t nce_return_to_run_code_by_trampoline(void* tpidr, GuestContext* ctx, uint64_t trampoline_addr);
uint64_t nce_return_to_run_code_by_exception_level_change(int tid, void* tpidr);
void nce_lock_thread_parameters(void* tpidr);
void nce_unlock_thread_parameters(void* tpidr);
}

// Per-core execution state. One instance per emulated guest thread; the C#
// side drives it via the C ABI in nce.h.
//
// The core OWNS its NativeExecutionParameters (stable native address — the
// guest's TPIDR_EL0 points here while running, so it must never move, which
// rules out keeping it in GC-managed memory).
class NceCore {
public:
    NceCore();

    // Installs signal handlers (first core only) and the per-thread signal
    // stack. Call from the thread that will run guest code.
    int Initialize();

    // Runs guest code starting from the current GuestContext state.
    // Returns the halt reason (see HaltReason in guest_context.h).
    // trampoline_addr: address of a post-SVC re-entry trampoline to use
    // instead of the exception-level-change path (0 = use default).
    uint64_t RunThread(uint64_t trampoline_addr);

    // Signals the running guest thread to break out of the run loop.
    // Safe to call from another thread.
    void SignalInterrupt();

    GuestContext& GetGuestContext() { return m_guest_ctx; }
    const GuestContext& GetGuestContext() const { return m_guest_ctx; }

    NativeExecutionParameters& GetThreadParams() { return m_thread_params; }

    bool IsRunning() const { return m_is_running.load(std::memory_order_relaxed); }
    int ThreadId() const { return m_thread_id; }

private:
    int m_thread_id{-1};
    GuestContext m_guest_ctx{};
    NativeExecutionParameters m_thread_params{}; // Owned: TPIDR_EL0 target
    std::unique_ptr<uint8_t[]> m_signal_stack{};
    std::atomic<bool> m_is_running{false};
};

} // namespace Ryubing::Nce
