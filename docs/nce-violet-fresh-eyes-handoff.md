# NCE Pokémon Violet black-screen — fresh-eyes handoff

**Date:** 2026-09-04  
**Project:** Ryubing-Android (Switch emulator on Android, ARM64)  
**Goal:** Get *Pokémon Violet* (`01008F6008C5E000`) past black screen using Eden-style **NCE** (Native Code Execution: run guest AArch64 directly on host).  
**Current native version string:** `ryubing-nce 0.7.43 (peel MapSharedMemory; QM high/region rewrite; overlap DataAbort)`  
**Device:** wireless ADB, often `192.168.2.176:42331` (Adreno 750 / Turnip Mesa).

Feed this whole file to another model. Ask it for: root-cause hypotheses on the **post-MapSharedMemory QueryMemory storm**, concrete next experiments, and what we should *not* try again.

---

## 1. One-paragraph situation

Violet under NCE on Android HostNoMirror boots far enough to map memory, relocate Alias `MapProcessMemory`, succeed at `MapSharedMemory`, load the Vulkan shader cache, and connect a Pro Controller — then the guest CPU often **spins `svcQueryMemory` hundreds of times on the same Alias VA** (`0x90BC28000` in recent logs). Sometimes that ends in `ApplicationAborted` / guest `Break`. The screen stays black. This is **not** “NCE never starts”; it is a **direct-map host/guest pointer coherence** problem after bootstrap, plus truncated address space on Android.

**PASS in smoke triage ≠ playable.** PASS only means “entered NCE, no Break/Abort/fault in the capture window.”

---

## 2. Architecture (what NCE means here)

### Memory model
- Backend: `MemoryManagerHostNoMirror` (Android cannot reserve mirrored 512 GiB AS).
- Typical smoke values:
  - `hostBase ≈ 0x18B8D8000` (mmap base of reserved AS)
  - `asSize = 0x5500000000` (truncated; app wants `0x8000000000`)
- Warning always logged: *Allocated address space smaller than guest application requirements*.
- Guest VAs are theoretically `[0, asSize)`. Host pointers for native LDR/STR are `hostBase + guestVA`.
- **Critical overlap:** `hostBase < asSize`, so a value like `0x90BC28000` is simultaneously:
  - a valid **guest VA** (inside Alias), and
  - numerically inside the **host window** `[hostBase, hostBase+asSize)`.

Disambiguation lives in patches `0015` + managed peel/rewrite in `NceCpuContext.cs`. Getting this wrong causes: wrong soft-faults, ASLR picking Alias destinations, `InvalidMemoryRegion`, or infinite QueryMemory loops.

### Split implementation
| Layer | Path | Role |
|--------|------|------|
| Native | `src/RyubingAndroid/app/src/main/cpp/nce/` → `libryubing-nce.so` | Signals, run loop, patcher, version string in `nce.cpp` |
| Managed | `src/LibRyubing/Nce/NceCpuContext.cs` | SVC peel/rewrite, GetInfo boost, MapProcessMemory Alias relocate + GPR patch |
| Upstream | `upstream/ryubing/` + `patches/0011`–`0018` | HostMapped/NoMirror, entropy, QueryMemory size clamp |
| Research | `research-material/eden/` | Reference NCE / HostMemory behavior |

### Region GetInfo (typical Violet 38-bit layout under truncated AS)
From smoke (guest, before boost):
- Alias: `0x851000000`, size `0x800000000`
- Heap: `0x1051000000`, size `0x300000000` (12 GiB mem_config)
- Stack: `0x811000000`, size `0x40000000`
- Aslr: `0x8000000`, size `0x3FF8000000`
- Kernel floors `addrSpaceEnd` to `1 << floor(log2(asSize))` → **0x4000000000** when asSize is `0x55…`

**Host-boosted GetInfo types (always):** 2 Alias, 4 Heap, 14 Stack, 20 UserException.  
**Not boosted:** Aslr (12/13) — boosting Aslr previously caused QueryMemory storms on Alias hole `0xA28E50000`.

### Smoke workflow
```bash
./scripts/android-deploy.sh nce-smoke 60   # build, install, force mem_config=3, launch Violet, triage
# Agent cue: "smoke done" → read tmp/nce-smoke-latest-triage.txt (don’t paste full logs)
```
- Title must already be in library once for auto-launch.
- Prefs: `NCE_SMOKE_MEM_CONFIG` default `3` = 12 GiB (needed to avoid `MapPhysicalMemory LimitReached`).

---

## 3. Milestone timeline (what actually worked)

| Ver | Outcome | Notes |
|-----|---------|--------|
| ≤0.7.31 | Fail early | Entropy Abort, Alias QM hole `0x37AEFFF000`, MapMemory ASLR Unmap loop, MPM InvalidMemRange, MapPhysical LimitReached |
| **0.7.32** | Strong PASS ~357 SVC | MapMemory kept; MPM ×6; no Break; then wait/tick stall |
| 0.7.36 | PASS ~388 SVC | Always boost Alias/Heap/Stack; MPM Alias relocate + GPR patch; reached CreateSharedMemory / sessions |
| 0.7.37 | FAIL guest fault | Overlap-only QM rewrite; OUTSIDE fault on low unmapped QM used as pointer |
| 0.7.38 | PASS but regression | Always rewrite all QM → only 1× MapPhysical, stalled early (rewriting `0x10FD2000` broke heap growth) |
| 0.7.39 | PASS | High-unmapped floor 1 GiB; reached sessions; access fault then hang (overlap soft-fault) |
| 0.7.40 | FAIL Break DC01 | Overlap-as-guest → DataAbort path; exposed `MapSharedMemory() = InvalidMemoryRegion` |
| **0.7.41** | **Best functional** | Peel MapSharedMemory addr; **970 SVCs**, shaders loaded, HID connected; then **QM storm** on `0x90BC28000` |
| 0.7.42 | FAIL | Tried skipping QM rewrite for mapped overlap → regress Break |
| 0.7.43 | = 0.7.41 policy | Reverted 0.7.42; flaky: sometimes ApplicationAborted during same storm |

### Best reference logs
- **Furthest:** `tmp/nce-smoke-20260904-201055.log` (+ triage) — 0.7.41 PASS, MapShared ok, shader+HID, QM storm  
- **Flaky abort:** `tmp/nce-smoke-20260904-201815.log` — MapShared ok then `ResultErrApplicationAborted (0x2a2)` / Break Result 0  
- **Earlier solid:** `tmp/nce-smoke-20260904-194909.log` — 0.7.36-ish, MPM relocate, no MapShared yet  

---

## 4. Current NCE policy (0.7.43) — implementer’s contract

File: `src/LibRyubing/Nce/NceCpuContext.cs`

1. **GetInfo boost** types 2, 4, 14, 20 → add `hostBase` into X1. Capture region bounds from **guest** values before boost.
2. **QueryMemory rewrite** of `MemoryInfo.Address` after SVC 0x6:
   - Rewrite if: boosted region (Alias/Heap/Stack), **or** mapped (`state != 0`), **or** unmapped with `addr >= 0x40000000` or in `[hostBase, asSize)`.
   - **Do not** rewrite low unmapped (`state==0`, addr &lt; 1 GiB, not boosted) — that stalled MapPhysical.
3. **Peel host→guest** before HLE for pointer SVCs: Map/UnmapMemory, MapPhysical, QueryMemory addr, Map/UnmapProcessMemory, MapProcessCodeMemory, **Map/UnmapSharedMemory (0x13/0x14)**, CreateTransferMemory (0x15).
4. **MapProcessMemory (0x74):** if peeled `dst` ∈ Alias → search free VA (prefer below Stack / above `0x10000000`), map there, **patch GPRs** matching old guest/host dst. Silent HLE-only relocate without GPR patch is wrong.
5. **Soft-fault:** if `in_as` but `fault_addr - hostBase >= asSize`, force DataAbort (`overlap-as-guest`) — prevents infinite soft-fault via `ToOffset` double-peel.
6. **`hr==0`:** resume (spurious halt); do not exit run loop.
7. DRAM: smoke forces `mem_config=3`.

**Do not** blindly boost Aslr GetInfo (12/13) — historically caused Alias-hole QM storms (`0xA28E50000` × 10⁵).

---

## 5. Active blocker (please focus here)

### Symptom
After successful `MapSharedMemory` (SVC `0x13` → `x0=0`):
- Guest issues **hundreds of `QueryMemory` on the same address**, commonly:
  - `addr=0x90BC28000 size=0x11D228000 state=0x5` (mapped, inside **Alias**, also ≥ `hostBase` → overlap)
- Little/no other SVC progress (occasionally one more MapPhysical).
- GPU thread may already have `Shader cache loaded` + `SetupNpad`.
- Capture then either idles until timeout (PASS) or hits `ServiceAm SetTerminateResult: 0x2a2 ApplicationAborted` and guest Break.

### Why this address is cursed
```
hostBase = 0x18B8D8000
asSize   = 0x5500000000
guest    = 0x90BC28000   # valid Alias VA AND numerically in host window
host form = guest + hostBase = 0xA98FA0000
```
Rewriting `MemoryInfo.Address` to host is required for native pointer use, but walkers that mix:
- guest cursor + `info.size`, vs
- rewritten `info.addr + info.size`, vs  
- peel treating a guest next-cursor like `0xA28E50000` as a host pointer  

…desync. Empirically: **always-rewrite** and **skip-rewrite-for-mapped-overlap** both hurt other stages; current hybrid still storms.

### Hypotheses worth testing (ranked)
1. **Walker / equality check** expects `info.addr == query_addr` in one address space; rewrite makes host ≠ guest cursor → retry forever.
2. **Peel of QueryMemory x1** mis-classifies overlap guest VAs or host `info.addr+size` results (especially when sum equals a “famous” guest hole like `0xA28E50000`).
3. **MapPhysical into Alias** at overlap VAs is itself wrong (guest should not MapPhysical Alias); peel/boost led virtmem there — relocate MapPhysical like MPM?
4. **Truncated AS (0x55 vs 0x80)** forces odd region packing; full fix is lower `hostBase` / larger contiguous reserve so `hostBase+0x80` fits in 39-bit user VA (today max ~`0x7e…` from this base).
5. **Storm is a wait** for another thread that is deadlocked (GPU/HLE) — less likely given pure QM with almost no WaitSynchronization in the storm window, but possible.
6. GPR-only patching after MPM relocate leaves **in-memory** pointers at old Alias VA → later faults/aborts (secondary).

### Failed / risky experiments (do not repeat casually)
| Experiment | Result |
|------------|--------|
| Boost Aslr GetInfo | Alias hole QM storm (`0xA28E50000`) |
| Always rewrite every QM Address | Stalls after first MapPhysical (breaks early ASLR walk at ~`0x10FD2000`) |
| Skip QM rewrite for mapped overlap | Regressed to Break / less progress |
| Deferred GetInfo (guest first, then host) | MapMemory to Alias, early stall, less MPM |
| HLE-only MPM relocate without GPR patch | Maps succeed at wrong VA; guest keeps Alias pointers |

---

## 6. SVC cheat sheet (Ryujinx numbering, relevant)

| # | Name | NCE note |
|---|------|----------|
| 0x4 | MapMemory | Peel dst/src; log ok/fail |
| 0x6 | QueryMemory | Peel addr; rewrite MemoryInfo.Address |
| 0x10 | GetCurrentProcessorNumber | *Not* CreateSharedMemory |
| 0x13 | **MapSharedMemory** | Peel **x1=address** (fixed 0.7.41) |
| 0x14 | UnmapSharedMemory | Peel address |
| 0x15 | CreateTransferMemory | Peel address |
| 0x1E | GetSystemTick | Often wait loops |
| 0x21 | WaitSynchronization | Post-bootstrap waits |
| 0x29 | GetInfo | Boost select address types |
| 0x2C | MapPhysicalMemory | Peel addr; need 12 GiB DRAM |
| 0x74/0x75 | Map/UnmapProcessMemory | Peel; Alias relocate+GPR |

Kernel `MapSharedMemory` rejects destinations inside Heap/Alias (`InvalidMemRange` / region errors). Host addresses that aren’t peeled look like invalid ranges → `InvalidMemoryRegion`.

---

## 7. Key files

```
src/LibRyubing/Nce/NceCpuContext.cs          # peel, rewrite, GetInfo, MPM relocate
src/LibRyubing/Nce/NceNative.cs
src/RyubingAndroid/app/src/main/cpp/nce/nce.cpp
src/RyubingAndroid/app/src/main/cpp/nce/signal_handlers.cpp
upstream/ryubing/src/Ryujinx.Cpu/Jit/MemoryManagerHostNoMirror.cs
upstream/ryubing/src/Ryujinx.Cpu/AddressSpace.cs   # TryCreateWithoutMirror shrink loop
upstream/ryubing/src/Ryujinx.HLE/HOS/Kernel/SupervisorCall/Syscall.cs  # 0x13 MapSharedMemory
patches/0015-nce-disambiguate-guest-va-vs-host-pointer.patch
patches/0017-hle-fill-getinfo-random-entropy-for-aslr.patch
patches/0018-hle-clamp-unmapped-querymemory-to-region-ends.patch
scripts/android-deploy.sh                     # nce-smoke / triage
tmp/nce-smoke-latest-triage.txt
research-material/eden/                       # reference
```

---

## 8. How to reproduce / iterate

```bash
cd /home/jochem/Ryubing-Android
./scripts/android-deploy.sh nce-smoke 60
# read:
tmp/nce-smoke-latest-triage.txt
# deep dive:
python3 -c "..."  # count SVC 0x6 repeats of 0x90BC28000, MapShared, Break, Shader cache
```

Bump `nce.cpp` version string on every behavioral change. Prefer small policy changes + smoke over large refactors.

Triage FAIL reasons can be blunt (`InvalidMemoryRegion`) even when the interesting signal is QM storm + ApplicationAborted — read the log, don’t trust the one-line verdict alone.

---

## 9. Questions for fresh eyes

1. What is the correct Eden/Skyline invariant for `MemoryInfo.Address` under direct-map NCE when guest VAs overlap the host mapping window?
2. Should **all** pointers (GetInfo Aslr + every QM Address) be host-absolute, with peel on every SVC addr — and if so, how do they avoid Alias-hole storms?
3. Is MapPhysical/MapShared into addresses ≥ `hostBase` (overlap Alias) a symptom that virtmemFindAslr is using mixed host/guest bases — and should destinations in Alias be relocated like MPM?
4. Can Android reserve a **lower** `hostBase` so `asSize=0x8000000000` fits under 39-bit VA, eliminating truncation?
5. Is the post-MapShared QM storm more likely a **pointer-space bug** or a **legitimate wait** that should be paired with GPU/display SVC progress we’re not logging (svc log budget)?
6. After MPM Alias relocate, should we also scan/patch **guest memory** for the old dst, not only GPRs?

---

## 10. Non-goals / constraints

- Don’t paste multi-MB logcats into chat; use triage + targeted greps.
- Don’t boost Aslr GetInfo without a storm mitigation plan.
- Don’t treat smoke PASS as “game works.”
- Prefer fixing peel/rewrite coherence over adding more silent HLE remaps.
- Wireless ADB drops (“network unreachable”) — reconnect before smoke.

---

## 11. Suggested prompt for the next AI

> You are reviewing an Android Switch emulator NCE bring-up. Read `docs/nce-violet-fresh-eyes-handoff.md` and skim `NceCpuContext.cs` peel/rewrite/GetInfo/MPM relocate plus `signal_handlers.cpp` overlap-as-guest. Primary bug: after MapSharedMemory succeeds, guest storms QueryMemory on overlap Alias VA `0x90BC28000`. Propose 2–3 concrete next code experiments (smallest first), what log signals would confirm each, and which prior experiments to avoid. Do not suggest boosting Aslr GetInfo unless you also solve the historical `0xA28E50000` storm.
