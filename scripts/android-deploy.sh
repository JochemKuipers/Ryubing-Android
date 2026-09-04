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
  nce-smoke [secs]   deploy, capture NCE logs, write triage (default 30s)
  nce-triage [log]   Re-triage an existing smoke log (default: tmp/nce-smoke-latest.log)
  debug              build + install + wait for JDWP debugger on :8700 (needs Java debugger)
  resume             Relaunch without waiting for debugger (unstick "Waiting for debugger")
  logcat             Stream app logs (Ctrl+C to stop)
  devices            List adb devices
  ensure-device      Verify adb device is connected (USB preferred, auto wireless reconnect)

Device selection: USB is used automatically whenever a USB device is plugged
in; a saved wireless endpoint is the fallback. Export ANDROID_SERIAL to
override.

Wireless debugging (fallback):
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
  $(basename "$0") nce-smoke 40          # build/install + auto-launch title + triage
  $(basename "$0") nce-triage            # re-print triage for latest capture
  NCE_SMOKE_TITLE_ID=01008F6008C5E000 $(basename "$0") nce-smoke 40
  NCE_SMOKE_VERBOSE=1 $(basename "$0") nce-smoke 30
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

Easiest fix: plug the phone in over USB (USB debugging enabled in Developer
options) and rerun.

Wireless alternative:
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

# USB transports carry a `usb:` property in `adb devices -l`; wireless (TCP)
# transports have ip:port serials and no usb: property.
usb_device_serial() {
    adb devices -l 2>/dev/null | awk 'NR>1 && $2=="device" && / usb:/ { print $1; exit }'
}

remote_device_serial() {
    adb devices -l 2>/dev/null | awk 'NR>1 && $2=="device" && !/ usb:/ { print $1; exit }'
}

# Pick the device all subsequent adb commands will target. A USB transport wins
# whenever one is plugged in; a connected/saved wireless endpoint is the fallback.
# ANDROID_SERIAL is exported so bare `adb ...` calls stay unambiguous when the
# same phone is connected over both transports at once. A pre-set ANDROID_SERIAL
# pointing at an online device always wins (manual override).
select_device() {
    if [[ -n "${ANDROID_SERIAL:-}" ]] \
        && [[ "$(adb -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)" == "device" ]]; then
        echo "Using ANDROID_SERIAL=$ANDROID_SERIAL"
        return 0
    fi

    local serial
    serial="$(usb_device_serial)"
    if [[ -n "$serial" ]]; then
        export ANDROID_SERIAL="$serial"
        echo "Using USB device $serial"
        return 0
    fi

    serial="$(remote_device_serial)"
    if [[ -n "$serial" ]]; then
        export ANDROID_SERIAL="$serial"
        echo "Using wireless device $serial"
        return 0
    fi

    return 1
}

require_device() {
    adb start-server >/dev/null 2>&1 || true

    if select_device; then
        return 0
    fi

    if adb devices 2>/dev/null | grep -qE '[[:space:]]unauthorized$'; then
        echo "Device connected but unauthorized — accept the debugging prompt on the phone." >&2
        exit 1
    fi

    try_wireless_reconnect || true

    if select_device; then
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
    # Ryubing = managed/.NET logs; RyubingNCE = native NCE (signals, run_thread).
    # Zygote/ActivityManager/DEBUG so native crashes are visible.
    adb logcat -v time Ryubing:D RyubingNCE:D EmulationSession:D ActivityManager:I Zygote:I DEBUG:F AndroidRuntime:E '*:S'
}

# Count matching lines; always a single integer (never multiline / empty).
_nce_count() {
    local n
    n="$(grep -cE "$1" "$2" 2>/dev/null | tr -d '\n' || true)"
    [[ -n "$n" ]] || n=0
    echo "$n"
}

# ---------------------------------------------------------------------------
# NCE smoke triage
#
# Parses the structured `NCE|` lines emitted by the managed (LibRyubing.Nce,
# Ryujinx.HLE patches) and native (libryubing-nce) layers and asserts the
# identity-mapping invariants, then reports boot milestones. The verdict is
# PASS only when a frame was actually presented (BOOT|PRESENT), so PASS means
# "past the black screen", not merely "entered NCE".
#
# Invariants (each produces an `invariant:` line, FAIL on violation):
#   AS         window reserved, 2 MiB aligned, in [2^36, 2^39)
#   MEMCFG     native window == managed window
#   LAYOUT     every kernel region (code/alias/heap/stack/tlsio) inside the window
#   CODE       loader codeStart inside the code region
#   SELFTEST   nce_self_test PASS (only asserted if it ran; debug level >= 2)
#   FAULT      no `NCE|FAULT outside-window` / `halt` / `storm`
#   STORM      no `NCE|STORM` (identical-SVC loop detector)
# ---------------------------------------------------------------------------

# First match of an extended regex, printed once; empty if none.
_nce_first() {
    grep -oE "$1" "$2" 2>/dev/null | head -n1 || true
}

# Extract `name=[0xA,0xB)` from a line -> "A B" (hex, no 0x). Empty if absent.
_nce_range() {
    local line="$1" name="$2"
    local m
    m="$(printf '%s' "$line" | grep -oE "(^| )$name=\[0x[0-9A-Fa-f]+,0x[0-9A-Fa-f]+\)" | head -n1 || true)"
    [[ -n "$m" ]] || return 0
    printf '%s' "$m" | sed -E 's/.*\[0x([0-9A-Fa-f]+),0x([0-9A-Fa-f]+)\)/\1 \2/'
}

# Extract `key=0xHEX` value from a line -> hex digits without 0x. Empty if absent.
_nce_hex_field() {
    local line="$1" key="$2"
    printf '%s' "$line" | grep -oE "(^| )$key=0x[0-9A-Fa-f]+" | head -n1 | sed -E 's/.*=0x//' || true
}

# Write/print the triage report for a smoke capture. Sets _NCE_VERDICT / _NCE_EXIT.
nce_write_triage() {
    local raw="$1"
    local triage="$2"
    local filtered="${3:-}"

    local -a invariants=()
    local -a failures=()
    local -a warnings=()

    # View of the log without the nce_self_test section: it deliberately triggers a
    # DataAbort (NCE|FAULT halt) and must not count as a guest fault.
    local nost
    nost="$(mktemp)"
    awk '/NCE\|SELFTEST begin/{skip=1} !skip{print} /NCE\|SELFTEST end/{skip=0}' "$raw" >"$nost" 2>/dev/null || cp "$raw" "$nost"

    # ---- window -----------------------------------------------------------
    local as_line win_base win_end win_size
    as_line="$(grep -E 'NCE\|AS reserved' "$raw" 2>/dev/null | head -n1 || true)"
    win_base="$(_nce_hex_field "$as_line" base)"
    win_end="$(_nce_hex_field "$as_line" end)"
    win_size="$(_nce_hex_field "$as_line" size)"
    if [[ -z "$win_base" || -z "$win_end" ]]; then
        if grep -qE 'NCE\|AS reserve FAILED' "$raw" 2>/dev/null; then
            invariants+=("AS        FAIL  identity window reservation failed (fell back to JIT)")
            failures+=("no identity window (reserve failed)")
        elif grep -qE 'NCE CPU backend requested|UseNce' "$raw" 2>/dev/null; then
            invariants+=("AS        FAIL  no NCE|AS reserved line")
            failures+=("no NCE|AS line")
        else
            invariants+=("AS        n/a   NCE not requested in this capture")
        fi
        win_base=0; win_end=0; win_size=0
    else
        local b=$((16#$win_base)) e=$((16#$win_end))
        local ok=1 why=""
        (( b % 0x200000 == 0 )) || { ok=0; why="$why base-not-2MiB-aligned"; }
        (( b >= (1 << 36) )) || { ok=0; why="$why base<2^36"; }
        (( e <= (1 << 39) )) || { ok=0; why="$why end>2^39"; }
        (( e > b )) || { ok=0; why="$why empty"; }
        if (( ok )); then
            invariants+=("AS        ok    window=[0x$win_base,0x$win_end) size=0x$win_size")
        else
            invariants+=("AS        FAIL  window=[0x$win_base,0x$win_end):$why")
            failures+=("bad window placement:$why")
        fi
    fi

    # ---- native memory config must agree ----------------------------------
    local memcfg_line memcfg_range
    memcfg_line="$(grep -E 'NCE\|MEMCFG window=' "$raw" 2>/dev/null | grep -v 'handler=0' | tail -n1 || true)"
    if [[ -n "$memcfg_line" ]]; then
        memcfg_range="$(_nce_range "$memcfg_line" window)"
        local mb me
        read -r mb me <<<"$memcfg_range"
        if [[ "$win_base" != 0 ]] && (( 16#$mb == 16#$win_base && 16#$me == 16#$win_end )); then
            invariants+=("MEMCFG    ok    native window matches managed")
        else
            invariants+=("MEMCFG    FAIL  native window=[0x$mb,0x$me) != managed [0x$win_base,0x$win_end)")
            failures+=("native/managed window mismatch")
        fi
    elif [[ "$win_base" != 0 ]]; then
        invariants+=("MEMCFG    FAIL  no NCE|MEMCFG from native (nce_set_memory_config never called?)")
        failures+=("no NCE|MEMCFG")
    fi

    # ---- kernel layout inside window --------------------------------------
    local layout_line
    layout_line="$(grep -E 'NCE\|LAYOUT as=' "$raw" 2>/dev/null | head -n1 || true)"
    if [[ -n "$layout_line" && "$win_base" != 0 ]]; then
        local region bad="" rs re as_s as_e
        # Kernel address space must stay [0, 2^39): rtld discovers modules by walking
        # QueryMemory from 0; anything else ends the walk before the first NSO.
        read -r as_s as_e <<<"$(_nce_range "$layout_line" as)"
        if [[ -z "$as_s" ]] || (( 16#$as_s != 0 || 16#$as_e != (1 << 39) )); then
            bad="$bad as=[0x${as_s:-?},0x${as_e:-?})!=[0,2^39)"
        fi
        for region in code aslr alias heap stack tlsio; do
            read -r rs re <<<"$(_nce_range "$layout_line" "$region")"
            if [[ -z "$rs" ]]; then
                bad="$bad $region=missing"
                continue
            fi
            if (( 16#$rs < 16#$win_base || 16#$re > 16#$win_end || 16#$re < 16#$rs )); then
                bad="$bad $region=[0x$rs,0x$re)"
            fi
        done
        if [[ -z "$bad" ]]; then
            invariants+=("LAYOUT    ok    as=[0,2^39); code/aslr/alias/heap/stack/tlsio all inside window")
        else
            invariants+=("LAYOUT    FAIL $bad")
            failures+=("kernel layout:$bad")
        fi
    elif [[ "$win_base" != 0 ]]; then
        invariants+=("LAYOUT    FAIL  no NCE|LAYOUT line (process never created?)")
        failures+=("no NCE|LAYOUT")
    fi

    # ---- loader code start ------------------------------------------------
    local code_line code_start cs ce
    code_line="$(grep -E 'NCE\|CODE window=' "$raw" 2>/dev/null | head -n1 || true)"
    if [[ -n "$code_line" && -n "$layout_line" ]]; then
        code_start="$(_nce_hex_field "$code_line" codeStart)"
        read -r cs ce <<<"$(_nce_range "$layout_line" code)"
        if [[ -n "$code_start" && -n "$cs" ]] && (( 16#$code_start >= 16#$cs && 16#$code_start < 16#$ce )); then
            invariants+=("CODE      ok    codeStart=0x$code_start inside code region")
        else
            invariants+=("CODE      FAIL  codeStart=0x${code_start:-?} not in code=[0x${cs:-?},0x${ce:-?})")
            failures+=("codeStart outside code region")
        fi
    fi

    # ---- self test --------------------------------------------------------
    local st_pass st_fail st_skip
    st_pass="$(_nce_count 'NCE\|SELFTEST PASS' "$raw")"
    st_fail="$(_nce_count 'NCE\|SELFTEST FAIL' "$raw")"
    st_skip="$(_nce_count 'NCE\|SELFTEST SKIP' "$raw")"
    if (( st_fail > 0 )); then
        invariants+=("SELFTEST  FAIL  $(grep -E 'NCE\|SELFTEST (FAIL|stage=)' "$raw" | head -n1 | sed -E 's/.*NCE\|SELFTEST //')")
        failures+=("nce_self_test failed")
    elif (( st_pass > 0 )); then
        invariants+=("SELFTEST  ok    $(grep -E 'NCE\|SELFTEST PASS' "$raw" | head -n1 | sed -E 's/.*NCE\|SELFTEST PASS //')")
    elif (( st_skip > 0 )); then
        invariants+=("SELFTEST  warn  skipped (native lib without nce_self_test)")
        warnings+=("self-test skipped")
    else
        invariants+=("SELFTEST  n/a   not run (NceDebugLevel < 2)")
    fi

    # ---- faults / storms --------------------------------------------------
    local f_outside f_halt f_storm f_managed storm_n pagefault_fail
    f_outside="$(_nce_count 'NCE\|FAULT outside-window' "$nost")"
    f_halt="$(_nce_count 'NCE\|FAULT halt' "$nost")"
    f_storm="$(_nce_count 'NCE\|FAULT storm' "$nost")"
    f_managed="$(_nce_count 'NCE\|FAULT (prefetch|data|PrefetchAbort|DataAbort|Alignment|Undefined|Unknown)' "$nost")"
    pagefault_fail="$(_nce_count 'NCE\|PAGEFAULT handler failed' "$nost")"
    storm_n="$(_nce_count 'NCE\|STORM' "$raw")"
    if (( f_outside > 0 || f_halt > 0 || f_storm > 0 || pagefault_fail > 0 )); then
        invariants+=("FAULT     FAIL  outside-window=$f_outside halt=$f_halt storm=$f_storm pagefault-handler-failed=$pagefault_fail managed-aborts=$f_managed")
        (( f_outside > 0 )) && failures+=("$f_outside fault(s) outside identity window")
        (( f_halt > 0 )) && failures+=("$f_halt fault(s) with no page-fault handler")
        (( f_storm > 0 )) && failures+=("same-PC fault storm")
        (( pagefault_fail > 0 )) && failures+=("managed page fault handler threw")
    elif (( f_managed > 0 )); then
        invariants+=("FAULT     FAIL  $f_managed guest abort(s) surfaced to managed (PrefetchAbort/DataAbort/...)")
        failures+=("$f_managed guest abort(s)")
    else
        invariants+=("FAULT     ok    none")
    fi
    if (( storm_n > 0 )); then
        invariants+=("STORM     FAIL  $(grep -E 'NCE\|STORM' "$raw" | head -n1 | sed -E 's/.*NCE\|STORM //')")
        failures+=("SVC storm")
    else
        invariants+=("STORM     ok    none")
    fi

    # ---- activity counters ------------------------------------------------
    local run_enter run_exit native_enter native_exit svc_n break_n halt_n svcfail_n
    run_enter="$(_nce_count 'NCE\|RUN enter ' "$raw")"
    run_exit="$(_nce_count 'NCE\|RUN exit ' "$raw")"
    native_enter="$(_nce_count 'NCE\|RUN native-enter' "$raw")"
    native_exit="$(_nce_count 'NCE\|RUN native-exit' "$raw")"
    (( run_enter > 0 )) || run_enter="$native_enter"
    svc_n="$(_nce_count 'NCE\|SVC ' "$raw")"
    break_n="$(_nce_count 'NCE\|BREAK|guest Break|The guest program broke execution' "$raw")"
    halt_n="$(_nce_count 'NCE\|HALT' "$raw")"
    svcfail_n="$(_nce_count 'NCE\|SVCFAIL' "$raw")"

    # ---- crash detection --------------------------------------------------
    local crash_line
    # Only treat *our* process death / abort as a crash. Unrelated Zygote SIGKILL noise
    # (other apps) and ART null-check HOSTFAULTs must not fail the triage.
    crash_line="$(grep -E 'Fatal signal|FATAL EXCEPTION|Process org\.ryubing\.android .* has died|org\.ryubing\.android .* exited due to signal|Process [0-9]+ exited due to signal [0-9]+ \((Segmentation fault|Aborted)\)' "$raw" 2>/dev/null | grep -E 'org\.ryubing\.android|Fatal signal|FATAL EXCEPTION' | head -n1 || true)"
    if [[ -z "$crash_line" ]]; then
        # Zygote line often lacks the package name; match our pid from earlier Ryubing lines.
        local our_pid
        our_pid="$(grep -oE 'Ryubing(NCE)?\( *[0-9]+\)' "$raw" 2>/dev/null | head -n1 | grep -oE '[0-9]+' || true)"
        if [[ -n "$our_pid" ]]; then
            crash_line="$(grep -E "Process ${our_pid} exited due to signal|Process org\.ryubing\.android \(pid ${our_pid}\)" "$raw" 2>/dev/null | head -n1 || true)"
        fi
    fi
    crash_line="${crash_line#*: }"

    # ---- boot milestones --------------------------------------------------
    local m_layout m_run m_shader m_npad m_present1 m_present60 present_line
    m_layout="$( [[ -n "$layout_line" ]] && echo yes || echo no )"
    m_run="$( (( run_enter > 0 )) && echo yes || echo no )"
    m_shader="$( grep -qE 'Shader cache loaded' "$raw" 2>/dev/null && echo yes || echo no )"
    m_npad="$( grep -qE 'SetupNpad|ActivateNpad|SetSupportedNpad' "$raw" 2>/dev/null && echo yes || echo no )"
    present_line="$(grep -E 'BOOT\|PRESENT frames=1 ' "$raw" 2>/dev/null | head -n1 || true)"
    m_present1="$( [[ -n "$present_line" ]] && echo yes || echo no )"
    m_present60="$( grep -qE 'BOOT\|PRESENT frames=60 ' "$raw" 2>/dev/null && echo yes || echo no )"

    # ---- verdict ----------------------------------------------------------
    local verdict exit_code
    if [[ -n "$crash_line" ]]; then
        # A dead process explains every missing line; report it first.
        verdict="FAIL (process crashed: ${crash_line:0:100})"
        exit_code=1
    elif (( ${#failures[@]} > 0 )); then
        verdict="FAIL (${failures[0]})"
        exit_code=1
    elif (( break_n > 0 )); then
        verdict="FAIL (guest Break x$break_n)"
        exit_code=1
    elif [[ "$m_present1" == yes ]]; then
        verdict="PASS (frame presented; $(printf '%s' "$present_line" | grep -oE 'uptimeMs=[0-9]+'); run_enter=$run_enter svc=$svc_n)"
        exit_code=0
    elif (( run_enter > 0 )); then
        verdict="FAIL (black screen: entered NCE run_enter=$run_enter svc=$svc_n halt=$halt_n, no frame presented)"
        exit_code=1
    elif [[ -n "$layout_line" ]]; then
        verdict="FAIL (process created but NCE never entered the run loop)"
        exit_code=1
    elif [[ "$win_base" != 0 ]]; then
        verdict="FAIL (window reserved but no process/layout)"
        exit_code=1
    else
        verdict="INCOMPLETE (no NCE activity in window)"
        exit_code=1
    fi

    local version_line
    version_line="$(grep -E 'ryubing-nce |NCE CPU backend|NCE\|MEMCFG base=' "$raw" 2>/dev/null | head -n 3 || true)"

    {
        echo "NCE smoke triage"
        echo "================"
        echo "log: $raw"
        echo "verdict: $verdict"
        echo
        echo "invariants:"
        local inv
        for inv in "${invariants[@]}"; do echo "  $inv"; done
        if (( ${#warnings[@]} > 0 )); then
            echo "  warnings: ${warnings[*]}"
        fi
        echo
        echo "milestones:"
        echo "  layout=$m_layout  run_loop=$m_run  shader_cache=$m_shader  npad=$m_npad  first_frame=$m_present1  60_frames=$m_present60"
        echo
        echo "activity:"
        echo "  managed run enter=$run_enter exit=$run_exit  native run enter=$native_enter exit=$native_exit"
        echo "  svc=$svc_n svcfail=$svcfail_n halt=$halt_n break=$break_n"
        echo "  faults: outside=$f_outside halt=$f_halt storm=$f_storm managed=$f_managed  svc_storm=$storm_n"
        echo
        echo "version / host:"
        if [[ -n "$version_line" ]]; then echo "$version_line"; else echo "  (none)"; fi
        echo
        echo "last NCE errors / breaks / faults / halts (if any):"
        grep -E 'NCE\|(FAULT|HOSTFAULT|BREAK|HALT|STORM|SVCFAIL|PAGEFAULT handler)|guest Break|broke execution|nn::diag::detail::Abort|Fatal signal|FATAL EXCEPTION|exited due to signal' \
            "$raw" 2>/dev/null | tail -n 20 || echo "  (none)"
        echo
        echo "last SVC trace (if verbose):"
        grep -E 'NCE\|(SVC|TRACE)' "$raw" 2>/dev/null | tail -n 12 || echo "  (none)"
        echo
        echo "Agent: read this file (or $raw). No need to paste logs."
        echo "Chat cue: say \"smoke done\"."
    } >"$triage"

    if [[ -n "$filtered" ]]; then
        grep -E 'NCE\||BOOT\||RyubingNCE|guest fault|Invalid memory|PrefetchAbort|DataAbort|Using NCE|patch_module|signal handlers|Shader cache loaded|Fatal signal|backtrace|#[0-9]+ pc ' \
            "$raw" 2>/dev/null | head -n 600 >"$filtered" || true
    fi

    rm -f "$nost"
    _NCE_VERDICT="$verdict"
    _NCE_EXIT="$exit_code"
}

cmd_launch_title() {
    require_device
    local title_id="${NCE_SMOKE_TITLE_ID:-01008F6008C5E000}"
    local path="${NCE_SMOKE_LAUNCH_PATH:-}"
    adb shell am clear-debug-app >/dev/null 2>&1 || true
    adb shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
    if [[ -n "$path" ]]; then
        echo "Launching $ACTIVITY with LAUNCH_PATH=$path"
        adb shell am start -n "$ACTIVITY" \
            --es org.ryubing.android.LAUNCH_PATH "$path"
    else
        echo "Launching $ACTIVITY with LAUNCH_TITLE_ID=$title_id"
        adb shell am start -n "$ACTIVITY" \
            --es org.ryubing.android.LAUNCH_TITLE_ID "$title_id"
    fi
}

# Build/install, auto-launch title, capture logcat, write triage + latest symlinks.
# Default title: Pokémon Violet. Override with NCE_SMOKE_TITLE_ID or NCE_SMOKE_LAUNCH_PATH.
# Tell the agent "smoke done" — no paste.
cmd_nce_smoke() {
    local secs="${1:-30}"
    if ! [[ "$secs" =~ ^[0-9]+$ ]] || [[ "$secs" -lt 5 ]]; then
        echo "Usage: $(basename "$0") nce-smoke [seconds>=5]" >&2
        exit 1
    fi

    local log_dir="$REPO_ROOT/tmp"
    mkdir -p "$log_dir"
    local stamp
    stamp="$(date +%Y%m%d-%H%M%S)"
    local raw="$log_dir/nce-smoke-$stamp.log"
    local filtered="$log_dir/nce-smoke-$stamp-nce.log"
    local triage="$log_dir/nce-smoke-$stamp-triage.txt"

    cmd_build
    cmd_install

    require_device
    # Ensure enough guest DRAM for Violet MapPhysicalMemory (prefs may still be 4GiB).
    # MemoryConfiguration: 0=4GiB 1=6GiB 2=8GiB 3=12GiB. Override with NCE_SMOKE_MEM_CONFIG.
    local mem_cfg="${NCE_SMOKE_MEM_CONFIG:-3}"
    adb shell "run-as $PACKAGE sh -c 'cd shared_prefs 2>/dev/null || exit 0; \
      if [ -f ryubing_settings.xml ]; then \
        grep -q mem_config ryubing_settings.xml \
          && sed -i \"s/name=\\\"mem_config\\\" value=\\\"[0-9]*\\\"/name=\\\"mem_config\\\" value=\\\"${mem_cfg}\\\"/\" ryubing_settings.xml \
          || sed -i \"s|<map>|<map>\\n    <int name=\\\"mem_config\\\" value=\\\"${mem_cfg}\\\" />|\" ryubing_settings.xml; \
      else \
        printf \"%s\\n\" \"<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\" \"<map>\" \"    <int name=\\\"mem_config\\\" value=\\\"${mem_cfg}\\\" />\" \"</map>\" > ryubing_settings.xml; \
      fi'" >/dev/null 2>&1 \
      && echo "Forced ryubing_settings mem_config=${mem_cfg}" \
      || echo "Note: could not force mem_config=${mem_cfg} via run-as"

    # NCE_SMOKE_USE_NCE=0 runs the same smoke on the JIT backend (baseline for
    # deciding whether a failure is NCE-specific); default leaves the pref alone.
    if [[ -n "${NCE_SMOKE_USE_NCE:-}" ]]; then
        local use_nce="false"
        [[ "$NCE_SMOKE_USE_NCE" != "0" ]] && use_nce="true"
        adb shell "run-as $PACKAGE sh -c 'cd shared_prefs 2>/dev/null || exit 0; \
          [ -f ryubing_settings.xml ] || exit 0; \
          grep -q use_nce ryubing_settings.xml \
            && sed -i \"s/name=\\\"use_nce\\\" value=\\\"[a-z]*\\\"/name=\\\"use_nce\\\" value=\\\"${use_nce}\\\"/\" ryubing_settings.xml \
            || sed -i \"s|<map>|<map>\\n    <boolean name=\\\"use_nce\\\" value=\\\"${use_nce}\\\" />|\" ryubing_settings.xml'" >/dev/null 2>&1 \
          && echo "Forced ryubing_settings use_nce=${use_nce}" \
          || echo "Note: could not force use_nce=${use_nce} via run-as"
    fi

    # NCE_SMOKE_DISABLE_MODS=1 runs the title with every mod in its mods.json flipped to
    # enabled=false for this run only (the original file is restored after capture).
    # Mods built for another game version corrupt archive/exefs data and make the guest
    # fault in FileLoaderFromArchive on both JIT and NCE, which is not an emulator bug.
    # ModLoader matches mods.json entries by the *emulator's* full mod path
    # (<Ryubing>/mods/contents/<title>/<mod>), so the file is regenerated from the
    # directory listing rather than edited (the app's own mods.json may use another prefix).
    local title_lc
    title_lc="$(echo "${NCE_SMOKE_TITLE_ID:-01008F6008C5E000}" | tr 'A-Z' 'a-z')"
    local mods_json="/storage/emulated/0/Ryubing/games/$title_lc/mods.json"
    local mods_dir="/storage/emulated/0/Ryubing/mods/contents/$title_lc"
    local mods_backup=""
    if [[ "${NCE_SMOKE_DISABLE_MODS:-0}" == "1" ]]; then
        mods_backup="$(mktemp)"
        adb shell "cat '$mods_json' 2>/dev/null" >"$mods_backup" || true
        local mod_names
        mod_names="$(adb shell "ls -1 '$mods_dir' 2>/dev/null" | tr -d '\r')"
        if [[ -n "$mod_names" ]]; then
            {
                echo '{'
                echo '  "mods": ['
                local first=1 name
                while IFS= read -r name; do
                    [[ -z "$name" ]] && continue
                    (( first )) || echo ','
                    first=0
                    printf '    {"name": "%s", "path": "%s/%s", "enabled": false}' "$name" "$mods_dir" "$name"
                done <<<"$mod_names"
                echo
                echo '  ]'
                echo '}'
            } | adb shell "cat > '$mods_json'" \
                && echo "Disabled $(wc -l <<<"$mod_names") mod(s) via $mods_json for this run (restored afterwards)"
        else
            echo "Note: no mods found under $mods_dir"
        fi
    fi

    adb logcat --clear >/dev/null 2>&1 || true
    cmd_launch_title

    echo "Capturing logcat for ${secs}s → $raw"
    set +e
    timeout --signal=INT "${secs}s" adb logcat -v time \
        Ryubing:D RyubingNCE:D EmulationSession:D ActivityManager:I Zygote:I DEBUG:F AndroidRuntime:E '*:S' \
        >"$raw" 2>&1
    local cap_rc=$?
    set -e
    if [[ "$cap_rc" -ne 0 && "$cap_rc" -ne 124 ]]; then
        echo "logcat capture exited $cap_rc (continuing with whatever was written)" >&2
    fi

    if [[ -n "$mods_backup" ]]; then
        if [[ -s "$mods_backup" ]]; then
            adb shell "cat > '$mods_json'" <"$mods_backup" && echo "Restored $mods_json"
        else
            adb shell "rm -f '$mods_json'" && echo "Removed temporary $mods_json"
        fi
        rm -f "$mods_backup"
    fi

    nce_write_triage "$raw" "$triage" "$filtered"

    ln -sfn "$(basename "$raw")" "$log_dir/nce-smoke-latest.log"
    ln -sfn "$(basename "$triage")" "$log_dir/nce-smoke-latest-triage.txt"
    ln -sfn "$(basename "$filtered")" "$log_dir/nce-smoke-latest-nce.log"

    echo
    echo "======== NCE smoke triage ($triage) ========"
    cat "$triage"
    echo "======== end triage ========"
    if [[ "${NCE_SMOKE_VERBOSE:-0}" == "1" ]]; then
        echo
        echo "======== filtered (verbose) ========"
        if [[ -s "$filtered" ]]; then cat "$filtered"; else echo "(empty)"; fi
        echo "======== end filtered ========"
    fi
    echo "Latest: $log_dir/nce-smoke-latest-triage.txt"
    echo "Verdict: $_NCE_VERDICT"
    exit "$_NCE_EXIT"
}

cmd_nce_triage() {
    local raw="${1:-$REPO_ROOT/tmp/nce-smoke-latest.log}"
    if [[ ! -f "$raw" ]]; then
        echo "No log at $raw — run nce-smoke first." >&2
        exit 1
    fi
    raw="$(readlink -f "$raw" 2>/dev/null || echo "$raw")"
    local triage="${raw%.log}-triage.txt"
    nce_write_triage "$raw" "$triage" ""
    cat "$triage"
    echo "Verdict: $_NCE_VERDICT"
    exit "$_NCE_EXIT"
}

cmd_devices() {
    adb devices -l
}

cmd_ensure_device() {
    # Select the device first so cleanup targets it (and never trips over
    # multiple transports when the phone is on USB + WiFi simultaneously).
    require_device
    adb shell am clear-debug-app >/dev/null 2>&1 || true
    adb forward --remove-all >/dev/null 2>&1 || true
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
    # Only bail out early if a wireless device is already connected — a USB-only
    # connection must not block an explicit `wireless connect`.
    if [[ -n "$(remote_device_serial)" ]]; then
        echo "Wireless adb device already connected:"
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
    if [[ -z "$(remote_device_serial)" ]]; then
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
        nce-smoke) shift; cmd_nce_smoke "$@" ;;
        nce-triage) shift; cmd_nce_triage "$@" ;;
        launch-title) cmd_launch_title ;;
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
