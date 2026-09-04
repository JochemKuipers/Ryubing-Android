// SPDX-License-Identifier: MIT
//
// Guest fault handlers, called from the assembly signal handlers in nce.S.
// Ported from eden's arm_nce.cpp fault-handling section.
//
// Alignment faults: try the one-instruction load/store interpreter (Eden
// MatchAndExecuteOneInstruction). If decode/emulation fails, skip the
// faulting insn (failed-data-abort path).
//
// Access faults (SIGSEGV) call the managed page-fault handler when the
// address is inside the reserved identity window — that path drives
// MemoryTracking.VirtualMemoryEvent (GPU dirty / remapping), matching what
// NativeSignalHandler does for the JIT. The guest address space is identity
// mapped (guest VA == host pointer, Eden EnableDirectMappedAddress), so the
// faulting address needs no translation.

#include <cstring>
#include <cstdint>
#include <atomic>
#include <signal.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

#include <android/log.h>

#include "guest_context.h"
#include "core.h"
#include "host_mapped_memory.h"
#include "interpreter_visitor.h"
#include "nce.h"
#include "nce_log.h"

#ifndef ESR_MAGIC
#define ESR_MAGIC 0x45535201
#endif

// Implemented in guest_context.cpp (Eden-equivalent SaveGuestContext:
// save guest + restore host SP/callee-saved/PC/X0 in one step).
extern "C" {
void nce_save_guest_context(Ryubing::Nce::GuestContext* guest_ctx, void* raw_context);
}

namespace Ryubing::Nce {

// Original signal actions (chained to when a fault is not ours to handle).
struct sigaction g_orig_bus_action;
struct sigaction g_orig_segv_action;

// Identity-mapped guest window [base, base+size) + managed page-fault callback.
std::atomic<uint64_t> g_guest_host_base{0};
std::atomic<uint64_t> g_guest_as_size{0};
std::atomic<uintptr_t> g_page_fault_handler{0};

static constexpr uint64_t PageSize = 0x1000;
static constexpr uint64_t PageMask = PageSize - 1;

// Walk ucontext __reserved for esr_context; return WnR (bit 6) as 0/1.
// Falls back to "write" (1) if ESR is missing — safer for GPU remapping.
static int ExtractIsWrite(void* raw_context) {
    auto& host_ctx = static_cast<ucontext_t*>(raw_context)->uc_mcontext;
    _aarch64_ctx* header = reinterpret_cast<_aarch64_ctx*>(&host_ctx.__reserved);

    for (int i = 0; i < 64; i++) {
        if (header->magic == 0 || header->size == 0) {
            break;
        }
        if (header->magic == ESR_MAGIC) {
            const uint64_t esr = *reinterpret_cast<const uint64_t*>(
                reinterpret_cast<const char*>(header) + sizeof(_aarch64_ctx));
            return (esr & 0x40ull) != 0 ? 1 : 0;
        }
        header = reinterpret_cast<_aarch64_ctx*>(
            reinterpret_cast<char*>(header) + header->size);
    }
    return 1;
}

} // namespace Ryubing::Nce

extern "C" {

void nce_set_memory_config(uint64_t window_base, uint64_t window_size,
                           NcePageFaultHandler handler) {
    using namespace Ryubing::Nce;
    g_guest_host_base.store(window_base, std::memory_order_release);
    g_guest_as_size.store(window_size, std::memory_order_release);
    g_page_fault_handler.store(reinterpret_cast<uintptr_t>(handler), std::memory_order_release);
    NCE_LOGI("NCE|MEMCFG window=[0x%llx,0x%llx) handler=%d",
             (unsigned long long)window_base,
             (unsigned long long)(window_base + window_size),
             handler != nullptr ? 1 : 0);
}

bool nce_handle_failed_guest_fault(Ryubing::Nce::GuestContext* guest_ctx, void* raw_info,
                                   void* raw_context) {
    using namespace Ryubing::Nce;

    auto& host_ctx = static_cast<ucontext_t*>(raw_context)->uc_mcontext;
    auto* info = static_cast<siginfo_t*>(raw_info);

    const bool is_prefetch_abort = host_ctx.pc == reinterpret_cast<u64>(info->si_addr);

    NCE_LOG_ERROR("NCE|FAULT halt pc=0x%llx si_addr=0x%llx prefetch=%d",
                  (unsigned long long)host_ctx.pc,
                  (unsigned long long)reinterpret_cast<uintptr_t>(info->si_addr),
                  is_prefetch_abort ? 1 : 0);

    // Always return to the managed Execute loop. Skipping the faulting insn
    // (old Eden data-abort path) would hide the fault as a silent black-screen hang.
    guest_ctx->esr_el1.fetch_or(static_cast<u64>(
        is_prefetch_abort ? HaltReason::PrefetchAbort : HaltReason::DataAbort));

    if (guest_ctx->parent != nullptr) {
        guest_ctx->parent->GetThreadParams().lock.store(SpinLockLocked);
    }

    nce_save_guest_context(guest_ctx, raw_context);
    return false;
}

bool nce_handle_guest_alignment_fault(Ryubing::Nce::GuestContext* guest_ctx, void* raw_info,
                                      void* raw_context) {
    using namespace Ryubing::Nce;

    auto& host_ctx = static_cast<ucontext_t*>(raw_context)->uc_mcontext;
    auto* fpctx = GetFloatingPointState(host_ctx);

    const uint64_t host_base = g_guest_host_base.load(std::memory_order_acquire);
    const uint64_t as_size = g_guest_as_size.load(std::memory_order_acquire);
    HostMappedMemory memory(host_base, as_size);

    NCE_LOG_VERBOSE("alignment fault @ pc=0x%llx", (unsigned long long)host_ctx.pc);

    if (auto next_pc = MatchAndExecuteOneInstruction(memory, &host_ctx, fpctx)) {
        host_ctx.pc = *next_pc;
        return true;
    }

    static std::atomic<uint32_t> s_logged{0};
    if (s_logged.fetch_add(1, std::memory_order_relaxed) < 8) {
        NCE_LOGW("guest alignment fault: interpreter could not handle insn @ 0x%llx; aborting",
                 static_cast<unsigned long long>(host_ctx.pc));
    }
    return nce_handle_failed_guest_fault(guest_ctx, raw_info, raw_context);
}

bool nce_handle_guest_access_fault(Ryubing::Nce::GuestContext* guest_ctx, void* raw_info,
                                   void* raw_context) {
    using namespace Ryubing::Nce;

    auto* info = static_cast<siginfo_t*>(raw_info);
    auto& host_ctx = static_cast<ucontext_t*>(raw_context)->uc_mcontext;
    const uint64_t fault_addr = reinterpret_cast<uint64_t>(info->si_addr);

    const uint64_t window_base = g_guest_host_base.load(std::memory_order_acquire);
    const uint64_t window_size = g_guest_as_size.load(std::memory_order_acquire);
    auto handler = reinterpret_cast<NcePageFaultHandler>(
        g_page_fault_handler.load(std::memory_order_acquire));

    // Identity mapping (Eden HandleGuestAccessFault): the guest VA *is* the host
    // address, so si_addr is passed straight to the managed handler. The only
    // question is whether it lies inside the reserved window at all.
    const bool in_window = window_base != 0 && window_size != 0 &&
                           fault_addr >= window_base && fault_addr < window_base + window_size;

    NCE_LOG_VERBOSE("NCE|FAULT access pc=0x%llx addr=0x%llx in_window=%d",
                    (unsigned long long)host_ctx.pc,
                    (unsigned long long)fault_addr, in_window ? 1 : 0);

    if (handler != nullptr && in_window) {
        const uint64_t guest_va = fault_addr & ~PageMask;
        const int is_write = ExtractIsWrite(raw_context);
        if (handler(guest_va, PageSize, is_write) != 0) {
            // Detect infinite soft-fault retry loops (handler claims handled but the
            // page stays inaccessible): same PC faulting over and over.
            static std::atomic<uint64_t> s_last_pc{0};
            static std::atomic<uint32_t> s_same_pc{0};
            const uint64_t prev = s_last_pc.exchange(host_ctx.pc, std::memory_order_relaxed);
            uint32_t streak = 1;
            if (prev == host_ctx.pc) {
                streak = s_same_pc.fetch_add(1, std::memory_order_relaxed) + 1;
            } else {
                s_same_pc.store(1, std::memory_order_relaxed);
            }
            if (streak >= 64 && (streak == 64 || streak == 1024 || (streak % 4096) == 0)) {
                NCE_LOG_ERROR("NCE|FAULT storm pc=0x%llx addr=0x%llx count=%u action=DataAbort",
                              (unsigned long long)host_ctx.pc,
                              (unsigned long long)fault_addr,
                              streak);
                return nce_handle_failed_guest_fault(guest_ctx, raw_info, raw_context);
            }
            return true;
        }
    }

    if (!in_window) {
        static std::atomic<uint32_t> s_oob{0};
        if (s_oob.fetch_add(1, std::memory_order_relaxed) < 16) {
            NCE_LOG_ERROR("NCE|FAULT outside-window pc=0x%llx addr=0x%llx window=[0x%llx,0x%llx) "
                          "(wild pointer, or kernel placed a region outside the identity window)",
                          (unsigned long long)host_ctx.pc,
                          (unsigned long long)fault_addr,
                          (unsigned long long)window_base,
                          (unsigned long long)(window_base + window_size));
        }
    }

    return nce_handle_failed_guest_fault(guest_ctx, raw_info, raw_context);
}

void nce_handle_host_alignment_fault(int sig, void* raw_info, void* raw_context) {
    using namespace Ryubing::Nce;

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

    sigaction(sig, &g_orig_bus_action, nullptr);
    raise(sig);
}

void nce_handle_host_access_fault(int sig, void* raw_info, void* raw_context) {
    using namespace Ryubing::Nce;

    // Host-side SIGSEGV (TLS magic did not match: not guest code). The chained
    // handler is normally the .NET runtime's (null-reference -> managed exception)
    // or the crash dumper; either way record where we were, because a crash in the
    // NCE host path otherwise dies without a tombstone. Rate limited: the runtime
    // may use SIGSEGV for legitimate managed exceptions.
    {
        static std::atomic<int> s_logged{0};
        if (s_logged.fetch_add(1, std::memory_order_relaxed) < 8) {
            const auto& mctx = static_cast<ucontext_t*>(raw_context)->uc_mcontext;
            const auto* info = static_cast<siginfo_t*>(raw_info);
            NCE_LOG_ERROR("NCE|HOSTFAULT sig=%d code=%d addr=0x%llx pc=0x%llx lr=0x%llx sp=0x%llx x0=0x%llx tid=%d",
                          sig, info != nullptr ? info->si_code : 0,
                          (unsigned long long)(info != nullptr ? reinterpret_cast<uintptr_t>(info->si_addr) : 0),
                          (unsigned long long)mctx.pc, (unsigned long long)mctx.regs[30],
                          (unsigned long long)mctx.sp, (unsigned long long)mctx.regs[0], gettid());
        }
    }

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

    sigaction(sig, &g_orig_segv_action, nullptr);
    raise(sig);
}

} // extern "C"
