// SPDX-License-Identifier: MIT
//
// Guest fault handlers, called from the assembly signal handlers in nce.S.
// Ported from eden's arm_nce.cpp fault-handling section.
//
// Phase 2 note: HandleGuestAlignmentFault currently falls back to
// "skip instruction" behavior (same as eden's HandleFailedGuestFault data
// path). The one-instruction interpreter (MatchAndExecuteOneInstruction,
// which needs the dynarmic frontend decoder) lands in a later phase.

#include <cstring>
#include <cstdint>
#include <signal.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

#include <android/log.h>

#include "guest_context.h"

#define LOG_TAG "RyubingNCE"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Implemented in guest_context.cpp.
extern "C" {
void nce_save_guest_context(Ryubing::Nce::GuestContext* guest_ctx, void* raw_context);
void nce_return_to_host(Ryubing::Nce::GuestContext* guest_ctx, void* raw_context);
}

namespace Ryubing::Nce {

// Original signal actions (chained to when a fault is not ours to handle).
struct sigaction g_orig_bus_action;
struct sigaction g_orig_segv_action;

} // namespace Ryubing::Nce

extern "C" {

// Returns true if the fault was handled (guest should resume), false if the
// guest should exit to host. For data aborts: skip the instruction. For
// prefetch aborts: mark the halt reason and save the guest context.
bool nce_handle_failed_guest_fault(Ryubing::Nce::GuestContext* guest_ctx, void* raw_info,
                                   void* raw_context) {
    using namespace Ryubing::Nce;

    auto& host_ctx = static_cast<ucontext_t*>(raw_context)->uc_mcontext;
    auto* info = static_cast<siginfo_t*>(raw_info);

    const bool is_prefetch_abort = host_ctx.pc == reinterpret_cast<u64>(info->si_addr);

    // For data aborts, skip the instruction and return to guest code.
    if (!is_prefetch_abort) {
        host_ctx.pc += 4;
        return true;
    }

    // This is a prefetch abort: mark and return to host.
    guest_ctx->esr_el1.fetch_or(static_cast<u64>(HaltReason::PrefetchAbort));
    guest_ctx->esr_el1.fetch_or(static_cast<u64>(HaltReason::BreakLoop));

    // Return to host: save the guest context into the signal frame so the
    // handler returns into host code.
    nce_save_guest_context(guest_ctx, raw_context);
    nce_return_to_host(guest_ctx, raw_context);
    return false;
}

// Called from nce_signal_handler_alignment_fault (SIGBUS). Attempts to
// execute the faulting instruction via the interpreter; currently falls
// back to skip (phase 2 TODO: wire MatchAndExecuteOneInstruction).
bool nce_handle_guest_alignment_fault(Ryubing::Nce::GuestContext* guest_ctx, void* raw_info,
                                      void* raw_context) {
    // TODO(phase 2.5): decode and execute the single instruction at the
    // fault PC via the dynarmic-frontend interpreter visitor, then advance
    // host_ctx.pc by 4 and return true.
    return nce_handle_failed_guest_fault(guest_ctx, raw_info, raw_context);
}

// Called from nce_signal_handler_access_fault (SIGSEGV). Attempts to
// page-in the faulting address (GPU-dirty memory remap); falls back to the
// failed-fault path.
bool nce_handle_guest_access_fault(Ryubing::Nce::GuestContext* guest_ctx, void* raw_info,
                                   void* raw_context) {
    // TODO(phase 4): check whether the fault address is in the guest address
    // space and whether it is GPU-dirty; if so, call the memory manager's
    // InvalidateNCE path (deferred page map) and return true.
    auto* info = static_cast<siginfo_t*>(raw_info);
    (void)info;
    return nce_handle_failed_guest_fault(guest_ctx, raw_info, raw_context);
}

// Chains SIGBUS to the previously-installed handler (host fault, not ours).
void nce_handle_host_alignment_fault(int sig, void* raw_info, void* raw_context) {
    using namespace Ryubing::Nce;

    // Compare handler addresses as integers (sa_sigaction and SIG_DFL/SIG_IGN
    // have different function-pointer types on bionic).
    const auto sigaction_addr = reinterpret_cast<uintptr_t>(g_orig_bus_action.sa_sigaction);
    const auto handler_addr = reinterpret_cast<uintptr_t>(g_orig_bus_action.sa_handler);
    const auto dfl_addr = reinterpret_cast<uintptr_t>(SIG_DFL);
    const auto ign_addr = reinterpret_cast<uintptr_t>(SIG_IGN);

    if (g_orig_bus_action.sa_flags & SA_SIGINFO) {
        if (sigaction_addr != 0 && sigaction_addr != dfl_addr && sigaction_addr != ign_addr) {
            g_orig_bus_action.sa_sigaction(sig, static_cast<siginfo_t*>(raw_info), raw_context);
            return;
        }
    } else if (handler_addr != 0 && handler_addr != dfl_addr && handler_addr != ign_addr) {
        g_orig_bus_action.sa_handler(sig);
        return;
    }

    // No previous handler: restore default and re-raise.
    sigaction(sig, &g_orig_bus_action, nullptr);
    raise(sig);
}

// Chains SIGSEGV to the previously-installed handler (host fault, not ours).
void nce_handle_host_access_fault(int sig, void* raw_info, void* raw_context) {
    using namespace Ryubing::Nce;

    const auto sigaction_addr = reinterpret_cast<uintptr_t>(g_orig_segv_action.sa_sigaction);
    const auto handler_addr = reinterpret_cast<uintptr_t>(g_orig_segv_action.sa_handler);
    const auto dfl_addr = reinterpret_cast<uintptr_t>(SIG_DFL);
    const auto ign_addr = reinterpret_cast<uintptr_t>(SIG_IGN);

    if (g_orig_segv_action.sa_flags & SA_SIGINFO) {
        if (sigaction_addr != 0 && sigaction_addr != dfl_addr && sigaction_addr != ign_addr) {
            g_orig_segv_action.sa_sigaction(sig, static_cast<siginfo_t*>(raw_info), raw_context);
            return;
        }
    } else if (handler_addr != 0 && handler_addr != dfl_addr && handler_addr != ign_addr) {
        g_orig_segv_action.sa_handler(sig);
        return;
    }

    // No previous handler: restore default and re-raise.
    sigaction(sig, &g_orig_segv_action, nullptr);
    raise(sig);
}

} // extern "C"

