// SPDX-License-Identifier: MIT
//
// Host-side unit test for the NCE binary patcher.
// Links only the patcher (no signals / asm), so it runs on any host arch —
// oaknut emits AArch64 instruction bytes as data.

#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <span>
#include <vector>

#include "instructions.h"
#include "patcher.h"

using Ryubing::Nce::CodeSegment;
using Ryubing::Nce::EntryTrampolines;
using Ryubing::Nce::Exclusive;
using Ryubing::Nce::MRS;
using Ryubing::Nce::Patcher;
using Ryubing::Nce::PatchMode;
using Ryubing::Nce::SVC;

namespace {

// A64 encodings used as fixtures.
constexpr uint32_t NOP = 0xD503201F;
constexpr uint32_t RET = 0xD65F03C0;
// SVC #0x6B (svcExitProcess)
constexpr uint32_t SVC_EXIT = 0xD4000D61;
// MRS X0, TPIDR_EL0  (op0=3 op1=3 CRn=13 CRm=0 op2=2 Rt=0)
constexpr uint32_t MRS_TPIDR = 0xD53BD040;
// LDXR X0, [X0] / LDAR X0, [X0] — encodings from instructions.h static_asserts
constexpr uint32_t LDXR_X0_X0 = 0xC85F7C00;
constexpr uint32_t LDAR_X0_X0 = 0xC85FFC00;
// Exclusive store form from instructions.h static_assert (AsOrdered sets o0)
constexpr uint32_t STXR_FORM = 0xC8200440;
constexpr uint32_t STXR_ORDERED = 0xC8208440;
// MRS X0, CNTPCT_EL0
constexpr uint32_t MRS_CNTPCT_X0 = 0xD53BE020;

void WriteWord(std::vector<uint8_t>& image, size_t offset, uint32_t word) {
    assert(offset + 4 <= image.size());
    std::memcpy(image.data() + offset, &word, sizeof(word));
}

uint32_t ReadWord(const std::vector<uint8_t>& image, size_t offset) {
    uint32_t word = 0;
    std::memcpy(&word, image.data() + offset, sizeof(word));
    return word;
}

bool IsBranch(uint32_t inst) {
    // Unconditional B: 0b000101xxxxxxxx...
    return (inst & 0xFC000000u) == 0x14000000u;
}

int Fail(const char* msg) {
    std::fprintf(stderr, "FAIL: %s\n", msg);
    return 1;
}

} // namespace

// Patches a fixture module loaded at `load_base` and checks the rewrite plus
// the trampoline registry. Run for several bases: NCE places modules inside an
// identity-mapped window whose base is >= 2^36 (see IdentityWindowPlacement),
// so every address the patcher emits must be correct for high absolute bases.
int RunPatchCase(uint64_t load_base) {
    // Build a tiny 4KiB "module" with code at offset 0.
    // Layout mirrors a minimal NSO .text: skip first 0x24 bytes (NSO header
    // region the patcher ignores), then place fixtures.
    constexpr size_t ImageSize = 4096;
    constexpr size_t TextOff = 0;
    constexpr size_t FixtureBase = 0x24;

    std::vector<uint8_t> image(ImageSize, 0);
    for (size_t i = 0; i < ImageSize; i += 4) {
        WriteWord(image, i, NOP);
    }

    WriteWord(image, FixtureBase + 0, SVC_EXIT);
    WriteWord(image, FixtureBase + 4, MRS_TPIDR);
    WriteWord(image, FixtureBase + 8, LDXR_X0_X0);
    WriteWord(image, FixtureBase + 12, STXR_FORM);
    WriteWord(image, FixtureBase + 16, MRS_CNTPCT_X0);
    WriteWord(image, FixtureBase + 20, RET);

    // Sanity: bitfield decoders accept the fixtures.
    if (!SVC{SVC_EXIT}.Verify()) {
        return Fail("SVC fixture does not verify");
    }
    if (!MRS{MRS_TPIDR}.Verify()) {
        return Fail("MRS TPIDR fixture does not verify");
    }
    if (!MRS{MRS_CNTPCT_X0}.Verify() ||
        MRS{MRS_CNTPCT_X0}.GetSystemReg() != Ryubing::Nce::CntpctEl0) {
        return Fail("MRS CNTPCT fixture does not verify");
    }
    if (!Exclusive{LDXR_X0_X0}.Verify()) {
        return Fail("LDXR fixture does not verify");
    }
    if (Exclusive{LDXR_X0_X0}.AsOrdered() != LDAR_X0_X0) {
        return Fail("LDXR AsOrdered() != LDAR");
    }
    if (!Exclusive{STXR_FORM}.Verify() ||
        Exclusive{STXR_FORM}.AsOrdered() != STXR_ORDERED) {
        return Fail("STXR AsOrdered mismatch");
    }

    CodeSegment code{};
    code.offset = TextOff;
    code.addr = load_base;
    code.size = static_cast<uint32_t>(ImageSize);

    // Count patchable instructions before rewriting.
    uint32_t svc_before = 0, mrs_before = 0, excl_before = 0;
    {
        const auto words = std::span<const uint32_t>{
            reinterpret_cast<const uint32_t*>(image.data() + FixtureBase),
            (ImageSize - FixtureBase) / 4};
        for (uint32_t w : words) {
            if (SVC{w}.Verify()) {
                svc_before++;
            } else if (MRS{w}.Verify()) {
                mrs_before++;
            } else if (Exclusive{w}.Verify()) {
                excl_before++;
            }
        }
    }
    if (svc_before < 1 || mrs_before < 1 || excl_before < 1) {
        return Fail("fixture instruction counts too low before patch");
    }

    Patcher patcher{};
    if (!patcher.PatchText(image, code)) {
        return Fail("PatchText returned false");
    }

    EntryTrampolines trampolines;
    if (!patcher.RelocateAndCopy(code.addr, code, image, &trampolines)) {
        return Fail("RelocateAndCopy returned false");
    }

    if (patcher.GetPatchMode() == PatchMode::None) {
        return Fail("expected a non-None patch mode after patching SVC/MRS");
    }

    if (image.size() <= ImageSize) {
        return Fail("patched image did not grow (no patch section appended?)");
    }

    // After PostData patching, original text stays at the same offsets.
    // SVC site must become a branch into the patch section.
    const uint32_t svc_site = ReadWord(image, FixtureBase + 0);
    if (!IsBranch(svc_site)) {
        std::fprintf(stderr, "FAIL: SVC site not branched (got 0x%08X)\n", svc_site);
        return 1;
    }

    // Exclusive must be rewritten in-place to the ordered form.
    const uint32_t excl_site = ReadWord(image, FixtureBase + 8);
    if (excl_site != LDAR_X0_X0) {
        std::fprintf(stderr, "FAIL: LDXR not converted to LDAR (got 0x%08X)\n", excl_site);
        return 1;
    }
    const uint32_t stxr_site = ReadWord(image, FixtureBase + 12);
    if (stxr_site != STXR_ORDERED) {
        std::fprintf(stderr, "FAIL: STXR not ordered (got 0x%08X)\n", stxr_site);
        return 1;
    }

    // MRS TPIDR / CNTPCT must no longer be the original system-reg reads.
    const uint32_t mrs_site = ReadWord(image, FixtureBase + 4);
    if (MRS{mrs_site}.Verify() && mrs_site == MRS_TPIDR) {
        return Fail("MRS TPIDR site was left unchanged");
    }
    const uint32_t cntpct_site = ReadWord(image, FixtureBase + 16);
    if (MRS{cntpct_site}.Verify() && cntpct_site == MRS_CNTPCT_X0) {
        return Fail("MRS CNTPCT site was left unchanged");
    }

    // At least one post-SVC trampoline for the instruction after SVC.
    if (trampolines.empty()) {
        return Fail("no entry trampolines registered");
    }

    // Trampoline keys are absolute guest addresses (== host pointers under the
    // identity-mapped AS) of the instruction after each SVC; targets must land
    // inside the patched image at the same base. Both must survive a high base.
    const uint64_t expected_key = load_base + FixtureBase + 4;
    bool found_key = false;
    for (const auto& [guest_addr, patch_addr] : trampolines) {
        if (guest_addr == expected_key) {
            found_key = true;
        }
        if (guest_addr < load_base || guest_addr >= load_base + image.size()) {
            std::fprintf(stderr, "FAIL: trampoline key 0x%llx outside module [0x%llx, 0x%llx)\n",
                         (unsigned long long)guest_addr, (unsigned long long)load_base,
                         (unsigned long long)(load_base + image.size()));
            return 1;
        }
        if (patch_addr < load_base || patch_addr >= load_base + image.size()) {
            std::fprintf(stderr, "FAIL: trampoline target 0x%llx outside module [0x%llx, 0x%llx)\n",
                         (unsigned long long)patch_addr, (unsigned long long)load_base,
                         (unsigned long long)(load_base + image.size()));
            return 1;
        }
        if ((patch_addr & 3) != 0) {
            return Fail("trampoline target not 4-byte aligned");
        }
    }
    if (!found_key) {
        std::fprintf(stderr, "FAIL: no trampoline keyed at post-SVC address 0x%llx\n",
                     (unsigned long long)expected_key);
        return 1;
    }

    std::printf("ok: base=0x%llx mode=%u image %zu -> %zu, trampolines=%zu, "
                "pre_svc=%u pre_mrs=%u pre_excl=%u\n",
                (unsigned long long)load_base,
                static_cast<unsigned>(patcher.GetPatchMode()), ImageSize, image.size(),
                trampolines.size(), svc_before, mrs_before, excl_before);
    return 0;
}

int main() {
    // Classic Switch main NSO VA (39-bit layout without an identity window).
    if (int rc = RunPatchCase(0x7100000000ull); rc != 0) {
        return rc;
    }
    // Identity-mapped window bases: code = window + 0x8000000 with window in
    // [2^36, 2^39 - 2^38). Lowest, a mid value, and the highest possible.
    if (int rc = RunPatchCase(0x1000000000ull + 0x8000000ull); rc != 0) {
        return rc;
    }
    if (int rc = RunPatchCase(0x3FA0000000ull + 0x8000000ull); rc != 0) {
        return rc;
    }
    if (int rc = RunPatchCase(0x7FFFE00000ull - 0x4000000000ull + 0x8000000ull); rc != 0) {
        return rc;
    }
    return 0;
}
