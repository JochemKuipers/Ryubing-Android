#!/usr/bin/env bash
#
# Build every native runtime dependency and stage the .so files into the app's
# jniLibs. Run once before ./gradlew assembleDebug (and after bumping versions).
#
# Prerequisites: ANDROID_NDK_HOME set, plus curl, cmake, make, tar, and (for
# FFmpeg) a POSIX shell toolchain.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"$DIR/build-openssl.sh"
"$DIR/build-openal.sh"
"$DIR/build-ffmpeg.sh"

echo
echo "==> All native dependencies built and staged into jniLibs."
