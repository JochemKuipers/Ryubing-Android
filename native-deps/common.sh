#!/usr/bin/env bash
#
# Shared environment for the native-dependency cross-compilation scripts.
# Sourced by build-*.sh. Targets a single ABI: arm64-v8a (aarch64-linux-android).
set -euo pipefail

# --- Configuration (override via environment) ---
export ANDROID_ABI="${ANDROID_ABI:-arm64-v8a}"
export ANDROID_API="${ANDROID_API:-30}"          # matches app minSdk
export ANDROID_TRIPLE="${ANDROID_TRIPLE:-aarch64-linux-android}"

NATIVE_DEPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export NATIVE_DEPS_DIR
export DOWNLOAD_DIR="$NATIVE_DEPS_DIR/download"
export BUILD_DIR="$NATIVE_DEPS_DIR/build"
export PREFIX="$BUILD_DIR/prefix/$ANDROID_ABI"

# Output directory: where the app expects runtime .so files.
export JNILIBS_DIR="$NATIVE_DEPS_DIR/../src/RyubingAndroid/app/src/main/jniLibs/$ANDROID_ABI"

# --- Locate the NDK ---
: "${ANDROID_NDK_HOME:=${ANDROID_NDK_ROOT:-}}"
if [[ -z "${ANDROID_NDK_HOME:-}" || ! -d "$ANDROID_NDK_HOME" ]]; then
  REPO_ROOT="$(cd "$NATIVE_DEPS_DIR/.." && pwd)"
  if toolchain_bin="$("$REPO_ROOT/scripts/resolve-ndk-toolchain.sh" 2>/dev/null)"; then
    export ANDROID_NDK_HOME="$(cd "$toolchain_bin/../../../../.." && pwd)"
  fi
fi
if [[ -z "${ANDROID_NDK_HOME:-}" || ! -d "$ANDROID_NDK_HOME" ]]; then
  cat >&2 <<EOF
error: Android NDK not found.

Set ANDROID_NDK_HOME, or install the SDK/NDK locally:
  scripts/setup-android-sdk.sh
  export ANDROID_NDK_HOME="\$HOME/Android/Sdk/ndk/27.2.12479018"
EOF
  exit 1
fi

HOST_TAG="linux-x86_64"
case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;
esac
export TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG"
export TOOLCHAIN_BIN="$TOOLCHAIN/bin"

# Standard cross-compiler variables for autotools/CMake/configure.
export CC="$TOOLCHAIN_BIN/${ANDROID_TRIPLE}${ANDROID_API}-clang"
export CXX="$TOOLCHAIN_BIN/${ANDROID_TRIPLE}${ANDROID_API}-clang++"
export AR="$TOOLCHAIN_BIN/llvm-ar"
export AS="$CC"
export LD="$TOOLCHAIN_BIN/ld"
export RANLIB="$TOOLCHAIN_BIN/llvm-ranlib"
export STRIP="$TOOLCHAIN_BIN/llvm-strip"
export NM="$TOOLCHAIN_BIN/llvm-nm"

mkdir -p "$DOWNLOAD_DIR" "$BUILD_DIR" "$PREFIX" "$JNILIBS_DIR"

# fetch <url> <output-filename>
fetch() {
  local url="$1" out="$2"
  if [[ ! -f "$DOWNLOAD_DIR/$out" ]]; then
    echo "==> Downloading $out"
    curl -fsSL "$url" -o "$DOWNLOAD_DIR/$out"
  fi
}

# install_so <path-to-.so> : strip and copy into jniLibs.
install_so() {
  local so="$1"
  local base
  base="$(basename "$so")"
  "$STRIP" --strip-unneeded "$so" -o "$JNILIBS_DIR/$base"
  echo "==> Installed $base -> $JNILIBS_DIR"
}
