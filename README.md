# Ryubing Android

A fully self-contained Android port of the [Ryubing](https://git.ryujinx.app/ryubing/ryujinx) Nintendo Switch emulator (a maintained fork of Ryujinx).

This repository is a **thin Android wrapper** around an unmodified Ryubing checkout. Ryubing is included as a git submodule under [`upstream/ryubing`](upstream/ryubing) so upstream changes can be pulled with minimal friction. Android-specific code lives entirely in this repo; upstream is only touched through a small, need-driven patch queue in [`patches/`](patches).

## Architecture

```
Kotlin/Compose app  ──JNA──▶  libryubing.so (.NET NativeAOT)  ──▶  Ryubing.HLE + ARMeilleure + Graphics.Vulkan
        │                            │
        └──────── JNI ───────▶  libryubingjni.so (C++/NDK: ANativeWindow, Vulkan surface, adrenotools)
```

- **`libryubing.so`** — the emulator core, published from [`src/LibRyubing`](src/LibRyubing) as a NativeAOT shared library for the `linux-bionic-arm64` runtime. It is a greenfield Android *headless host* modeled on Ryubing's own `HeadlessRyujinx`, referencing the upstream emulation projects directly.
- **`libryubingjni.so`** — a small C++/NDK shim for platform APIs the managed core cannot reach (Android surface, custom Vulkan driver loading via adrenotools).
- **Compose app** — [`src/RyubingAndroid`](src/RyubingAndroid), a native Android UI (game library, settings, on-screen controls).

See the full roadmap in the project plan.

## Layout

| Path | Purpose |
|------|---------|
| `upstream/ryubing/` | Ryubing submodule (unmodified upstream source) |
| `src/LibRyubing/` | NativeAOT Android host (`libryubing.so`) |
| `src/RyubingAndroid/` | Kotlin/Compose app + JNI shim |
| `native-deps/` | Scripts to cross-compile OpenSSL / OpenAL / FFmpeg for arm64 |
| `patches/` | Numbered, need-driven patches applied to `upstream/ryubing` |
| `scripts/` | Upstream sync + patch automation |
| `compat/pins.json` | Records which upstream commit each app version was built against |
| `docs/` | Audit notes and design decisions |

## Building

Prerequisites:

- .NET SDK matching `upstream/ryubing/global.json`
- Android NDK r26+ (LLVM toolchain on `PATH`)
- Android SDK + JDK 17

```bash
git submodule update --init --recursive
scripts/apply-patches.sh              # apply Android patch queue to upstream
cd src/RyubingAndroid && ./gradlew assembleDebug
```

The Gradle build invokes `dotnet publish` for `libryubing.so` and CMake for `libryubingjni.so`, then packages both into the APK.

## Upstream sync

```bash
scripts/sync-upstream.sh <tag-or-commit>   # bump submodule, re-apply patches, sanity build
```

See [`scripts/`](scripts) and [`compat/pins.json`](compat/pins.json).

## License

Android wrapper code is MIT (matching Ryubing). Bundled native dependencies retain their own licenses (see the in-app About screen and `distribution/legal` upstream). This project does **not** distribute Nintendo firmware, keys, or games — users must provide their own.
