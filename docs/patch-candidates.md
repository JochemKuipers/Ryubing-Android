# Upstream patch candidates

This is a **catalogue**, not a patch set. `patches/` stays empty until a candidate is
*proven necessary* by a `dotnet publish -r linux-bionic-arm64` build or an on-device run
(see `patches/README.md`). Each entry below records a problem an Android/NativeAOT build
of a Ryujinx-lineage emulator is known to hit, the trigger that would justify writing the
patch, and the **Ryubing-native** fix to write when that happens.

The candidates were derived by *auditing* Kenji-NX's `#if ANDROID` diffs to learn which
problems exist — not by copying its code. Where Ryubing already solves a problem upstream,
it is noted as "already handled" and no patch is planned.

---

## C1 — ARMeilleure JIT ↔ managed-helper interop under NativeAOT  (LIKELY REQUIRED)

**Files:** `src/ARMeilleure/Translation/Delegates.cs`,
`src/ARMeilleure/Instructions/{NativeInterface,SoftFallback,SoftFloat,MathHelper}.cs`,
`src/ARMeilleure/Translation/EmitterContext.cs`

**Problem.** ARMeilleure's JIT emits calls into managed helper methods
(`NativeInterface.*`, `SoftFallback.*`, `MathHelper.*`) via native function pointers. On
the desktop those helpers are `[UnmanagedCallersOnly]` and their pointers come from
`MethodHandle.GetFunctionPointer()`. Under NativeAOT that reflection-based pointer
acquisition path is not reliable, so JIT-translated guest code can jump to bad addresses.
Ryubing still uses `[UnmanagedCallersOnly]` here (24 occurrences in `NativeInterface.cs`),
so it has **not** been solved upstream.

**Trigger.** The first bionic run crashes/segfaults inside translated code, or the AOT
compiler warns about `UnmanagedCallersOnly`/reflection over these helpers.

**Ryubing-native fix.** Add an `#if ANDROID`-guarded delegate registry in `Delegates.cs`
that maps each helper to an explicit `[UnmanagedFunctionPointer(Cdecl)]` delegate and
hands `Marshal.GetFunctionPointerForDelegate` to the JIT; guard the helpers so they are
plain (aggressively-inlined) managed methods instead of `[UnmanagedCallersOnly]` on
Android. Write this fresh against Ryubing's current `Delegates.cs` — do not import
Kenji's file. This is the one candidate that is almost certainly needed for a working
port; everything else is conditional.

---

## C2 — Precise-sleep timing on bionic  (ALREADY HANDLED UPSTREAM)

**File:** `src/Ryujinx.Common/PreciseSleep/PreciseSleepHelper.cs`

Ryubing already branches on `OperatingSystem.IsAndroid() || PlatformInfo.IsBionic` to use
a `NanosleepEvent`. **No patch needed** — validate at runtime only.

---

## C3 — GPU texture-cache sizing for mobile memory  (OPTIONAL TUNING)

**File:** `src/Ryujinx.Graphics.Gpu/Image/AutoDeleteCache.cs`

**Problem.** Desktop uses a 2048-entry texture cache; phones have far less RAM/VRAM.
Kenji halves `MaxCapacity` to 1024 on Android.

**Trigger.** OOM / excessive memory pressure during gameplay on target devices.

**Ryubing-native fix.** Prefer exposing the cap through `EmulatorSettings`/`GraphicsConfig`
(runtime knob) instead of a compile-time patch. Only patch if a runtime knob is
infeasible.

---

## C4 — Data paths / storage under scoped storage  (SOLVE IN ADAPTER FIRST)

**Files:** `src/Ryujinx.Common/Configuration/AppDataManager.cs` (upstream) vs.
`LibRyubing.AndroidHost.Initialize` (ours)

**Problem.** The emulator wants a writable base dir; Android apps must use their private
storage (and SAF for user file access).

**Status.** Solved in the adapter: `AndroidHost.Initialize` calls
`AppDataManager.Initialize(appDataPath)` with the app's private dir. **No patch expected.**
Only patch if a hard-coded desktop path is discovered that ignores the base dir.

---

## C5 — Shared memory on bionic (no /dev/shm)  (UPSTREAM @ `9d66a852e`)

**File:** `src/Ryujinx.Memory/MemoryManagementUnix.cs`,
`src/Ryujinx.Memory/MemoryManagerUnixHelper.cs`

**Status.** Merged upstream; removed from `patches/` when pinning `86f17d74`. Former
`patches/0001-*`.

---

## C6 — Mirrored 39-bit address-space reservation on bionic  (UPSTREAM @ `00eaa31f7`)

**Files:** `src/Ryujinx.Cpu/AddressSpace.cs`,
`src/Ryujinx.Cpu/Jit/MemoryManagerHostNoMirror.cs`,
`src/Ryujinx.HLE/HOS/ArmProcessContextFactory.cs`

**Status.** Merged upstream; removed from `patches/` when pinning `86f17d74`. Former
`patches/0002-*`.

---

## C7 — Sparse JIT address tables on bionic  (UPSTREAM @ `86f17d74a` + `LibRyubing`)

**Files:** `src/Ryujinx.Common/PlatformInfo.cs`, `src/Ryujinx.Cpu/AddressTable.cs`,
`src/LibRyubing/AndroidHost.cs`

**Status.** Upstream half merged at `86f17d74`; `PlatformInfo.IsBionic` is still set in
the adapter (`AndroidHost.Initialize`). Former `patches/0003-*`.

---

## Notes

- Keep every future patch `#if ANDROID`-guarded (the publish sets `DefineConstants=ANDROID`)
  so upstream desktop behaviour is untouched and `git am` conflicts stay small.
- When a candidate becomes real, generate it with `git format-patch` from the submodule and
  drop it in `patches/NNNN-*.patch`, then bump `patches_applied` in `compat/pins.json`.
- Patches are applied automatically at build time by the Gradle `applyUpstreamPatches` task
  (`git apply` onto the pinned submodule), so the submodule tree stays pristine in git.
