# Upstream patch queue

Numbered patches here are applied to `upstream/ryubing` by `scripts/apply-patches.sh`
using `git am`. They are the **only** sanctioned way to modify upstream source.

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

## Generating a patch

```bash
cd upstream/ryubing
# make your change, commit it with a descriptive message
git commit -am "android: <why this is needed on bionic>"
git format-patch -1 -o ../../patches --start-number <N>
```

Name format: `NNNN-short-description.patch` (kept in order by `NNNN`).

## Current queue

_Empty._ The port currently builds against pristine upstream; all Android logic lives
in `src/LibRyubing` and `src/RyubingAndroid`.
