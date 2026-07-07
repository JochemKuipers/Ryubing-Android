#!/usr/bin/env bash
#
# Cross-compile OpenAL Soft (libopenal.so) for Android arm64. Ryubing's
# Ryujinx.Audio.Backends.OpenAL P/Invokes into this at runtime.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

OPENAL_VERSION="${OPENAL_VERSION:-1.23.1}"
SRC="$BUILD_DIR/openal-soft-$OPENAL_VERSION"

fetch "https://github.com/kcat/openal-soft/archive/refs/tags/$OPENAL_VERSION.tar.gz" "openal-soft-$OPENAL_VERSION.tar.gz"
rm -rf "$SRC"
tar -xzf "$DOWNLOAD_DIR/openal-soft-$OPENAL_VERSION.tar.gz" -C "$BUILD_DIR"

BUILD="$SRC/build-android"
rm -rf "$BUILD"
mkdir -p "$BUILD"

cmake -S "$SRC" -B "$BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  `# OpenAL Soft 1.23.1 declares an ancient cmake_minimum_required that CMake 4.x`  \
  `# rejects; allow it to configure under the old policy defaults.`                 \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DANDROID_ABI="$ANDROID_ABI" \
  -DANDROID_PLATFORM="android-$ANDROID_API" \
  -DCMAKE_BUILD_TYPE=Release \
  -DLIBTYPE=SHARED \
  -DALSOFT_BACKEND_OPENSL=ON \
  -DALSOFT_BACKEND_WAVE=OFF \
  -DALSOFT_EXAMPLES=OFF \
  -DALSOFT_UTILS=OFF \
  -DALSOFT_TESTS=OFF \
  -DALSOFT_INSTALL=OFF

cmake --build "$BUILD" -j"$(nproc)"

SO="$(find "$BUILD" -name 'libopenal.so' -type f | head -n1)"
[[ -n "$SO" ]] || { echo "error: libopenal.so not built" >&2; exit 1; }
install_so "$SO"

echo "==> OpenAL Soft $OPENAL_VERSION done."
