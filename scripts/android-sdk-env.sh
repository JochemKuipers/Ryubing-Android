#!/usr/bin/env bash
# Resolve Android SDK paths for Ryubing-Android scripts.
# Source this file:  source "$(dirname "$0")/android-sdk-env.sh"
#
# Priority for sdk root:
#   1. ANDROID_HOME / ANDROID_SDK_ROOT (already set)
#   2. sdk.dir in src/RyubingAndroid/local.properties
#   3. $HOME/Android/Sdk
#
# sdkmanager needs JDK 17 (JAXB); Gradle uses JDK 17 per gradle-daemon-jvm.properties.
set -euo pipefail

_ryubing_android_sdk_repo_root() {
  if [[ -n "${RYUBING_ANDROID_SDK_REPO_ROOT:-}" ]]; then
    printf '%s' "$RYUBING_ANDROID_SDK_REPO_ROOT"
    return
  fi
  local here="${BASH_SOURCE[0]}"
  while [[ -L "$here" ]]; do
    here="$(readlink "$here")"
  done
  cd "$(dirname "$here")/.." && pwd
}

RYUBING_ANDROID_SDK_REPO_ROOT="$(_ryubing_android_sdk_repo_root)"
RYUBING_ANDROID_DIR="$RYUBING_ANDROID_SDK_REPO_ROOT/src/RyubingAndroid"
RYUBING_DEFAULT_ANDROID_SDK="${HOME}/Android/Sdk"

_read_local_sdk_dir() {
  local props="$RYUBING_ANDROID_DIR/local.properties"
  [[ -f "$props" ]] || return 1
  sed -n 's/^sdk\.dir=//p' "$props" | tail -1 | sed 's/\\:/:/g; s/\\//g' | tr -d '\r'
}

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
  if sdk_dir="$(_read_local_sdk_dir)" && [[ -n "$sdk_dir" && -d "$sdk_dir" ]]; then
    export ANDROID_HOME="$sdk_dir"
  else
    export ANDROID_HOME="$RYUBING_DEFAULT_ANDROID_SDK"
  fi
fi

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

if [[ -z "${JAVA_HOME:-}" || "${RYUBING_FORCE_SDKMANAGER_JDK17:-0}" == "1" ]]; then
  for candidate in \
    /usr/lib/jvm/java-17-openjdk \
    /usr/lib/jvm/java-17-openjdk-amd64 \
    /usr/lib/jvm/jdk-17; do
    if [[ -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi

export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
