// SPDX-License-Identifier: MIT
//
// NCE core implementation: signal handler installation (with the Android
// bionic sigaction-chain workaround) and the per-core run loop.
//
// The bionic workaround: on Android, the ART runtime wraps sigaction() with
// its own signal-chain mechanism. Installing our handlers through the
// wrapper risks them being chained or preempted. We resolve the REAL libc
// sigaction via dlopen("libc.so")+dlsym and call it directly, matching
// eden's Common::SigAction.

#include <dlfcn.h>
#include <errno.h>
#include <mutex>
#include <signal.h>
#include <string.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

#include <android/log.h>

#include "core.h"
#include "guest_context.h"

#define LOG_TAG "RyubingNCE"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace Ryubing::Nce {

namespace {

using SigActionFn = int (*)(int, const struct sigaction*, struct sigaction*);

// Resolve the real libc sigaction, bypassing any ART signal chain on bionic.
SigActionFn GetRealSigAction() {
    static SigActionFn fn = []() -> SigActionFn {
#if defined(__BIONIC__)
        void* libc = dlopen("libc.so", RTLD_LAZY | RTLD_LOCAL);
        if (libc != nullptr) {
            void* sym = dlsym(libc, "sigaction");
            if (sym != nullptr) {
                return reinterpret_cast<SigActionFn>(sym);
            }
        }
        // Fall through to the default (may be the ART-wrapped version).
#endif
        return &sigaction;
    }();
    return fn;
}

int RealSigAction(int signum, const struct sigaction* act, struct sigaction* oldact) {
    return GetRealSigAction()(signum, act, oldact);
}

} // namespace

// Declared in signal_handlers.cpp (previous handlers we chain host faults to).
extern struct sigaction g_orig_bus_action;
extern struct sigaction g_orig_segv_action;

// Assembly handler entry points (implemented in nce.S).
extern "C" {
void nce_signal_handler_return_to_run(int sig, void* info, void* raw_context);
void nce_signal_handler_break_from_run(int sig, void* info, void* raw_context);
void nce_signal_handler_alignment_fault(int sig, void* info, void* raw_context);
void nce_signal_handler_access_fault(int sig, void* info, void* raw_context);
}

int InstallSignalHandlers() {
    static std::once_flag flag;
    static int result = -1;

    std::call_once(flag, [] {
        using HandlerType = decltype(sigaction::sa_sigaction);

        // Mask all NCE signals inside the handlers so they don't nest.
        sigset_t signal_mask;
        sigemptyset(&signal_mask);
        sigaddset(&signal_mask, ReturnToRunCodeByExceptionLevelChangeSignal);
        sigaddset(&signal_mask, BreakFromRunCodeSignal);
        sigaddset(&signal_mask, GuestAlignmentFaultSignal);
        sigaddset(&signal_mask, GuestAccessFaultSignal);

        // SIGUSR2: return to guest code (exception-level-change mechanism).
        struct sigaction return_to_run_action {};
        return_to_run_action.sa_flags = SA_SIGINFO | SA_ONSTACK;
        return_to_run_action.sa_sigaction =
            reinterpret_cast<HandlerType>(&nce_signal_handler_return_to_run);
        return_to_run_action.sa_mask = signal_mask;
        if (RealSigAction(ReturnToRunCodeByExceptionLevelChangeSignal,
                          &return_to_run_action, nullptr) != 0) {
            LOGE("Failed to install SIGUSR2 handler: %s", strerror(errno));
            result = -1;
            return;
        }

        // SIGURG: break from run code (scheduler interrupt).
        struct sigaction break_action {};
        break_action.sa_flags = SA_SIGINFO | SA_ONSTACK;
        break_action.sa_sigaction =
            reinterpret_cast<HandlerType>(&nce_signal_handler_break_from_run);
        break_action.sa_mask = signal_mask;
        if (RealSigAction(BreakFromRunCodeSignal, &break_action, nullptr) != 0) {
            LOGE("Failed to install SIGURG handler: %s", strerror(errno));
            result = -1;
            return;
        }

        // SIGBUS: guest alignment fault (or host — TLS magic decides).
        // Save the original action so we can chain host faults to it.
        struct sigaction alignment_action {};
        alignment_action.sa_flags = SA_SIGINFO | SA_ONSTACK;
        alignment_action.sa_sigaction =
            reinterpret_cast<HandlerType>(&nce_signal_handler_alignment_fault);
        alignment_action.sa_mask = signal_mask;
        if (RealSigAction(GuestAlignmentFaultSignal, &alignment_action,
                          &g_orig_bus_action) != 0) {
            LOGE("Failed to install SIGBUS handler: %s", strerror(errno));
            result = -1;
            return;
        }

        // SIGSEGV: guest access fault (or host — TLS magic decides).
        // Save the original action so we can chain host faults to it.
        struct sigaction access_action {};
        access_action.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESTART;
        access_action.sa_sigaction =
            reinterpret_cast<HandlerType>(&nce_signal_handler_access_fault);
        access_action.sa_mask = signal_mask;
        if (RealSigAction(GuestAccessFaultSignal, &access_action, &g_orig_segv_action) != 0) {
            LOGE("Failed to install SIGSEGV handler: %s", strerror(errno));
            result = -1;
            return;
        }

        LOGI("NCE signal handlers installed (SIGUSR2, SIGURG, SIGBUS, SIGSEGV)");
        result = 0;
    });

    return result;
}

int SetupThreadSignalStack() {
    // Each guest thread needs its own alternate signal stack so the
    // handlers can run even if the guest exhausts the normal stack.
    // Allocation is intentionally leaked: sigaltstack references it for
    // the lifetime of the thread, and cores may be destroyed while the
    // thread is still alive.
    auto* stack = new uint8_t[SignalStackSize];

    stack_t ss{};
    ss.ss_sp = stack;
    ss.ss_size = SignalStackSize;
    ss.ss_flags = 0;
    if (sigaltstack(&ss, nullptr) != 0) {
        LOGE("sigaltstack failed: %s", strerror(errno));
        delete[] stack;
        return -1;
    }
    return 0;
}

NceCore::NceCore() = default;

int NceCore::Initialize() {
    if (m_thread_id == -1) {
        m_thread_id = gettid();
    }

    if (InstallSignalHandlers() != 0) {
        return -1;
    }

    // Single ownership: only the core sets up the altstack for this thread.
    // Callers must not also call nce_thread_init() beforehand.
    if (!m_signal_stack_ready) {
        if (SetupThreadSignalStack() != 0) {
            return -1;
        }
        m_signal_stack_ready = true;
    }

    return 0;
}

void NceCore::SignalInterrupt() {
    m_guest_ctx.esr_el1.fetch_or(static_cast<u64>(HaltReason::BreakLoop));

    nce_lock_thread_parameters(&m_thread_params);

    std::atomic_thread_fence(std::memory_order_acquire);

    if (m_thread_params.is_running) {
        syscall(SYS_tkill, m_thread_id, BreakFromRunCodeSignal);
    } else {
        nce_unlock_thread_parameters(&m_thread_params);
    }
}

uint64_t NceCore::RunThread(uint64_t trampoline_addr) {
    // Check if we're already interrupted. If so, return immediately.
    uint64_t hr = m_guest_ctx.esr_el1.exchange(0);
    if (hr != 0) {
        return hr;
    }

    // Cache the TLS values before the critical section.
    const uint64_t tpidr_el0_cache = m_guest_ctx.tpidr_el0;
    const uint64_t tpidrro_el0_cache = m_guest_ctx.tpidrro_el0;

    // Critical section begins: publish the context to the thread params so
    // signal handlers can find it via TPIDR_EL0.
    m_guest_ctx.parent = this;
    m_thread_params.native_context = &m_guest_ctx;
    m_thread_params.tpidr_el0 = tpidr_el0_cache;
    m_thread_params.tpidrro_el0 = tpidrro_el0_cache;

    // Memory barrier to ensure visibility of changes.
    std::atomic_thread_fence(std::memory_order_release);
    m_thread_params.is_running = true;
    m_is_running.store(true, std::memory_order_relaxed);

    // Run the guest. Both paths "return" with X0 = halt reason when the
    // guest exits (SVC trampoline or break signal handler restores the host
    // context with the return address and halt reason in X0).
    if (trampoline_addr != 0) {
        hr = nce_return_to_run_code_by_trampoline(&m_thread_params, &m_guest_ctx, trampoline_addr);
    } else {
        hr = nce_return_to_run_code_by_exception_level_change(m_thread_id, &m_thread_params);
    }

    // Critical section for thread cleanup.
    std::atomic_thread_fence(std::memory_order_acquire);

    // Cache values before releasing the thread.
    const uint64_t final_tpidr_el0 = m_thread_params.tpidr_el0;

    m_thread_params.is_running = false;
    m_thread_params.native_context = nullptr;
    m_is_running.store(false, std::memory_order_relaxed);

    // Non-critical updates can happen after releasing the thread.
    m_guest_ctx.tpidr_el0 = final_tpidr_el0;

    return hr;
}

} // namespace Ryubing::Nce

