#!/usr/bin/env bash
# Build, install, and launch the Ryubing Android app from WSL/Cursor (no Android Studio).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_DIR="$REPO_ROOT/src/RyubingAndroid"
APK="$GRADLE_DIR/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="org.ryubing.android"
ACTIVITY="$PACKAGE/.MainActivity"
JDWP_PORT="${RYUBING_JDWP_PORT:-8700}"
WIRELESS_ENV="$REPO_ROOT/.wireless-adb.env"

# shellcheck source=android-sdk-env.sh
source "$REPO_ROOT/scripts/android-sdk-env.sh"

usage() {
    cat <<EOF
Usage: $(basename "$0") <command> [args]

Build / deploy:
  build              Assemble the debug APK
  install            Install the debug APK on the connected device
  launch             Start MainActivity
  deploy             build + install + launch
  deploy-logcat      deploy, then stream logcat until Ctrl+C
  debug              build + install + wait for JDWP debugger on :8700 (needs Java debugger)
  resume             Relaunch without waiting for debugger (unstick "Waiting for debugger")
  logcat             Stream app logs (Ctrl+C to stop)
  devices            List adb devices
  ensure-device      Verify adb device is connected (auto wireless reconnect)

Wireless debugging (recommended):
  wireless pair <ip:port> <code>   Pair using the phone's pairing port + 6-digit code
  wireless connect [ip:port]         Connect (uses .wireless-adb.env if no arg)
  wireless save <ip:port>            Save the connect endpoint for auto-reconnect
  wireless status                    Show saved endpoint + adb devices
  wireless disconnect [ip:port]      Disconnect wireless adb

Phone: Settings → Developer options → Wireless debugging
  - "Pair device with pairing code" → use with 'wireless pair'
  - Main screen shows IP:port for connect → use with 'wireless save' / 'wireless connect'

Examples:
  $(basename "$0") wireless pair 192.168.1.42:37123 123456
  $(basename "$0") wireless save 192.168.1.42:43445
  $(basename "$0") wireless connect
  $(basename "$0") deploy
EOF
}

load_wireless_env() {
    if [[ -f "$WIRELESS_ENV" ]]; then
        # shellcheck disable=SC1090
        source "$WIRELESS_ENV"
    fi
}

try_wireless_reconnect() {
    load_wireless_env
    if [[ -z "${RYUBING_ADB_CONNECT:-}" ]]; then
        return 1
    fi
    echo "Reconnecting wireless adb to $RYUBING_ADB_CONNECT ..."
    adb connect "$RYUBING_ADB_CONNECT" >/dev/null 2>&1 || true
}

adb_hint_no_device() {
    cat >&2 <<EOF
No adb device available.

Wireless debugging (recommended):
  1. Phone → Settings → Developer options → Wireless debugging → ON
  2. Pair (first time / after reset):
       $(basename "$0") wireless pair <pair-ip:port> <6-digit-code>
  3. Save + connect using the IP:port shown on the Wireless debugging screen:
       $(basename "$0") wireless save <connect-ip:port>
       $(basename "$0") wireless connect

If WSL cannot reach the phone, enable mirrored networking in Windows
%USERPROFILE%\\.wslconfig:
  [wsl2]
  networkingMode=mirrored

Then: wsl --shutdown
EOF
}

device_state() {
    adb devices 2>/dev/null | awk 'NR>1 && $2=="device" { print $2; exit }'
}

require_device() {
    adb start-server >/dev/null 2>&1 || true

    if [[ -n "$(device_state)" ]]; then
        return 0
    fi

    if adb devices 2>/dev/null | grep -qE '[[:space:]]unauthorized$'; then
        echo "Device connected but unauthorized — accept the debugging prompt on the phone." >&2
        exit 1
    fi

    try_wireless_reconnect || true

    if [[ -n "$(device_state)" ]]; then
        return 0
    fi

    adb_hint_no_device
    exit 1
}

ensure_native_deps() {
    local dir="$REPO_ROOT/src/RyubingAndroid/app/src/main/jniLibs/arm64-v8a"
    local missing=()
    for lib in libcrypto.so libssl.so libopenal.so libavcodec.so libavutil.so; do
        [[ -f "$dir/$lib" ]] || missing+=("$lib")
    done
    if ((${#missing[@]} > 0)); then
        echo "Missing native runtime libs: ${missing[*]}" >&2
        echo "Build them with: cd native-deps && ./build-all.sh" >&2
        exit 1
    fi
}

cmd_build() {
    ensure_native_deps
    cd "$GRADLE_DIR"
    ./gradlew assembleDebug
    echo "APK: $APK"
}

install_apk() {
    local output
    if output="$(adb install -r -d "$APK" 2>&1)"; then
        echo "$output"
        return 0
    fi

    if [[ "$output" == *"INSTALL_FAILED_UPDATE_INCOMPATIBLE"* ]] \
        || [[ "$output" == *"signatures do not match"* ]] \
        || [[ "$output" == *"signatures donot match"* ]]; then
        echo "Existing $PACKAGE install has a different signature — uninstalling first." >&2
        adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
        adb install -r -d "$APK"
        return
    fi

    echo "$output" >&2
    return 1
}

cmd_install() {
    require_device
    if [[ ! -f "$APK" ]]; then
        echo "Debug APK not found. Run: $(basename "$0") build" >&2
        exit 1
    fi
    install_apk
}

cmd_launch() {
    require_device
    adb shell am clear-debug-app >/dev/null 2>&1 || true
    adb shell am start -n "$ACTIVITY"
}

cmd_resume() {
    require_device
    adb shell am clear-debug-app >/dev/null 2>&1 || true
    adb shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
    adb shell am start -n "$ACTIVITY"
    echo "Launched $ACTIVITY (not waiting for debugger)."
}

wait_for_pid() {
    local pid=""
    for _ in {1..40}; do
        pid="$(adb shell pidof -s "$PACKAGE" 2>/dev/null | tr -d '\r')"
        if [[ -n "$pid" ]]; then
            echo "$pid"
            return 0
        fi
        sleep 0.25
    done
    return 1
}

cmd_debug() {
    require_device
    if [[ ! -f "$APK" ]]; then
        cmd_build
    fi
    install_apk
    adb shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
    adb shell am start -D -n "$ACTIVITY" >/dev/null

    local pid
    if ! pid="$(wait_for_pid)"; then
        echo "Timed out waiting for $PACKAGE to start." >&2
        exit 1
    fi

    adb forward --remove "tcp:$JDWP_PORT" >/dev/null 2>&1 || true
    adb forward "tcp:$JDWP_PORT" "jdwp:$pid"
    echo "JDWP ready on localhost:$JDWP_PORT (pid $pid)."
    echo "Attach a Java debugger, then resume the app."
    echo "Without a debugger, run: $(basename "$0") resume"
}

cmd_deploy() {
    cmd_build
    cmd_install
    cmd_launch
}

cmd_deploy_logcat() {
    cmd_deploy
    cmd_logcat
}

cmd_logcat() {
    require_device
    adb logcat --clear >/dev/null 2>&1 || true
    # Include Zygote/ActivityManager so native SIGSEGV exits are visible (filtered Ryubing tag alone misses them).
    adb logcat -v time Ryubing:D EmulationSession:D ActivityManager:I Zygote:I DEBUG:F AndroidRuntime:E '*:S'
}

cmd_devices() {
    adb devices -l
}

cmd_ensure_device() {
    adb shell am clear-debug-app >/dev/null 2>&1 || true
    adb forward --remove-all >/dev/null 2>&1 || true
    require_device
    adb devices -l
}

cmd_wireless_pair() {
    local endpoint="${1:-}"
    local code="${2:-}"
    if [[ -z "$endpoint" || -z "$code" ]]; then
        echo "Usage: $(basename "$0") wireless pair <ip:port> <6-digit-code>" >&2
        exit 1
    fi
    adb pair "$endpoint" "$code"
}

cmd_wireless_connect() {
    if [[ -n "$(device_state)" ]]; then
        echo "adb device already connected:"
        adb devices -l
        return 0
    fi

    local endpoint="${1:-}"
    if [[ -z "$endpoint" ]]; then
        load_wireless_env
        endpoint="${RYUBING_ADB_CONNECT:-}"
    fi
    if [[ -z "$endpoint" ]]; then
        echo "Usage: $(basename "$0") wireless connect <ip:port>" >&2
        echo "Or save first: $(basename "$0") wireless save <ip:port>" >&2
        exit 1
    fi
    adb connect "$endpoint"
    echo "---"
    adb devices -l
    if [[ -z "$(device_state)" ]]; then
        echo "Connect failed. Check phone IP/port and that WSL can reach the phone." >&2
        exit 1
    fi
}

cmd_wireless_save() {
    local endpoint="${1:-}"
    if [[ -z "$endpoint" ]]; then
        echo "Usage: $(basename "$0") wireless save <ip:port>" >&2
        exit 1
    fi
    echo "RYUBING_ADB_CONNECT=$endpoint" >"$WIRELESS_ENV"
    echo "Saved to $WIRELESS_ENV"
}

cmd_wireless_status() {
    if [[ -f "$WIRELESS_ENV" ]]; then
        echo "Saved config ($WIRELESS_ENV):"
        cat "$WIRELESS_ENV"
        echo
    else
        echo "No saved config. Copy .wireless-adb.env.example to .wireless-adb.env or run wireless save."
        echo
    fi
    adb devices -l
}

cmd_wireless_disconnect() {
    local endpoint="${1:-}"
    if [[ -n "$endpoint" ]]; then
        adb disconnect "$endpoint"
    else
        load_wireless_env
        if [[ -n "${RYUBING_ADB_CONNECT:-}" ]]; then
            adb disconnect "$RYUBING_ADB_CONNECT" || true
        fi
        adb disconnect || true
    fi
    adb devices -l
}

cmd_wireless() {
    local sub="${1:-}"
    shift || true
    case "$sub" in
        pair) cmd_wireless_pair "$@" ;;
        connect) cmd_wireless_connect "$@" ;;
        save) cmd_wireless_save "$@" ;;
        status) cmd_wireless_status ;;
        disconnect) cmd_wireless_disconnect "$@" ;;
        *)
            echo "Unknown wireless subcommand: $sub" >&2
            usage
            exit 1
            ;;
    esac
}

main() {
    local action="${1:-deploy}"
    case "$action" in
        build) cmd_build ;;
        install) cmd_install ;;
        launch) cmd_launch ;;
        deploy) cmd_deploy ;;
        deploy-logcat) cmd_deploy_logcat ;;
        debug) cmd_debug ;;
        resume) cmd_resume ;;
        logcat) cmd_logcat ;;
        devices) cmd_devices ;;
        ensure-device) cmd_ensure_device ;;
        wireless) shift; cmd_wireless "$@" ;;
        -h|--help|help) usage ;;
        *)
            echo "Unknown action: $action" >&2
            usage
            exit 1
            ;;
    esac
}

main "$@"
