// SPDX-License-Identifier: MIT
//
// Guest context save/restore across signal frames. Called from the assembly
// signal handlers in nce.S. Ported from eden's arm_nce.cpp.

#include <cstring>
#include <ucontext.h>

#include "guest_context.h"

namespace Ryubing::Nce {

// Walk the ucontext_t __reserved array to find the fpsimd_context.
// The kernel appends tagged structures; we skip until FPSIMD_MAGIC.
static fpsimd_context* GetFloatingPointState(mcontext_t& host_ctx) {
    _aarch64_ctx* header = reinterpret_cast<_aarch64_ctx*>(&host_ctx.__reserved);
    while (header->magic != FPSIMD_MAGIC) {
        header = reinterpret_cast<_aarch64_ctx*>(reinterpret_cast<char*>(header) + header->size);
    }
    return reinterpret_cast<fpsimd_context*>(header);
}

} // namespace Ryubing::Nce

extern "C" {

// Called from nce_signal_handler_return_to_run (SIGUSR2 handler).
// Restores all guest registers into the signal frame so that returning
// from the handler enters guest code. Returns the tpidr (the caller sets
// TPIDR_EL0 to this so the guest can find its NativeExecutionParameters).
void* nce_restore_guest_context(void* raw_context) {
    using namespace Ryubing::Nce;

    // Retrieve the host context.
    auto& host_ctx = static_cast<ucontext_t*>(raw_context)->uc_mcontext;

    // Thread-local parameters will be located in x9 (saved by the asm caller).
    auto* tpidr = reinterpret_cast<NativeExecutionParameters*>(host_ctx.regs[9]);
    auto* guest_ctx = static_cast<GuestContext*>(tpidr->native_context);

    // Retrieve the host floating point state.
    auto* fpctx = GetFloatingPointState(host_ctx);

    // Save host callee-saved registers.
    std::memcpy(guest_ctx->host_ctx.host_saved_vregs.data(), &fpctx->vregs[8],
                sizeof(guest_ctx->host_ctx.host_saved_vregs));
    std::memcpy(guest_ctx->host_ctx.host_saved_regs.data(), &host_ctx.regs[19],
                sizeof(guest_ctx->host_ctx.host_saved_regs));

    // Save stack pointer.
    guest_ctx->host_ctx.host_sp = host_ctx.sp;

    // Restore all guest state except tpidr_el0.
    host_ctx.sp = guest_ctx->sp;
    host_ctx.pc = guest_ctx->pc;
    host_ctx.pstate = guest_ctx->pstate;
    fpctx->fpcr = guest_ctx->fpcr;
    fpctx->fpsr = guest_ctx->fpsr;
    std::memcpy(host_ctx.regs, guest_ctx->cpu_registers.data(), sizeof(host_ctx.regs));
    std::memcpy(fpctx->vregs, guest_ctx->vector_registers.data(), sizeof(fpctx->vregs));

    // Return the new thread-local storage pointer.
    return tpidr;
}

// Called from nce_signal_handler_break_from_run (SIGURG handler) and
// HandleFailedGuestFault. Saves all guest registers from the signal frame
// into the GuestContext so that returning from the handler enters host code.
void nce_save_guest_context(Ryubing::Nce::GuestContext* guest_ctx, void* raw_context) {
    using namespace Ryubing::Nce;

    // Retrieve the host context.
    auto& host_ctx = static_cast<ucontext_t*>(raw_context)->uc_mcontext;

    // Retrieve the host floating point state.
    auto* fpctx = GetFloatingPointState(host_ctx);

    // Save all guest registers except tpidr_el0.
    std::memcpy(guest_ctx->cpu_registers.data(), host_ctx.regs, sizeof(host_ctx.regs));
    std::memcpy(guest_ctx->vector_registers.data(), fpctx->vregs, sizeof(fpctx->vregs));
    guest_ctx->fpsr = fpctx->fpsr;
    guest_ctx->fpcr = fpctx->fpcr;
    guest_ctx->pstate = static_cast<u32>(host_ctx.pstate);
    guest_ctx->sp = host_ctx.sp;
    guest_ctx->pc = host_ctx.pc;
}

// Called from nce_signal_handler_break_from_run after nce_save_guest_context.
// Restores the host's saved state into the signal frame and returns the
// halt reason (from esr_el1) in x0.
void nce_return_to_host(Ryubing::Nce::GuestContext* guest_ctx, void* raw_context) {
    using namespace Ryubing::Nce;

    // Retrieve the host context.
    auto& host_ctx = static_cast<ucontext_t*>(raw_context)->uc_mcontext;

    // Retrieve the host floating point state.
    auto* fpctx = GetFloatingPointState(host_ctx);

    // Restore host callee-saved registers.
    std::memcpy(&host_ctx.regs[19], guest_ctx->host_ctx.host_saved_regs.data(),
                sizeof(guest_ctx->host_ctx.host_saved_regs));
    std::memcpy(&fpctx->vregs[8], guest_ctx->host_ctx.host_saved_vregs.data(),
                sizeof(guest_ctx->host_ctx.host_saved_vregs));

    // Return from the call on exit by setting pc to x30.
    host_ctx.pc = guest_ctx->host_ctx.host_saved_regs[11];

    // Clear esr_el1 and return it.
    host_ctx.regs[0] = guest_ctx->esr_el1.exchange(0);
}

} // extern "C"
