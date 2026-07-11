#!/usr/bin/env bash
#
# Print the Android NDK LLVM toolchain 'bin' directory for the current host OS.
# Used by publish-libryubing.sh and documented for manual builds.
#
# Resolution order:
#   1. RYUBING_NDK_TOOLCHAIN (if the directory exists)
#   2. ryubing.ndk.toolchain in src/RyubingAndroid/gradle.properties
#   3. sdk.dir from src/RyubingAndroid/local.properties
#   4. ANDROID_HOME / ANDROID_SDK_ROOT
#   5. ~/Android/Sdk (default local install from scripts/setup-android-sdk.sh)
#
# Usage: scripts/resolve-ndk-toolchain.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$REPO_ROOT/src/RyubingAndroid"

host_tag() {
  case "$(uname -s)" in
    Linux*) echo "linux-x86_64" ;;
    Darwin*) echo "darwin-x86_64" ;;
    MINGW*|MSYS*|CYGWIN*) echo "windows-x86_64" ;;
    *) echo "linux-x86_64" ;;
  esac
}

ndk_bin_from_sdk() {
  local sdk="$1"
  local ndk_root="$sdk/ndk"
  [[ -d "$ndk_root" ]] || return 1
  local version
  version="$(ls -1 "$ndk_root" 2>/dev/null | sort -V | tail -1)" || return 1
  local bin="$ndk_root/$version/toolchains/llvm/prebuilt/$(host_tag)/bin"
  [[ -x "$bin/clang" && -x "$bin/ld.lld" ]] || return 1
  printf '%s' "$bin"
}

try_dir() {
  local dir="$1"
  [[ -n "$dir" && -d "$dir" && -x "$dir/clang" && -x "$dir/ld.lld" ]] || return 1
  printf '%s' "$dir"
}

if [[ -n "${RYUBING_NDK_TOOLCHAIN:-}" ]]; then
  if try_dir "$RYUBING_NDK_TOOLCHAIN" >/dev/null; then
    try_dir "$RYUBING_NDK_TOOLCHAIN"
    exit 0
  fi
fi

gradle_props="$ANDROID_DIR/gradle.properties"
if [[ -f "$gradle_props" ]]; then
  toolchain_prop="$(sed -n 's/^ryubing\.ndk\.toolchain=//p' "$gradle_props" | tail -1 | tr -d '\r')"
  if [[ -n "$toolchain_prop" ]]; then
    if try_dir "$toolchain_prop" >/dev/null; then
      try_dir "$toolchain_prop"
      exit 0
    fi
  fi
fi

local_props="$ANDROID_DIR/local.properties"
if [[ -f "$local_props" ]]; then
  sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$local_props" | tail -1 | sed 's/\\:/:/g; s/\\//g' | tr -d '\r')"
  if [[ -n "$sdk_dir" && -d "$sdk_dir" ]]; then
    if bin="$(ndk_bin_from_sdk "$sdk_dir")"; then
      printf '%s' "$bin"
      exit 0
    fi
  fi
fi

for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
  [[ -n "$sdk" && -d "$sdk" ]] || continue
  if bin="$(ndk_bin_from_sdk "$sdk")"; then
    printf '%s' "$bin"
    exit 0
  fi
done

exit 1
