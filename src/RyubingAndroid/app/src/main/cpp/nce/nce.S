/* SPDX-License-Identifier: MIT
 *
 * Assembly-level signal handlers and execution entry points for NCE.
 *
 * Ported from eden's src/core/arm/nce/arm_nce.s with plain C symbol names
 * (extern "C") instead of mangled C++ names. The handlers find the current
 * guest context via TPIDR_EL0 -> NativeExecutionParameters -> native_context.
 *
 * Included by the build as a preprocessed assembly file (.S).
 */

#include "asm_defs.h"

#define LOAD_IMMEDIATE_32(reg, val)                     \
    mov     reg, #(((val) >> 0x00) & 0xFFFF);           \
    movk    reg, #(((val) >> 0x10) & 0xFFFF), lsl #16


/* void nce_return_to_run_code_by_trampoline(void* tpidr, GuestContext* ctx, u64 trampoline_addr) */
.section    .text.nce_return_to_run_code_by_trampoline, "ax", %progbits
.global     nce_return_to_run_code_by_trampoline
.type       nce_return_to_run_code_by_trampoline, %function
nce_return_to_run_code_by_trampoline:
    /* Back up host sp to x3. */
    /* Back up host tpidr_el0 to x4. */
    mov     x3, sp
    mrs     x4, tpidr_el0

    /* Load guest sp. x5 is used as a scratch register. */
    ldr     x5, [x1, #(GuestContextSp)]
    mov     sp, x5

    /* Offset GuestContext pointer to the host member. */
    add     x5, x1, #(GuestContextHostContext)

    /* Save original host sp and tpidr_el0 (x3, x4) to host context. */
    stp     x3, x4, [x5, #(HostContextSpTpidrEl0)]

    /* Save all callee-saved host GPRs. */
    stp     x19, x20, [x5, #(HostContextRegs+0x0)]
    stp     x21, x22, [x5, #(HostContextRegs+0x10)]
    stp     x23, x24, [x5, #(HostContextRegs+0x20)]
    stp     x25, x26, [x5, #(HostContextRegs+0x30)]
    stp     x27, x28, [x5, #(HostContextRegs+0x40)]
    stp     x29, x30, [x5, #(HostContextRegs+0x50)]

    /* Save all callee-saved host FPRs. */
    stp     q8, q9,   [x5, #(HostContextVregs+0x0)]
    stp     q10, q11, [x5, #(HostContextVregs+0x20)]
    stp     q12, q13, [x5, #(HostContextVregs+0x40)]
    stp     q14, q15, [x5, #(HostContextVregs+0x60)]

    /* Load guest tpidr_el0 from argument. */
    msr     tpidr_el0, x0

    /* Tail call the trampoline to restore guest state. */
    br      x2


/* void nce_return_to_run_code_by_exception_level_change(int tid, void* tpidr) */
.section    .text.nce_return_to_run_code_by_exception_level_change, "ax", %progbits
.global     nce_return_to_run_code_by_exception_level_change
.type       nce_return_to_run_code_by_exception_level_change, %function
nce_return_to_run_code_by_exception_level_change:
    /* This sends SIGUSR2 to our own thread; the signal handler restores the
     * entire guest context from the signal frame. On entry, x0 = thread id. */

    /* Move tpidr to x9 so it is not trampled. */
    mov     x9, x1

    /* Set up arguments: tkill(tid, SIGUSR2). */
    mov     x8, #(__NR_tkill)
    mov     x1, #(ReturnToRunCodeByExceptionLevelChangeSignal)

    /* Tail call the signal handler via tkill syscall. */
    svc     #0

    /* Block execution from flowing here. */
    brk     #1000

/* void nce_signal_handler_return_to_run(int sig, void* info, void* raw_context) */
.section    .text.nce_signal_handler_return_to_run, "ax", %progbits
.global     nce_signal_handler_return_to_run
.type       nce_signal_handler_return_to_run, %function
nce_signal_handler_return_to_run:
    stp     x29, x30, [sp, #-0x10]!
    mov     x29, sp

    /* Call the context restorer with the raw context. */
    mov     x0, x2
    bl      nce_restore_guest_context

    /* Save the old value of tpidr_el0. */
    mrs     x8, tpidr_el0
    ldr     x9, [x0, #(TpidrEl0NativeContext)]
    str     x8, [x9, #(GuestContextHostContext + HostContextTpidrEl0)]

    /* Set our new tpidr_el0. */
    msr     tpidr_el0, x0

    /* Unlock the context. */
    bl      nce_unlock_thread_parameters

    /* Returning from here will enter the guest. */
    ldp     x29, x30, [sp], #0x10
    ret


/* void nce_signal_handler_break_from_run(int sig, void* info, void* raw_context) */
.section    .text.nce_signal_handler_break_from_run, "ax", %progbits
.global     nce_signal_handler_break_from_run
.type       nce_signal_handler_break_from_run, %function
nce_signal_handler_break_from_run:
    /* Check to see if we have the correct TLS magic. */
    mrs     x8, tpidr_el0
    ldr     w9, [x8, #(TpidrEl0TlsMagic)]

    LOAD_IMMEDIATE_32(w10, TlsMagic)

    cmp     w9, w10
    b.ne    1f

    /* Correct TLS magic, so this is a guest interrupt. */
    /* Restore host tpidr_el0. */
    ldr     x0, [x8, #(TpidrEl0NativeContext)]
    ldr     x3, [x0, #(GuestContextHostContext + HostContextTpidrEl0)]
    msr     tpidr_el0, x3

    /* Tail call SaveGuestContext(ctx, raw_context): saves guest state and
     * restores host SP/callee-saved/PC/X0 so returning from the handler
     * re-enters host code (matches Eden ArmNce::SaveGuestContext). */
    mov     x1, x2
    b       nce_save_guest_context

    /* Returning from here will enter host code. */

1:
    /* Incorrect TLS magic, so this is a spurious signal. */
    ret


/* void nce_signal_handler_alignment_fault(int sig, void* info, void* raw_context) */
.section    .text.nce_signal_handler_alignment_fault, "ax", %progbits
.global     nce_signal_handler_alignment_fault
.type       nce_signal_handler_alignment_fault, %function
nce_signal_handler_alignment_fault:
    /* Check to see if we have the correct TLS magic. */
    mrs     x8, tpidr_el0
    ldr     w9, [x8, #(TpidrEl0TlsMagic)]

    LOAD_IMMEDIATE_32(w10, TlsMagic)

    cmp     w9, w10
    b.eq    1f

    /* Incorrect TLS magic, so this is a host fault. */
    /* Tail call the handler. */
    b       nce_handle_host_alignment_fault

1:
    /* Correct TLS magic, so this is a guest fault. */
    stp     x29, x30, [sp, #-0x20]!
    str     x19, [sp, #0x10]
    mov     x29, sp

    /* Save the old tpidr_el0. */
    mov     x19, x8

    /* Restore host tpidr_el0. */
    ldr     x0, [x8, #(TpidrEl0NativeContext)]
    ldr     x3, [x0, #(GuestContextHostContext + HostContextTpidrEl0)]
    msr     tpidr_el0, x3

    /* Call the handler. */
    bl      nce_handle_guest_alignment_fault

    /* If the handler returned false, we want to preserve the host tpidr_el0. */
    cbz     x0, 2f

    /* Otherwise, restore guest tpidr_el0. */
    msr     tpidr_el0, x19

2:
    ldr     x19, [sp, #0x10]
    ldp     x29, x30, [sp], #0x20
    ret


/* void nce_signal_handler_access_fault(int sig, void* info, void* raw_context) */
.section    .text.nce_signal_handler_access_fault, "ax", %progbits
.global     nce_signal_handler_access_fault
.type       nce_signal_handler_access_fault, %function
nce_signal_handler_access_fault:
    /* Check to see if we have the correct TLS magic. */
    mrs     x8, tpidr_el0
    ldr     w9, [x8, #(TpidrEl0TlsMagic)]

    LOAD_IMMEDIATE_32(w10, TlsMagic)

    cmp     w9, w10
    b.eq    1f

    /* Incorrect TLS magic, so this is a host fault. */
    /* Tail call the handler. */
    b       nce_handle_host_access_fault

1:
    /* Correct TLS magic, so this is a guest fault. */
    stp     x29, x30, [sp, #-0x20]!
    str     x19, [sp, #0x10]
    mov     x29, sp

    /* Save the old tpidr_el0. */
    mov     x19, x8

    /* Restore host tpidr_el0. */
    ldr     x0, [x8, #(TpidrEl0NativeContext)]
    ldr     x3, [x0, #(GuestContextHostContext + HostContextTpidrEl0)]
    msr     tpidr_el0, x3

    /* Call the handler. */
    bl      nce_handle_guest_access_fault

    /* If the handler returned false, we want to preserve the host tpidr_el0. */
    cbz     x0, 2f

    /* Otherwise, restore guest tpidr_el0. */
    msr     tpidr_el0, x19

2:
    ldr     x19, [sp, #0x10]
    ldp     x29, x30, [sp], #0x20
    ret


/* void nce_lock_thread_parameters(void* tpidr) */
.section    .text.nce_lock_thread_parameters, "ax", %progbits
.global     nce_lock_thread_parameters
.type       nce_lock_thread_parameters, %function
nce_lock_thread_parameters:
    /* Offset to lock member. */
    add     x0, x0, #(TpidrEl0Lock)

1:
    /* Clear the monitor. */
    clrex

2:
    /* Load-linked with acquire ordering. */
    ldaxr   w1, [x0]

    /* If the value was SpinLockLocked, clear monitor and retry. */
    cbz     w1, 1b

    /* Store-conditional SpinLockLocked with relaxed ordering. */
    stxr    w1, wzr, [x0]

    /* If we failed to store, retry. */
    cbnz    w1, 2b

    ret


/* void nce_unlock_thread_parameters(void* tpidr) */
.section    .text.nce_unlock_thread_parameters, "ax", %progbits
.global     nce_unlock_thread_parameters
.type       nce_unlock_thread_parameters, %function
nce_unlock_thread_parameters:
    /* Offset to lock member. */
    add     x0, x0, #(TpidrEl0Lock)

    /* Load SpinLockUnlocked. */
    mov     w1, #(SpinLockUnlocked)

    /* Store value with release ordering. */
    stlr    w1, [x0]

    ret

