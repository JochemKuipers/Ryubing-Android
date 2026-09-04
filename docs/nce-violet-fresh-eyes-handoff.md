# NCE identity-mapped address space — handoff

**Date:** 2026-09-04  
**Project:** Ryubing-Android (Switch emulator on Android, ARM64)  
**Model:** Eden-style **identity mapping** — guest VA == host pointer inside a reserved window.  
**Native version:** `ryubing-nce 0.8.0 (identity-mapped AS: guest VA == host pointer; self-test)`  
**Verified:** Celeste reaches in-game under NCE after the soft-fault storm fix. Hades reaches a loading screen with audio. Violet still depends on dump/mod quality (Oodle corruption observed with and without NCE).

---

## 1. Situation (one paragraph)

The old boost/peel/rewrite pointer-translation scheme was incoherent (two address spaces). It was replaced with Eden’s identity-mapped window: reserve ~2^38 host VA in `[2^36, 2^39)`, lay the 39-bit Horizon process out inside that window (`code = base+0x8000000`), and never translate GPRs. Guest code runs natively; SIGSEGV soft-faults drive `MemoryTracking`. Celeste boots to in-game. Remaining work is title-specific (Violet content) and triage polish (`BOOT|PRESENT` logging).

---

## 2. Architecture

### Identity window
- `AddressSpace.TryCreateIdentityWindow` / `NceAddressSpace.TryReserve`: Eden `ChooseVirtualBase` math (`IdentityWindowPlacement`).
- `MemoryBlock.TryReserveAt` + `MAP_FIXED_NOREPLACE`.
- `MemoryManagerHostNoMirror(identityMapped: true)`: `ToOffset(va) = va - windowBase`; no `MemoryEhMeilleure` (NCE owns faults).
- Kernel: `AddrSpaceStart/End = [0, 2^39)` (so `QueryMemory(0)` / rtld work); **region placement** confined to the window (`mapLimit`). Log: `NCE|LAYOUT as=[0,2^39) window=[base,end) code=…`.

### Layers
| Layer | Path | Role |
|--------|------|------|
| Patches | `patches/0011`–`0013` (+ `0014` Nifm) | Memory/Cpu/HLE identity wiring; Nifm `NetworkChange` catch |
| Managed | `src/LibRyubing/Nce/` | `NceCpuContext` (no translation), `NceTrace`, `NceLayout`, self-test wrapper |
| Native | `src/RyubingAndroid/.../cpp/nce/` | Signals, run loop, patcher, `nce_self_test`, fault storm guard |
| Tests | `src/LibRyubing.Tests/`, native `nce/tests/` | Placement, layout, storm detector, identity MapView |
| Smoke | `./scripts/android-deploy.sh nce-smoke` | Invariants + milestones; cue: “smoke done” → read `tmp/nce-smoke-latest-triage.txt` |

### Lock protocol (critical)
`NceCore::RunThread` must lock thread parameters on entry and unlock on exit (Eden `EnterContext`/`ExitContext`). Without this, SVC trampoline leaves the lock held and `SignalInterrupt` deadlocks.

### Soft-fault storm guard
Tracking soft-faults during bulk uploads fault at the **same PC, advancing pages**. The guard must key on `(pc, page)`, not PC alone — PC-only DataAborted Celeste/Hades mid-load while audio kept playing.

---

## 3. Smoke / env knobs

```bash
./scripts/android-deploy.sh nce-smoke 90
NCE_SMOKE_TITLE_ID=01002B30028F6000 ./scripts/android-deploy.sh nce-smoke 90   # Celeste
NCE_SMOKE_USE_NCE=0 ./scripts/android-deploy.sh nce-smoke 60                   # JIT baseline
NCE_SMOKE_DISABLE_MODS=1 ./scripts/android-deploy.sh nce-smoke 60              # temp disable mods.json
# Optional only — leave unset to keep the app's 4 GiB default (do not force 12 GiB):
# NCE_SMOKE_MEM_CONFIG=0|1|2|3
```

Triage PASS ideally wants `BOOT|PRESENT frames=1`. User-confirmed in-game is the stronger signal if present logging is sparse.

---

## 4. Fixes landed this pass (identity migration)

1. Identity window + kernel layout (`as=[0,2^39)`, placement in window).
2. Deleted managed peel/rewrite / GetInfo boost heuristics.
3. `RunThread` lock bracket (SignalInterrupt deadlock).
4. Self-test: LDR/STR, SVC, alignment, DataAbort, **Interrupt**.
5. Nifm: catch `NetworkInformationException` on `NetworkChange` (Android aborts otherwise) — `patches/0014`.
6. Fault storm keyed on `(pc, page)`.
7. Storm detector ignores argumentless + wait-like SVCs.
8. Host tests for identity MapView / HLE↔guest coherence.

---

## 5. Known follow-ups

- **Violet:** Oodle LZ corruption / wild pointer after failed decompress — seen on JIT too with mods; verify dump + disable incompatible RomFS/ExeFS mods (`Pokemon_Compass_…`).
- **`BOOT|PRESENT`:** may still be missing from some captures even when frames are on screen; Notice+Info logging added — re-check after next install.
- **Triage:** ignore unrelated Zygote SIGKILL; do not treat ART `NCE|HOSTFAULT` null checks as crashes.
- Do **not** revive peel/rewrite / overlap-as-guest heuristics.

---

## 6. What not to try again

- GPR boost/peel between guest VA and `hostBase+va`.
- Setting `AddrSpaceStart = windowBase` (breaks rtld `QueryMemory(0)` → unresolved `nn::init::Start`).
- PC-only soft-fault storm → DataAbort.
- Leaving `RunThread` without unlock (SignalInterrupt spin forever).
