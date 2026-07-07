# Kenji-NX audit notes

Kenji-NX (`libryujinx_bionic`) is a separate Ryujinx-lineage fork that already ships on
Android. We treat it as a **read-only reference**: we grep/read it to understand *what
problems exist* when running this codebase on Android, then implement a **Ryubing-native**
solution. No Kenji source is copied into this repo.

## Confirmed lessons (concept -> Ryubing-native approach)

| Problem Kenji surfaced | Ryubing-native approach in this repo |
|------------------------|--------------------------------------|
| Emulator-scale C# runs on Android via `linux-bionic-arm64` NativeAOT | `src/LibRyubing` publishes `libryubing.so` with `PublishAotUsingRuntimePack` |
| Android has no inbox OpenSSL; .NET crypto needs it | `native-deps/` builds `libcrypto.so`/`libssl.so`, bundled in `jniLibs` |
| Managed core can't reach `ANativeWindow` / custom Vulkan drivers | `libryubingjni.so` C++ shim + adrenotools; surface handle passed to managed side |
| Software keyboard applet is a stability hotspot | Implement Ryubing's `IHostUIHandler` properly, test early |
| NCE / PPTC risky on mobile | Ship with them off by default via Ryubing config |
| Custom Turnip drivers boost Adreno | adrenotools driver loading exposed through the JNI shim |

## What we deliberately do NOT reuse from Kenji

- `LibKenjinx/` — reimplemented as `LibRyubing`, modeled on Ryubing's `HeadlessRyujinx`.
- `KenjinxAndroid/` UI — fresh Compose app.
- `Ryujinx.UI.Common` config split — Ryubing keeps config in `Ryujinx.Common.Configuration`
  and `src/Ryujinx/Systems/Configuration`; we reference those instead.
- Kenji `#if ANDROID` patches in `ARMeilleure` — Ryubing already supports the `linux-arm64`
  JIT path; we validate that on bionic first and only patch on a proven failure.
- Kenji's JNI callback layer — we design a minimal export/callback ABI (see
  `src/LibRyubing/LibRyubing.Native.cs`).

## Ryubing integration anchors (use these)

| Need | Ryubing source |
|------|----------------|
| Headless boot / lifecycle | `upstream/ryubing/src/Ryujinx/Headless/HeadlessRyujinx.cs` (+ `.Init.cs`) |
| HLE UI callbacks | `upstream/ryubing/src/Ryujinx.HLE/UI/IHostUIHandler.cs` |
| Text input | `upstream/ryubing/src/Ryujinx.HLE/UI/IDynamicTextInputHandler.cs` |
| Vulkan surface creation | `upstream/ryubing/src/Ryujinx/Headless/Windows/VulkanWindow.cs` |
| Emulation context wiring | `HleConfiguration.Configure(...)` in `Ryujinx.HLE/HleConfiguration.cs` |
| Data paths | `upstream/ryubing/src/Ryujinx.Common/Configuration/AppDataManager.cs` |
| OpenAL audio driver | `upstream/ryubing/src/Ryujinx.Audio.Backends.OpenAL/OpenALHardwareDeviceDriver.cs` |
