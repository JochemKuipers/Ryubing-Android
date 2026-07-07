# RyubingAndroid (Gradle project)

The Android app and its native build.

## Modules

- **`:libryubing`** — not an Android module. Runs `dotnet publish` on
  `../LibRyubing/LibRyubing.csproj` for `linux-bionic-arm64` and copies the resulting
  `libryubing.so` into `app/src/main/jniLibs/arm64-v8a/`. See `libryubing/build.gradle.kts`.
- **`:app`** — the Kotlin/Compose APK. Builds `libryubingjni.so` from `app/src/main/cpp`
  via CMake, packages all `jniLibs/*.so`, and hosts the UI. `preBuild` depends on
  `:libryubing:assemble` so the emulator core is present before packaging.

## First-time setup

The Gradle wrapper jar is not committed. Generate it once with a locally installed Gradle:

```bash
cd src/RyubingAndroid
gradle wrapper --gradle-version 8.11.1
```

Then configure `gradle.properties`:

- `ryubing.ndk.toolchain` — absolute path to the NDK LLVM `bin` (clang for aarch64),
  required by `dotnet publish -r linux-bionic-arm64`.
- `ryubing.dotnet.bin` / `ryubing.dotnet.config` — dotnet executable and publish config.

## Build

```bash
./gradlew assembleDebug
```

This publishes `libryubing.so`, builds `libryubingjni.so`, and assembles the APK.
Native runtime dependencies (OpenSSL, OpenAL, FFmpeg) must first be produced by the
scripts in `../../native-deps/`.
