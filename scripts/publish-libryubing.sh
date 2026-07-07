#!/usr/bin/env bash
#
# Publish the LibRyubing NativeAOT shared library (libryubing.so) for
# linux-bionic-arm64 and copy it into the app's jniLibs.
#
# Requires:
#   - .NET SDK matching upstream/ryubing/global.json
#   - Android NDK LLVM toolchain on PATH (clang for aarch64)
#     export via RYUBING_NDK_TOOLCHAIN or ensure `clang` resolves to the NDK one.
#
# Usage: scripts/publish-libryubing.sh [Release|Debug]
set -euo pipefail

CONFIG="${1:-Release}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="$REPO_ROOT/src/LibRyubing/LibRyubing.csproj"
OUT_DIR="$REPO_ROOT/src/RyubingAndroid/app/src/main/jniLibs/arm64-v8a"

if [[ -n "${RYUBING_NDK_TOOLCHAIN:-}" ]]; then
  export PATH="$RYUBING_NDK_TOOLCHAIN:$PATH"
fi

echo "==> dotnet publish LibRyubing ($CONFIG, linux-bionic-arm64)"
dotnet publish "$PROJECT" \
  -r linux-bionic-arm64 \
  -c "$CONFIG" \
  -p:DisableUnsupportedError=true \
  -p:PublishAotUsingRuntimePack=true \
  -p:StripSymbols=true \
  -p:DefineConstants=ANDROID \
  --artifacts-path "$REPO_ROOT/artifacts/libryubing"

SO="$(find "$REPO_ROOT/artifacts/libryubing" -name 'libryubing.so' -print -quit)"
if [[ -z "$SO" ]]; then
  echo "error: libryubing.so not found after publish" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
cp -v "$SO" "$OUT_DIR/libryubing.so"
echo "==> Copied to $OUT_DIR/libryubing.so"
