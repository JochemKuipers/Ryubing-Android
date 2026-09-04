# Implementation Plan: NCE (Native Code Execution) for Ryubing-Android

## Overview

Adapt Eden's NCE architecture — guest ARM64 runs directly on host CPU cores via
binary patching and signal-handled fault management — into Ryubing-Android's C#
NativeAOT core as `libryubing-nce.so`, driven through P/Invoke from managed
`NceEngine` / `NceCpuContext`.

## Status (as of phase 6 complete)

| Phase | Status | Notes |
|-------|--------|-------|
| 0 Scaffolding | **Done** | CMake, oaknut, DT_NEEDED, ABI version, managed stubs |
| 1 Patcher | **Done** | Faithful Eden port |
| 2 Signals/context | **Done** | Handlers + bionic sigaction bypass |
| 3 Guest execution | **Done** | RunThread, interrupt, Execute + SVC via `ExceptionCallbacks` |
| 3.5 Correctness | **Done** | SaveGuestContext, halt-order, SP/V sync, SIGBUS oldact |
| 4 Engine + loader | **Done** | `UseNce` setting, factory select, LoadNsos + NRO patch |
| 5 Memory / faults | **Done** | Guest SIGSEGV → `MemoryTracking.VirtualMemoryEvent`; RX via existing Reprotect |
| 6 E2E polish | **Done** | Alignment interpreter + host tests; device smoke is manual |

NCE is **opt-in** (Settings → “Native Code Execution (NCE)”, default off). Requires
ARM64 host, HostMapped memory, 64-bit guest, and `libryubing-nce.so`.

ABI version: **3**. Version string: `ryubing-nce 0.7.1 (phase 6: alignment interpreter)`.

## What NCE Is (Eden ground truth)

1. **Patches guest code at module load** — SVC, MRS/MSR (TPIDR/CNT*),
   LDXR/STXR → ordered (WFI/WFE not patched).
2. **Runs guest on host CPU** — SIGUSR2 restores guest regs into `ucontext_t`.
3. **Faults via signals** — SIGSEGV → tracking remap / data abort; SIGBUS →
   one-insn load/store interpreter (skip if decode fails); SIGURG → break loop.
4. **SVC → HLE** via halt reason + `ExceptionCallbacks.SupervisorCallback`.
5. **Host-mapped guest VA** — guest pointers are host-dereferenceable.

## Research sources

| Repo | Path | Role |
|------|------|------|
| Eden | `research-material/eden/src/core/arm/nce/` | NCE algorithm / signals / patcher |
| Kenji-NX | `research-material/Kenji-NX` | `ICpuEngine` / HostMapped / Android build only |

## Architecture

```
┌──────────────────────────────────┐
│  libryubing.so (C# NativeAOT)    │
│  NceEngine / NceCpuContext       │
│  PageFault → MemoryTracking      │
│  NceModulePatcher → HLE loaders  │
└─────────────┬────────────────────┘
              │ P/Invoke
              ▼
┌──────────────────────────────────┐
│  libryubing-nce.so               │
│  Patcher / nce.S / signals       │
│  nce_set_memory_config           │
└──────────────────────────────────┘
```

HLE does not reference LibRyubing: AndroidHost injects `CpuEngineFactory` +
`INceModulePatcher` on `HleConfiguration`.

## Phase 5 — Memory + guest faults (completed)

1. **`nce_set_memory_config(host_base, as_size, handler)`** — registers the
   HostMapped base (`PageTablePointer`) and a managed page-fault callback.
2. **Guest SIGSEGV** (`nce_handle_guest_access_fault`): if `si_addr` is in
   `[host_base, host_base+as_size)`, page-align guest VA, extract WnR from
   ESR in `ucontext.__reserved`, call handler. On success, resume guest
   (Eden `InvalidateNCE` equivalent via `MemoryTracking.VirtualMemoryEvent`).
3. **Managed handler** in `NceCpuContext` — same role as JIT
   `NativeSignalHandler` → `TrackingEventDelegate` (already calls managed
   from a signal context).
4. **PROT_EXEC / RX** — already applied at load by
   `SetProcessMemoryPermission(ReadAndExecute)` → `KPageTable.Reprotect` →
   host `MemoryBlock.Reprotect` with Execute. No extra `mprotect` needed for
   HostMapped.
5. **Alignment interpreter** — ported (see Phase 6); skip only if decode fails.
6. **38-bit AS** — not changed; revisit only if HostMapped creation fails.
7. **Eden `DeferredMapSeparateHeap`** — Ryujinx HostMapped has no separate
   heap tracker; GPU remapping via `VirtualMemoryEvent` covers the practical
   Android fault case. Separate-heap deferral left for if/when Ryujinx gains
   an equivalent.

## Phase 6 — Integration polish (**done**)

1. **Host patcher unit test** — `tests/patcher_test.cpp` (`-DRYUBING_NCE_BUILD_TESTS=ON`):
   SVC/MRS(TPIDR+CNTPCT) trampolines, LDXR/STXR→ordered, CNTFRQ host-asm gated.
2. **Host interpreter unit test** — `tests/interpreter_test.cpp`: HostMapped byte-wise
   R/W + Dynarmic Decode of LDR/STR via `InterpreterVisitor`.
3. **PreText / Split safety** — `nce_patch_module` logs mode + growth warnings.
4. **Alignment interpreter** — Eden-faithful `MatchAndExecuteOneInstruction` with
   vendored dynarmic A64 decoder (`nce/third_party/`) + `HostMappedMemory` bridge;
   SIGBUS path tries interpret then falls back to skip.
5. **Device smoke (manual)** — debug APK with `ryubing-nce 0.7.1` installed on
   arm64 device; `use_nce=true` + HostMapped (`mem_mode=2`) set. Launch a
   title and confirm logcat shows `NCE load-time patching enabled` (full
   signal-chain coverage is on-device only).

## Key constraints

1. NativeAOT — P/Invoke only; no JNI from the AOT image.
2. Real libc `sigaction` (bionic ART bypass) — already done.
3. Exclusive with JIT signal handlers — NCE installs on top and chains host
   faults to the previous handler (`MemoryEhMeilleure` / NativeSignalHandler
   still receives non-guest faults).
4. Calling managed tracking from the signal path matches existing JIT practice.

## Risks

| Risk | Mitigation |
|------|------------|
| Managed code in signal handler | Same as JIT NativeSignalHandler; keep handler minimal |
| SIGSEGV chain conflicts | Guest TLS magic → NCE; else chain to prior |
| Missing tracking on MM type | Warn and fall back to skip/abort |

## Explicit non-goals (until later)

- Enabling NCE by default
- PTC / patched-image cache
- Porting Eden DeferredMapSeparateHeap wholesale
