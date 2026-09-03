// SPDX-License-Identifier: MIT
//
// Fixed offsets and signal numbers shared between the patcher's generated
// code, the signal handlers, and the C# side (via NceNative.cs).
//
// Ported from eden's arm_nce_asm_definitions.h. These values are baked into
// generated machine code — changing them invalidates all patch caches.

#pragma once

#define __ASSEMBLY__

#include <asm-generic/signal.h>
#include <asm-generic/unistd.h>

// Signals used by the NCE run loop. Do not change: generated code and
// debugging workflows (LLDB "process handle SIGUSR2 ...") reference these.
#define ReturnToRunCodeByExceptionLevelChangeSignal SIGUSR2
#define BreakFromRunCodeSignal SIGURG
#define GuestAccessFaultSignal SIGSEGV
#define GuestAlignmentFaultSignal SIGBUS

// GuestContext member offsets (bytes from struct start).
#define GuestContextSp 0xF8
#define GuestContextHostContext 0x320

// HostContext member offsets (bytes from HostContext start).
#define HostContextSpTpidrEl0 0xE0
#define HostContextTpidrEl0 0xE8
#define HostContextRegs 0x0
#define HostContextVregs 0x60

// NativeExecutionParameters member offsets (bytes from struct start).
#define TpidrEl0NativeContext 0x10
#define TpidrEl0Lock 0x18
#define TpidrEl0TlsMagic 0x20

// TLS magic value identifying a valid NativeExecutionParameters.
#define TlsMagic 0x555a5559

// Spinlock values (NativeExecutionParameters::lock).
#define SpinLockLocked 0
#define SpinLockUnlocked 1
