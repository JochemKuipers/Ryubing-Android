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

## Generating a patch

```bash
cd upstream/ryubing
# make your change, commit it with a descriptive message
git commit -am "android: <why this is needed on bionic>"
git format-patch -1 -o ../../patches --start-number <N>
```

Name format: `NNNN-short-description.patch` (kept in order by `NNNN`).

## Current queue

Empty as of pin `86f17d74` — the former `0001`–`0003` Android memory fixes are merged
upstream:

| Former patch | Upstream commit |
|--------------|-----------------|
| memfd_create shared memory | `9d66a852e` |
| host-no-mirror MM fallback | `00eaa31f7` |
| sparse JIT address tables off on bionic | `86f17d74a` |

`LibRyubing` still sets `PlatformInfo.IsBionic` at init (adapter layer).
