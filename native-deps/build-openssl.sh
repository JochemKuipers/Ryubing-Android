#!/usr/bin/env bash
#
# Cross-compile OpenSSL as shared libs (libcrypto.so / libssl.so) for Android arm64.
# Android has no inbox OpenSSL, and .NET's crypto stack needs it at runtime.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

OPENSSL_VERSION="${OPENSSL_VERSION:-3.2.1}"
SRC="$BUILD_DIR/openssl-$OPENSSL_VERSION"

fetch "https://www.openssl.org/source/openssl-$OPENSSL_VERSION.tar.gz" "openssl-$OPENSSL_VERSION.tar.gz"
rm -rf "$SRC"
tar -xzf "$DOWNLOAD_DIR/openssl-$OPENSSL_VERSION.tar.gz" -C "$BUILD_DIR"

pushd "$SRC" >/dev/null

# OpenSSL's Configure understands android-arm64; PATH must expose the NDK clang.
PATH="$TOOLCHAIN_BIN:$PATH" \
ANDROID_NDK_ROOT="$ANDROID_NDK_HOME" \
./Configure android-arm64 \
  -D__ANDROID_API__="$ANDROID_API" \
  --prefix="$PREFIX" \
  shared \
  no-tests \
  no-apps

PATH="$TOOLCHAIN_BIN:$PATH" make -j"$(nproc)" build_libs
PATH="$TOOLCHAIN_BIN:$PATH" make install_dev install_runtime_libs

popd >/dev/null

# OpenSSL names them libcrypto.so.3 / libssl.so.3; Android requires unversioned names.
for lib in libcrypto libssl; do
  real="$(find "$PREFIX/lib" -name "$lib.so*" -type f | head -n1)"
  [[ -n "$real" ]] || { echo "error: $lib not built" >&2; exit 1; }
  cp -f "$real" "$BUILD_DIR/$lib.so"
  install_so "$BUILD_DIR/$lib.so"
done

echo "==> OpenSSL $OPENSSL_VERSION done."
