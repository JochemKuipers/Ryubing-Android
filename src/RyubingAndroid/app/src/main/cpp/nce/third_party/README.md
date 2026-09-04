# NCE third_party — dynarmic A64 decoder

Header-only vendoring of the **dynarmic** A64 frontend decoder (and minimal helpers) for Ryubing-Android NCE alignment interpretation.

## Provenance

- Upstream: [dynarmic](https://github.com/merryhime/dynarmic) / merryhime
- Licenses: **0BSD** / **MIT** (see SPDX headers on individual files)
- Source snapshot: Eden’s tree under `research-material/eden/src/dynarmic/src/`

Only the frontend decoder path is vendored (`frontend/A64/decoder`, matcher/detail, `imm.h`, `a64_types.h`, `ir/cond.h`, `mcl/bit.hpp`, `common/math_util.h`). Kernel/memory/logging from Eden are **not** included.

## Local shims

| Path | Role |
|------|------|
| `include/common/common_types.h` | Minimal `u8`/`u32`/… typedefs |
| `include/common/assert.h` | `ASSERT` / `DEBUG_ASSERT` → `<cassert>` |

Add `nce/third_party/include` to the include path to use:

```cpp
#include <dynarmic/frontend/A64/decoder/a64.h>
```
