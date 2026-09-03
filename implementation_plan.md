# Implementation Plan: NCE (Native Code Execution) for Ryubing-Android

## Overview

Adapt eden's NCE architecture — where guest ARM64 code runs directly on host CPU cores via binary patching and signal-handled fault management — into Ryubing-Android's C# NativeAOT emulation core, creating a new `libryubing-nce.so` native library managed through P/Invoke callbacks from the managed host.

## Context & Scope

### What NCE Is (from eden's ground truth)

Instead of recompiling guest ARM64 via a JIT (ARMeilleure/Dynarmic), NCE:

1. **Patches guest code at module load time** — replaces SVC, MRS/MSR, LDXR/STXR, and WFI/WFE with branches to a small patch section that context-switches into host handlers
2. **Runs guest code directly on the CPU** — sets guest PC, SP, registers into a ucontext_t and returns from a signal handler, causing the kernel to resume execution at the guest instruction
3. **Handles faults via signals** — SIGSEGV on guest memory → guest data abort, SIGBUS on unaligned access → one-instruction interpreter fallback, SIGURG → break loop (interrupt)
4. **Traps SVC into the HLE kernel** — patched SVC instructions save guest context and return to the emulator's scheduler, where the SVC number is read
5. **Maintains a flat host-side mapping of guest physical memory** so guest code pointers are directly dereferenceable by the host CPU

### Current State of Ryubing-Android

- Emulation core is C# NativeAOT (`libryubing.so`) using ARMeilleure (C# JIT) → guest ARM64 → host ARM64 recompilation
- JNI/C++ shim (`libryubingjni.so`) handles only Android surface + Vulkan driver loading
- `ICpuEngine` / `ICpuContext` is the CPU abstraction — currently only `JitEngine` → `JitCpuContext`
- Memory manager (`IMemoryManager`) already supports host-mapped strategies that use mmap for guest address space mirroring
- Build system: Gradle → `dotnet publish` for libryubing.so, cmake for libryubingjni.so

### What Kenji-NX libryujinx_bionic Provides

Kenji-NX's bionic branch contains NO NCE implementation — it's a conventional Ryujinx desktop fork with an Android UI wrapper. Its value is in showing:

- The `Ryujinx.Cpu` project structure (`ICpuEngine`, `ICpuContext`, `IExecutionContext`)
- How the JIT path wires through `JitEngine` → `JitCpuContext` → ARMeilleure `Translator`
- The Android build integration for `LibKenjinx` (the C# project analogous to `LibRyubing`)
- How NativeAOT P/Invoke to JNI/C++ shims works in practice

### Key Constraints

1. **NativeAOT has no JNI or JVM** — the C# AOT image cannot call back into Java/Kotlin directly; must use callbacks provided by the C++ shim layer
2. **Bionic libc** — Android's libc lacks some POSIX features; eden already works around this (e.g., `DeferredMapSeparateHeap` for Android memory handling)
3. **Signal chain** — Android's `sigaction` must not conflict with existing signal handlers (NativeAOT runtime, adrenotools)
4. **No ELF loader** — guest code arrives as raw segments, not ELF; patching happens on the raw binary
5. **SVC dispatch must leave the managed world** — SVC causes the CPU to exit guest mode and return to the C# scheduler, which handles the syscall via existing HLE code

## Architecture

```
┌──────────────────────────────────┐
│  libryubing.so (C# NativeAOT)    │
│  ┌────────────────────────────┐  │
│  │ NceEngine : ICpuEngine     │  │ ◄── implements existing CPU abstraction
│  │ NceCpuContext : ICpuContext│  │
│  └──────────┬─────────────────┘  │
│             │ P/Invoke            │
│             ▼                     │
│  ┌────────────────────────────┐  │
│  │ NceNative (DllImport)      │  │ ◄── thin P/Invoke wrapper
│  └──────────┬─────────────────┘  │
└─────────────┼────────────────────┘
              │ dlopen
              ▼
┌──────────────────────────────────┐
│  libryubing-nce.so (C++20)       │ ◄── NEW: native NCE library
│                                  │
│  ┌────────────────────────────┐  │
│  │ Patcher                    │  │ ◄── binary patching (oaknut codegen)
│  │   - PatchText()            │  │     replaces SVC/MRS/MSR/exclusives
│  │   - RelocateAndCopy()      │  │
│  ├────────────────────────────┤  │
│  │ GuestContext / HostContext │  │ ◄── saved/restored via signal frames
│  ├────────────────────────────┤  │
│  │ Signal handlers            │  │ ◄── SIGSEGV, SIGBUS, SIGURG, SIGUSR2
│  ├────────────────────────────┤  │
│  │ Thread management          │  │ ◄── gettid, tkill, is_running
│  ├────────────────────────────┤  │
│  │ InterpreterVisitor         │  │ ◄── single-instruction fallback for
│  │ (VisitorBase subclass)     │  │     alignment faults (dynarmic decoder)
│  └────────────────────────────┘  │
└──────────────────────────────────┘
```

## Types

### New C++ structs/types (in libryubing-nce)

| Type | File | Purpose |
|------|------|---------|
| `NceGuestContext` | `nce/guest_context.h` | Guest CPU state (regs, sp, pc, pstate, fpcr/fpsr, vec regs, tpidr, esr_el1, svc) — mirrors eden's `GuestContext` |
| `NceHostContext` | `nce/guest_context.h` | Host callee-saved regs, SP, TPIDR_EL0 — save/restore across signal handler boundary |
| `NcePatchSection` | `nce/patcher.h` | Output of patching: relocated module image, patch trampolines, patch+post-patch segments |
| `NceCore` | `nce/core.h` | Per-core state: thread_id, GuestContext, signal stack |
| `NceConfiguration` | `nce/nce.h` | Parameters passed from C#: memory base, page size, core count, callbacks |
| `SVC` / `MRS` / `MSR` / `Exclusive` | `nce/instructions.h` | Bitfield unions for instruction decode |

### New C# types (in LibRyubing)

| Type | File | Purpose |
|------|------|---------|
| `NceEngine : ICpuEngine` | `Nce/NceEngine.cs` | Factory for NCE contexts; implements `ICpuEngine.CreateCpuContext()` |
| `NceCpuContext : ICpuContext` | `Nce/NceCpuContext.cs` | Per-address-space CPU state; wraps native `NceCore` via P/Invoke |
| `NceExecutionContext : IExecutionContext` | `Nce/NceExecutionContext.cs` | Thread execution state — wraps native thread parameters |
| `NceNative` | `Nce/NceNative.cs` | P/Invoke declarations into libryubing-nce.so |
| `NceCallbacks` | `Nce/NceCallbacks.cs` | Struct of C function pointers that the native library calls back into managed code |



## Files

### New files: Native library (`src/RyubingAndroid/app/src/main/cpp/nce/`)

```
src/RyubingAndroid/app/src/main/cpp/nce/
├── CMakeLists.txt            ── Build rules for libryubing-nce.so
├── nce.h                     ── Main header: NceConfiguration, exported C ABI
├── nce.cpp                   ── C ABI implementation, core lifecycle
├── core.h                    ── NceCore per-core state
├── core.cpp                  ── RunThread, Initialize, signal setup
├── guest_context.h           ── NceGuestContext, NceHostContext
├── patcher.h                 ── Patcher class, NcePatchSection
├── patcher.cpp               ── PatchText, RelocateAndCopy
├── instructions.h            ── SVC, MRS, MSR, Exclusive bitfield unions
├── interpreter_visitor.h     ── InterpreterVisitor (dynarmic decoder tree)
├── interpreter_visitor.cpp   ── Guest instruction interpreter for alignment faults
├── visitor_base.h            ── Dynarmic A64 visitor base
├── signal_handlers.cpp       ── All signal handler implementations
├── thread_management.cpp     ── LockThread, UnlockThread, is_running fence
├── memory_bridge.cpp         ── mmap/mprotect wrappers for guest address space
└── svc_dispatch.cpp          ── SVC dispatch to C# callbacks
```

### New files: Managed NCE host (`src/LibRyubing/Nce/`)

```
src/LibRyubing/Nce/
├── NceEngine.cs              ── ICpuEngine implementation
├── NceCpuContext.cs          ── ICpuContext implementation
├── NceExecutionContext.cs    ── IExecutionContext implementation
├── NceNative.cs              ── DllImport("ryubing-nce") declarations
├── NceCallbacks.cs           ── Delegate + function pointer marshaling
└── NceMemoryManager.cs       ── Bridge to Ryubing's memory manager
```

### Modified files

| File | Change |
|------|--------|
| `src/RyubingAndroid/app/src/main/cpp/CMakeLists.txt` | Add `add_subdirectory(nce)`; link `ryubing-nce` into `ryubingjni` |
| `src/RyubingAndroid/app/src/main/cpp/ryubing_interop.h` | Add `NceCallbacks` struct declaration |
| `src/LibRyubing/AndroidHost.cs` | Wire NCE path, add NceEngine factory |
| `src/LibRyubing/LibRyubing.Native.cs` | Add `ryubing_set_nce_config` entry point |

## Functions

### Native C ABI (exported from libryubing-nce.so)

```c
// Lifecycle
int32_t nce_initialize(NceConfiguration* config);
void nce_shutdown();

// Per-core operations
int32_t nce_core_create(uint32_t core_index);
void nce_core_destroy(int32_t core_handle);

// Guest code patching
int32_t nce_patch_module(
    const uint8_t* program_image, uint64_t image_size,
    uint64_t code_offset, uint64_t code_size,
    uint64_t base_virtual_address,
    NcePatchSection* out_section
);

// Guest execution
int32_t nce_run_thread(int32_t core_handle,
    NceGuestContext* context, uint64_t trampoline_addr);

// Context management
void nce_get_context(int32_t core_handle, NceGuestContext* out);
void nce_set_context(int32_t core_handle, const NceGuestContext* in);

// Interrupt
void nce_signal_interrupt(int32_t core_handle);

// Cache management
void nce_clear_instruction_cache();
void nce_invalidate_cache_range(uint64_t addr, uint64_t size);

// Memory integration
void nce_protect_guest_pages(uint64_t addr, uint64_t size, uint32_t prot);
```

### C# Callback Table (NceCallbacks)

```
[UnmanagedCallersOnly]
delegate void NceSvcHandler(int32_t core_handle, uint32_t svc_number, NceGuestContext* ctx);
[UnmanagedCallersOnly]
delegate int32_t NcePageFaultHandler(uint64_t fault_address, uint64_t fault_pc);

[StructLayout(LayoutKind.Sequential)]
struct NceCallbacks
{
    IntPtr handle_svc;        // NceSvcHandler
    IntPtr handle_page_fault; // NcePageFaultHandler
}
```


### Key C++ internal functions

| Function | File | Purpose |
|----------|------|---------|
| `Patcher::PatchText()` | `patcher.cpp` | Scan module text, patch SVC/MRS/MSR/exclusives, generate trampolines |
| `Patcher::RelocateAndCopy()` | `patcher.cpp` | Apply relocations, assemble final program image with patch sections |
| `SaveGuestContext()` | `guest_context.h` | Save all guest registers from mcontext_t into NceGuestContext |
| `RestoreGuestContext()` | `guest_context.h` | Restore guest regs from NceGuestContext into mcontext_t |
| `GetFloatingPointState()` | `guest_context.h` | Walk ucontext_t __reserved to find fpsimd_context |
| `HandleGuestAccessFault()` | `signal_handlers.cpp` | SIGSEGV → page-in or data abort |
| `HandleGuestAlignmentFault()` | `signal_handlers.cpp` | SIGBUS → single-instruction interpreter then resume |
| `HandleFailedGuestFault()` | `signal_handlers.cpp` | Prefetch abort → halt, data abort → skip + continue |
| `BreakFromRunCodeSignalHandler()` | `signal_handlers.cpp` | SIGURG → save guest state, return to C# scheduler |
| `ReturnToRunCodeByExceptionLevelChange()` | `core.cpp` | SIGUSR2 → swap guest context into signal frame, kernel resumes guest |
| `LockThreadParameters()` | `thread_management.cpp` | Acquire spinlock on NativeExecutionParameters |
| `UnlockThreadParameters()` | `thread_management.cpp` | Release spinlock |
| `MatchAndExecuteOneInstruction()` | `interpreter_visitor.cpp` | Decode + execute a single guest instruction |
| `WriteSvcTrampoline()` | `patcher.cpp` | Emit patch trampoline that calls SVC handler |
| `WriteMrsHandler()` | `patcher.cpp` | Emit handler for MRS TPIDR_EL0/TPIDRRO_EL0/CNTFRQ_EL0/CNTPCT_EL0 |
| `WriteMsrHandler()` | `patcher.cpp` | Emit handler for MSR TPIDR_EL0 |
| `WriteLoadContext()` / `WriteSaveContext()` | `patcher.cpp` | Emit context save/restore stubs in patch section |

## Classes

### New C++ classes

**`Patcher`** (in `nce/patcher.h/.cpp`):
- Members: `oaknut::VectorCodeGenerator c` (post-text patch), `c_pre` (pre-text patch), save/load context labels, `vector<ModulePatch> modules`
- Methods: `PatchText()`, `RelocateAndCopy()`, `GetSectionSize()`, `GetPatchMode()`

**`InterpreterVisitor`** (in `nce/interpreter_visitor.h/.cpp`):
- Inherits `VisitorBase` (dynarmic decoder hierarchy)
- Overrides: memory access, load/store, exclusive monitor instructions
- Static: `MatchAndExecuteOneInstruction()` — decodes and runs exactly one instruction

**`NceCore`** (in `nce/core.h/.cpp`):
- Members: `pid_t m_thread_id`, `NceGuestContext m_guest_ctx`, signal stack
- Methods: `Initialize()`, `RunThread()`, `SignalInterrupt()`, `LockThread()`, `UnlockThread()`

### New C# classes

**`NceEngine : ICpuEngine`**: Factory, calls `NceNative.Initialize()` on first use.

**`NceCpuContext : ICpuContext`**: Wraps native core handles. `Execute()` → `NceNative.RunThread()`. `PrepareCodeRange()` → triggers `Patcher::PatchText()`. Manages the `NceCallbacks` table (pinned GC handle → native code).

**`NceExecutionContext : IExecutionContext`**: `StopRunning()` → signals `NceCore.SignalInterrupt()`.


## Dependencies

### New dependencies for the native library

| Dependency | Purpose | Source |
|------------|---------|--------|
| `oaknut` | AArch64 code generation for patcher | https://github.com/merryhime/oaknut.git |
| `dynarmic` (frontend headers only) | A64 instruction decoder for VisitorBase tree and alignment-fault interpreter | https://github.com/merryhime/dynarmic.git |

Both are C++20 projects already used by eden. The dynarmic **frontend decoder is header-only**; we don't need the full dynarmic JIT runtime, just the decoder headers.

### Build integration

- `CMakeLists.txt` in `src/RyubingAndroid/app/src/main/cpp/nce/` uses `FetchContent` to pull oaknut and the dynarmic frontend (matching eden's pattern)
- Parent `CMakeLists.txt` adds `add_subdirectory(nce)` and links `ryubing-nce` into the JNI shim
- `libryubingjni.so` gets `DT_NEEDED` on both `libryubing.so` and `libryubing-nce.so`, ensuring all three load together

## Testing & Validation

### Incremental validation strategy

1. **Phase 0 (scaffolding)**: Build `libryubing-nce.so` exporting `nce_get_version()` — verify via `dlopen`/`dlsym` from adb shell. Verify Gradle picks up the new .so.

2. **Phase 1 (patcher)**: Feed a known NSO `.text` segment, verify:
   - All `SVC #imm` instructions replaced with `B <patch_trampoline>`
   - All `MRS`/`MSR` to TPIDR/CNTFRQ/CNTPCT replaced
   - All `LDXR`/`STXR` converted to `LDAR`/`STLR` (ordered equivalents)
   - Patch section has correct trampoline entries and relocations

3. **Phase 2 (signals + context)**: Signal handler installation; save/restore cycle using inline assembly that triggers SIGUSR2 → verify regs round-trip.

4. **Phase 3 (guest execution)**: Minimal patched NSO (loop + SVC 0x6B svcExitProcess) — verify guest enters, runs, SVC traps, returns correct exit code.

5. **Phase 4+5 (full integration)**: Boot a known-working homebrew NRO through NCE — compare behavior against ARMeilleure path.

### Automated test

A C++ test binary within the native project (built for host ARM64 Linux, not Android) unit-tests the patcher without the full Android stack.


## Implementation Order

### Phase 0: Scaffolding and Build Integration

1. Create `src/RyubingAndroid/app/src/main/cpp/nce/` directory structure and `CMakeLists.txt`
2. Add `FetchContent` for `oaknut` and `dynarmic` frontend headers
3. Create stub `nce.cpp` with a `nce_get_version()` export
4. Modify parent `CMakeLists.txt` with `add_subdirectory(nce)` and link into `ryubingjni`
5. Build from Gradle — confirm `libryubing-nce.so` appears in `jniLibs/arm64-v8a/`
6. Create `src/LibRyubing/Nce/NceNative.cs` with P/Invoke declarations
7. Create stub `NceEngine.cs` / `NceCpuContext.cs` that compile but aren't wired in yet

### Phase 1: Binary Patching

1. Port `instructions.h` (SVC, MRS, MSR, Exclusive bitfield unions) from eden
2. Port `arm_nce_asm_definitions.h` (offset constants) → `asm_defs.h`
3. Port `guest_context.h` — C struct definitions only
4. Port `patcher.h`/`patcher.cpp` from eden — adapt to work without KProcess/KThread:
   - `PatchText()` operates on a raw `span<const u8>` + code segment info
   - `RelocateAndCopy()` takes external addresses for base + patch section destinations
5. Add a standalone test target linking the patcher against dummy data
6. Create `NceCallbacks.cs` with the callbacks struct
7. In `NceCpuContext.PrepareCodeRange()`, call `NceNative.PatchModule()` and store patch segments

### Phase 2: Signal Handlers and Context Management

1. Port `guest_context.cpp` (SaveGuestContext, RestoreGuestContext) — depends only on `<ucontext.h>`
2. Port `signal_handlers.cpp` — the signal handler functions:
   - `ReturnToRunCodeByExceptionLevelChangeSignalHandler` (SIGUSR2)
   - `BreakFromRunCodeSignalHandler` (SIGURG)
   - `GuestAlignmentFaultSignalHandler` (SIGBUS)
   - `GuestAccessFaultSignalHandler` (SIGSEGV)
   - Host fault handlers (chain to previous handler)
3. `Initialize()` in `core.cpp` sets up `sigaltstack`, `sigaction` for all signals
4. Address **bionic-specific signal constraints** (SA_ONSTACK stack size, sigaction semantics)
5. Thread management: `thread_management.cpp` — spinlock via LDAXR/STXR, `is_running` fence
6. Test: inline-asm test triggering SIGUSR2 → verify context save/restore round-trip


### Phase 3: Guest Execution Core

1. Implement `NceCore.RunThread()`:
   - Set up thread parameters (`native_context`, `tpidr_el0`, `tpidrro_el0`)
   - Memory barrier + `is_running = true`
   - Choose between `ReturnToRunCodeByExceptionLevelChange` (default) and `ReturnToRunCodeByTrampoline` (for post-patch handlers)
   - After return: `is_running = false`, return halt reason
2. Implement `SignalInterrupt()`: `esr_el1.fetch_or(BreakLoop)` + `tkill(m_thread_id, SIGURG)`
3. Wire `NceExecutionContext` — `StopRunning()` calls `SignalInterrupt()`
4. Implement `NceCpuContext.Execute()`:
   - Call `nce_run_thread()`, read halt reason:
     - `SupervisorCall` → call managed `NceCallbacks.handle_svc`
     - `DataAbort` / `PrefetchAbort` → route to memory manager
     - `BreakLoop` → return normally
5. Implement the SVC dispatch callback:
   - Native layer saves guest context, sets `svc` field, calls managed callback
   - Managed callback invokes existing HLE SVC handler
   - After SVC handling, call `nce_run_thread()` again to resume guest

### Phase 4: Memory Integration

1. **Analyze Ryubing's memory manager** to find the right integration point:
   - For `MemoryManagerType.HostMapped` or `HostTracked`, guest address space is already mirrored via mmap
   - Native layer must call `mprotect` to set `PROT_EXEC` on code pages
   - Native layer must intercept `IMemoryManager` page protection changes
2. Implement `NceMemoryManager`:
   - Thin observer around Ryubing's `IMemoryManager`
   - On page protection changes: call `nce_protect_guest_pages(addr, size, prot)`
   - On GPU memory invalidation: signal native layer to remap
3. Implement `InvalidateNCE` in the native layer (equivalent to eden's `Memory::InvalidateNCE`):
   - Guest faults on GPU-dirty page → defer-map a separate host-backed copy
   - Android-specific (`__ANDROID__`-gated in eden's code)
4. Handle SIGSEGV in guest memory:
   - Check if fault address is in guest address space range
   - If so: `HandleGuestAccessFault` → InvalidateNCE or data abort → skip instruction, resume guest
   - If not: chain to previous SIGSEGV handler (host fault)


### Phase 5: Exclusive Monitor and Final Integration

1. **Exclusive monitor**: LDXR→LDAR, STXR→STLR (done by patcher). Managed HLE kernel handles exclusivity failures.
2. **Counter/timer emulation**:
   - `MRS <Xd>, CNTFRQ_EL0` → MOV immediate (patched)
   - `MRS <Xd>, CNTPCT_EL0` → read host CNTVCT_EL0, scale by frequency ratio (patched handler)
3. **Thread locking**: `NceCpuContext.LockThread()`/`UnlockThread()` → P/Invoke to native lock/unlock
4. **Integration into `AndroidHost`**:
   - Detect ARM64 host at startup → enable NCE by default
   - `ryubing_set_nce_config` entry point sets configuration
   - `ryubing_load_application` checks NCE mode → invokes patching + NCE initialization
5. Final end-to-end wiring: homebrew NSO runs through NCE path

### Phase 6: Polish and Edge Cases

1. **Dynamic NRO loading** — patching on-the-fly (locking in the patcher)
2. **32-bit (AArch32) guest code** — fall back to ARMeilleure
3. **Debugger/watchpoint support** — route through memory manager
4. **Performance tuning** — minimize signal handler hot paths, tune CNTPCT scaling
5. **PTC cache interaction** — NCE skips PTC; "Patched Image Cache" could speed up subsequent boots
6. **Crash/hang safety** — watchdog for infinite loops

## Key Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| NativeAOT + signal handling incompatibility | Crashes at runtime | Keep signal handlers in native .so only; C# never touches sigaction or ucontext_t |
| Oaknut/dynarmic cross-compilation for Android NDK | Build failures | Use FetchContent with NDK's clang; both libraries are well-tested on ARM64 |
| Guest memory mprotect conflicts with managed heap | Data corruption / SIGSEGV | NCE memory operations restricted to pre-reserved guest address range only |
| SVC dispatch overhead nullifies NCE benefits | Poor performance | Profile SVC frequency; batch small SVCs; trampoline-inline for common SVCs |
| Bionic signal semantics differ from glibc | Unhandled signals / hangs | Pre-test signal handlers on API 30+ before full integration; use eden's `__ANDROID__` paths |

