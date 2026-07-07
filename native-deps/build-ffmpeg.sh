#!/usr/bin/env bash
#
# Cross-compile a minimal FFmpeg (shared libs) for Android arm64. Ryubing's
# Ryujinx.Graphics.Nvdec.FFmpeg uses these for video (NVDEC) decoding.
#
# LGPL note: we build FFmpeg as SHARED libraries and link dynamically, keeping
# LGPL-2.1 compliance. Do not enable --enable-gpl or static linking.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

FFMPEG_VERSION="${FFMPEG_VERSION:-6.1.1}"
SRC="$BUILD_DIR/ffmpeg-$FFMPEG_VERSION"

fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" "ffmpeg-$FFMPEG_VERSION.tar.xz"
rm -rf "$SRC"
tar -xf "$DOWNLOAD_DIR/ffmpeg-$FFMPEG_VERSION.tar.xz" -C "$BUILD_DIR"

pushd "$SRC" >/dev/null

# Only the decoders the emulator actually needs (H.264 / VP8 / VP9).
PATH="$TOOLCHAIN_BIN:$PATH" ./configure \
  --prefix="$PREFIX" \
  --target-os=android \
  --arch=aarch64 \
  --cpu=armv8-a \
  --enable-cross-compile \
  --cross-prefix="$TOOLCHAIN_BIN/llvm-" \
  --cc="$CC" \
  --cxx="$CXX" \
  --ar="$AR" \
  --ranlib="$RANLIB" \
  --strip="$STRIP" \
  --nm="$NM" \
  --sysroot="$TOOLCHAIN/sysroot" \
  --enable-shared \
  --disable-static \
  --disable-programs \
  --disable-doc \
  --disable-everything \
  --enable-decoder=h264 \
  --enable-decoder=vp8 \
  --enable-decoder=vp9 \
  --disable-avdevice \
  --disable-avformat \
  --disable-swresample \
  --disable-swscale \
  --disable-postproc \
  --disable-network \
  --extra-cflags="-Os -fPIC -DANDROID"

PATH="$TOOLCHAIN_BIN:$PATH" make -j"$(nproc)"
PATH="$TOOLCHAIN_BIN:$PATH" make install

popd >/dev/null

# FFmpeg's android config installs unversioned .so files (SLIB_INSTALL_NAME=$(SLIBNAME),
# no version symlinks), each already carrying its own soname, so stage them directly.
for lib in libavcodec libavutil; do
  real="$(find "$PREFIX/lib" -name "$lib.so" -type f | head -n1)"
  [[ -n "$real" ]] || { echo "error: $lib not built" >&2; exit 1; }
  install_so "$real"
done

echo "==> FFmpeg $FFMPEG_VERSION done."
