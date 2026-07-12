# Upstream patch queue

Numbered patches here are applied to `upstream/ryubing`. They are the **only** sanctioned
way to modify upstream source — the submodule itself is kept pristine at its pinned commit.

Two entry points apply them:

- **Build time (automatic).** The Gradle `:libryubing:applyUpstreamPatches` task resets the
  submodule to its pinned commit and re-applies every patch with `git apply` before the
  NativeAOT publish runs. This is cross-platform (git only, no bash) and idempotent, so the
  `dotnet publish` always compiles the patched sources without anything being committed.
- **Maintainer/CI.** `scripts/apply-patches.sh` applies the same files with `git am` (giving
  each patch a commit + provenance) when preparing or validating an upstream bump.

## Rules

1. **Adapter-layer first.** Before writing a patch, try to solve the problem in
   `src/LibRyubing` (managed host), `src/RyubingAndroid` (Kotlin/JNI), or by using
   an existing Ryubing abstraction (`IHostUIHandler`, the `linux-arm64` code paths,
   `Ryujinx.Common`). Only patch upstream if none of those work.
2. **Need-driven.** Each patch must be motivated by a concrete build/test failure on
   `linux-bionic-arm64`, cited in the commit message. Do **not** seed patches by
   diffing Kenji-NX; audit Kenji only to understand a problem, then implement the
   Ryubing-native fix.
3. **Minimal & atomic.** One concern per patch. Prefer `#if ANDROID` guards
   (`DefineConstants=ANDROID` is set during publish) over invasive refactors.
4. **Regenerate on conflict.** After an upstream bump, if `git am` fails, fix in the
   submodule and regenerate with `git format-patch`.
5. **Upstream merge.** When Ryubing accepts an Android fix natively, drop the matching
   patch from this queue and bump the pinned commit (see `compat/pins.json`).
6. **Never commit patches into `upstream/ryubing`.** Use `git apply` at build time only.
   If you ran `scripts/apply-patches.sh` (git am), reset the submodule to the pristine
   `upstream_commit` in `compat/pins.json` before committing a bump.

## Generating a patch

```bash
cd upstream/ryubing
# make your change, commit it with a descriptive message
git commit -am "android: <why this is needed on bionic>"
git format-patch -1 -o ../../patches --start-number <N>
```

Name format: `NNNN-short-description.patch` (kept in order by `NNNN`).

## Current queue

Seven patches ported from [Kenji-NX `libryujinx_bionic`](https://git.ryujinx.app/projects/Kenji-NX/src/branch/libryujinx_bionic) onto the Ryubing pin (`dc06d0de` / Canary-1.3.335). The phantom upstream commits cited in older pins never landed on Ryubing master. Patches 0006–0007 are Ryubing-native (NativeAOT shader SSA crash). Patch 0008 adds Kenji-style Android surface rotation (identity Vulkan preTransform + buffer transform in the JNI shim).

| Patch | Concern |
|-------|---------|
| `0001-android-route-bionic-memory-through-Unix-helpers-and.patch` | `PlatformInfo.IsBionic`, Unix memory routing, `ASharedMemory_create` shared memory |
| `0002-android-host-no-mirror-address-space-fallback-on-bio.patch` | `TryCreateWithoutMirror`, `MemoryManagerHostNoMirror`, `ArmProcessContextFactory` fallback |
| `0003-android-disable-sparse-JIT-tables-and-honor-bionic-i.patch` | Sparse JIT off on bionic, `PreciseSleepHelper` bionic path |
| `0004-android-armeilleure-delegate-registry-for-NativeAOT.patch` | NativeAOT-safe JIT helper delegate registry (`Delegates.cs`, helper `#if ANDROID` guards) |
| `0005-android-bionic-sigaction-layout-and-signal-handler.patch` | Bionic `sigaction` struct layout, siginfo offsets, alternate stack helpers |
| `0006-android-hoist-ssa-rename-local-functions-for-NativeAOT.patch` | Hoist `Ssa.Rename` nested locals to static methods (NativeAOT codegen fix) |
| `0007-android-add-SSA-bounds-checks-and-skip-zero-predicat.patch` | Bounds-check `LocalDefMap` keys; skip RZ/PT dest registers (NativeAOT AV fix) |
| `0008-android-vulkan-identity-pretransform-and-surface.patch` | Identity swapchain preTransform on bionic; expose `CurrentTransform` for JNI buffer sync |
| `0009-android-vulkan-recover-from-surface-lost.patch` | Treat `ErrorSurfaceLostKhr` like out-of-date; skip 0×0 swapchain extents on Android |

`LibRyubing` still sets `PlatformInfo.IsBionic = true` at init (adapter layer).
