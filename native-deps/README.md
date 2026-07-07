# Native dependencies

Ryubing's managed code P/Invokes several native libraries that Android does not ship.
These scripts cross-compile them (arm64-v8a) with the NDK and stage the resulting
shared objects into `src/RyubingAndroid/app/src/main/jniLibs/arm64-v8a/`.

| Library | Produces | Needed by |
|---------|----------|-----------|
| OpenSSL 3.2 | `libcrypto.so`, `libssl.so` | .NET crypto (no inbox OpenSSL on Android) |
| OpenAL Soft 1.23 | `libopenal.so` | `Ryujinx.Audio.Backends.OpenAL` |
| FFmpeg 6.1 (LGPL, shared) | `libavcodec.so`, `libavutil.so` | `Ryujinx.Graphics.Nvdec.FFmpeg` |

## Usage

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk-r27
./build-all.sh
```

Override versions/ABI/API via environment, e.g. `OPENSSL_VERSION=3.2.2 ./build-openssl.sh`.

## Licensing

FFmpeg is built as **shared** libraries without `--enable-gpl`, so it is used under
LGPL-2.1 via dynamic linking. Do not switch to static linking or enable GPL components.
Attribution for all bundled libraries is surfaced in the app's About screen.
