#!/usr/bin/env bash
#
# Bootstrap a user-local Android SDK (no pacman android-sdk packages required).
#
# Installs Google's command-line tools into ~/Android/Sdk by default, accepts
# licenses, pulls the packages this repo needs, and writes local.properties.
#
# sdkmanager must run on JDK 17 — Java 21 lacks JAXB and crashes with:
#   NoClassDefFoundError: javax/xml/bind/annotation/XmlSchema
#
# Usage:
#   scripts/setup-android-sdk.sh
#   RYUBING_ANDROID_SDK=$HOME/.local/android-sdk scripts/setup-android-sdk.sh
#
# Optional env:
#   RYUBING_ANDROID_SDK          install root (default: ~/Android/Sdk)
#   RYUBING_CMDLINE_TOOLS_URL    override cmdline-tools zip URL
#   JAVA_HOME                    must be JDK 17 for sdkmanager if auto-detect fails
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${RYUBING_ANDROID_SDK:-$HOME/Android/Sdk}"
LOCAL_PROPERTIES="$REPO_ROOT/src/RyubingAndroid/local.properties"

# Google command-line tools (Linux). Bump if the download 404s.
CMDLINE_TOOLS_URL="${RYUBING_CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip}"

NDK_VERSION="27.2.12479018"
CMAKE_VERSION="3.22.1"
PLATFORM="platforms;android-36"
BUILD_TOOLS="build-tools;35.0.0"

export RYUBING_ANDROID_SDK_REPO_ROOT="$REPO_ROOT"
export RYUBING_FORCE_SDKMANAGER_JDK17=1
# shellcheck source=android-sdk-env.sh
source "$REPO_ROOT/scripts/android-sdk-env.sh"

if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "error: JDK 17 is required for sdkmanager (Java 21 breaks JAXB)." >&2
  echo "  Install jdk17-openjdk, then re-run this script." >&2
  exit 1
fi

java_major="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | sed -n 's/.*version \"\([0-9]*\).*/\1/p')"
if [[ "$java_major" != "17" ]]; then
  echo "warning: sdkmanager should use JDK 17; JAVA_HOME=$JAVA_HOME (major=$java_major)" >&2
fi

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "error: missing required command: $1" >&2
    exit 1
  }
}

need_cmd curl
need_cmd unzip
need_cmd find

mkdir -p "$SDK_ROOT/cmdline-tools"

install_cmdline_tools() {
  if [[ -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]]; then
    echo "==> Command-line tools already present at $SDK_ROOT/cmdline-tools/latest"
    return
  fi

  local tmp
  tmp="$(mktemp -d)"

  echo "==> Downloading Android command-line tools"
  curl -fsSL "$CMDLINE_TOOLS_URL" -o "$tmp/cmdline-tools.zip"

  echo "==> Installing command-line tools to $SDK_ROOT/cmdline-tools/latest"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp/extract"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mkdir -p "$SDK_ROOT/cmdline-tools/latest"
  # Zip contains a single 'cmdline-tools' directory.
  mv "$tmp/extract/cmdline-tools/"* "$SDK_ROOT/cmdline-tools/latest/"
  rm -rf "$tmp"
}

sdkmanager() {
  "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" "$@"
}

write_local_properties() {
  # Gradle accepts forward slashes even on Windows; escape colons for local.properties.
  local escaped="${SDK_ROOT//\\/\\\\}"
  escaped="${escaped//:/\\:}"
  mkdir -p "$(dirname "$LOCAL_PROPERTIES")"
  printf 'sdk.dir=%s\n' "$escaped" >"$LOCAL_PROPERTIES"
  echo "==> Wrote $LOCAL_PROPERTIES"
}

main() {
  echo "==> Local Android SDK root: $SDK_ROOT"
  echo "==> sdkmanager Java: $JAVA_HOME"

  install_cmdline_tools

  export ANDROID_HOME="$SDK_ROOT"
  export ANDROID_SDK_ROOT="$SDK_ROOT"
  export PATH="$SDK_ROOT/platform-tools:$SDK_ROOT/cmdline-tools/latest/bin:$PATH"

  echo "==> Accepting SDK licenses"
  set +o pipefail
  yes | sdkmanager --licenses >/dev/null
  set -o pipefail

  echo "==> Installing SDK packages (NDK $NDK_VERSION, platform 36, CMake $CMAKE_VERSION)"
  sdkmanager --install \
    "platform-tools" \
    "$PLATFORM" \
    "$BUILD_TOOLS" \
    "ndk;$NDK_VERSION" \
    "cmake;$CMAKE_VERSION"

  write_local_properties

  cat <<EOF

==> Done.

Add to your shell (fish example):
  set -gx ANDROID_HOME $SDK_ROOT
  set -gx ANDROID_SDK_ROOT $SDK_ROOT
  fish_add_path $SDK_ROOT/platform-tools
  fish_add_path $SDK_ROOT/cmdline-tools/latest/bin

Build:
  cd src/RyubingAndroid && ./gradlew assembleDebug

Note: run sdkmanager with JDK 17 only. Gradle can use JDK 17 as configured in
gradle/gradle-daemon-jvm.properties.
EOF
}

main "$@"
