# Stability baseline & compat tracking

How we keep the port stable as upstream Ryubing moves and across the fragmented Android
device landscape.

## Default configuration (mobile-safe)

These defaults are chosen for correctness/stability over peak speed and are enforced in
code, not just docs:

| Setting | Default | Where | Rationale |
|---------|---------|-------|-----------|
| NCE / Hypervisor | **off** (`useHypervisor: false`) | `AndroidHost.BuildConfiguration` | NCE is risky/unavailable on most Android kernels; use the ARMeilleure JIT path. |
| PPTC | **off** (`EnablePtc`) | `EmulatorConfig` / `EmulatorSettings` | Startup + memory cost and correctness risk on mobile; opt-in. |
| Memory manager | HostMappedUnsafe | `EmulatorSettings.MemoryManagerMode` | Fastest; downgrade to HostMapped if a title misbehaves. |
| DRAM | 4 GiB | `MemoryConfiguration4GiB` | Retail Switch default; avoids overcommprocessing on 6–8 GB phones. |
| Backend threading | Auto | `EmulatorSettings.BackendThreading` | Lets the Vulkan backend decide per device. |
| Shader cache | on | `GraphicsConfig.EnableShaderCache` | Big win for repeat launches. |

Users can override all of these in the Settings screen; the defaults are what a fresh
install and CI smoke tests use.

## Device test matrix

Target arm64-v8a, Android 11+ (minSdk 30), Vulkan 1.1+. Cover the three GPU families:

| Tier | Example SoC | GPU | Notes |
|------|-------------|-----|-------|
| A (primary) | Snapdragon 8 Gen 2/3 | Adreno 740/750 | adrenotools + Turnip driver injection supported |
| B | Dimensity 9000/9200 | Mali-G710/G715 | Validate stock driver path (no adrenotools) |
| C | Exynos 2200/2400 | Xclipse 920/940 | RDNA-based; watch for surface/format quirks |

For each tier, the manual smoke pass is: boot the app → add a homebrew/test title → reach
in-game render → verify audio + touch input → clean exit.

## Compat tracking (`compat/pins.json`)

Every app release records the exact upstream commit it was built against, plus which smoke
tests passed and any known breakages:

```jsonc
{
  "app_version": "0.1.0",
  "upstream_commit": "…",
  "upstream_tag": "Canary-1.3.333-…",
  "patches_applied": 0,
  "smoke_tests_passed": ["boot", "homebrew-render", "audio", "touch-input"],
  "known_breakages": [
    // { "since_commit": "…", "area": "gpu", "summary": "…", "workaround": "…" }
  ],
  "notes": [
    // "0001: …",
    // per-patch or release notes
  ]
}
```

- `scripts/pin-upstream.sh` refreshes commit/tag/patch-count after a sync.
- The weekly `sync-upstream-canary` workflow re-applies patches and smoke-builds the core;
  a failure is the cue to add a `known_breakages` entry (and, if warranted, a patch
  candidate per `docs/patch-candidates.md`).

## Smoke test definition

The automated CI smoke test currently verifies that `LibRyubing` **compiles against the
pinned upstream** (host RID) — the cheapest signal that the adapter still matches upstream
APIs. On-device functional smoke tests (boot/render/audio/input) are run manually against
the device matrix until an instrumented emulator harness exists.
