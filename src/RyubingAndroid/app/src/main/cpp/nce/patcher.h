// SPDX-License-Identifier: MIT
//
// Binary patcher for NCE: rewrites guest ARM64 code so it can run natively
// on the host CPU.
//
// Ported from eden's src/core/arm/nce/patcher.h/.cpp, adapted to stand alone
// (no kernel/code-set dependencies): the caller supplies the raw program
// image and code-segment layout, and receives a patched image plus a patch
// section that must be mapped within branch range of the guest code.

#pragma once

#include <cstddef>
#include <cstdint>
#include <span>
#include <unordered_map>
#include <vector>

#include <oaknut/code_block.hpp>
#include <oaknut/oaknut.hpp>

namespace Ryubing::Nce {

using u8 = std::uint8_t;
using u32 = std::uint32_t;
using u64 = std::uint64_t;

// Switch guest page size.
inline constexpr size_t NcePageSize = 4096;

// Where the patch section is placed relative to the module text.
// A64 B/BL reach is +/-128 MiB; large modules need both sides.
enum class PatchMode : u32 {
    None,
    PreText,  ///< Patch section is inserted before .text
    PostData, ///< Patch section is inserted after .data
    Split,    ///< Patch sections on both sides of the module
};

// Code segment descriptor (replaces eden's Kernel::CodeSet::Segment).
struct CodeSegment {
    size_t offset = 0; ///< Byte offset within the program image
    u64 addr = 0;      ///< Guest virtual address where the segment maps
    u32 size = 0;      ///< Segment size in bytes
};

// Map from guest code address -> patch entry (trampoline) address.
using EntryTrampolines = std::unordered_map<u64, u64>;

class Patcher {
public:
    Patcher();
    ~Patcher();

    /// Scans .text and queues patches (SVC, MRS/MSR, exclusives).
    /// Does not modify the image; call RelocateAndCopy afterwards.
    bool PatchText(std::span<const u8> program_image, const CodeSegment& code);

    /// Applies queued patches: rewrites branches in the image, appends (or
    /// prepends) the patch section, and fills out_trampolines with entry
    /// points used to re-enter the guest after an SVC.
    /// Returns true when this was the last module (image now final).
    bool RelocateAndCopy(uint64_t load_base, const CodeSegment& code,
                         std::vector<u8>& program_image, EntryTrampolines* out_trampolines);

    size_t GetSectionSize() const noexcept;
    size_t GetPreSectionSize() const noexcept;

    [[nodiscard]] PatchMode GetPatchMode() const noexcept { return mode; }

private:
    using ModuleDestLabel = uintptr_t;

    struct Trampoline {
        ptrdiff_t patch_offset;
        uintptr_t module_offset;
    };

    struct Relocation {
        ptrdiff_t patch_offset;
        uintptr_t module_offset;
    };

    struct ModulePatch {
        std::vector<Trampoline> m_trampolines;
        std::vector<Trampoline> m_trampolines_pre;
        std::vector<Relocation> m_branch_to_patch_relocations{};
        std::vector<Relocation> m_branch_to_pre_patch_relocations{};
        std::vector<Relocation> m_branch_to_module_relocations{};
        std::vector<Relocation> m_branch_to_module_relocations_pre{};
        std::vector<Relocation> m_write_module_pc_relocations{};
        std::vector<Relocation> m_write_module_pc_relocations_pre{};
        std::vector<size_t> m_exclusives{};
    };

    // Code emitters (implementations in patcher.cpp).
    void WriteLoadContext(oaknut::VectorCodeGenerator& code);
    void WriteSaveContext(oaknut::VectorCodeGenerator& code);
    void LockContext(oaknut::VectorCodeGenerator& code);
    void UnlockContext(oaknut::VectorCodeGenerator& code);
    void WriteSvcTrampoline(ModuleDestLabel module_dest, u32 svc_id,
                            oaknut::VectorCodeGenerator& code,
                            oaknut::Label& save_ctx, oaknut::Label& load_ctx);
    void WriteMrsHandler(ModuleDestLabel module_dest, oaknut::XReg dest_reg,
                         oaknut::SystemReg src_reg, oaknut::VectorCodeGenerator& code);
    void WriteMsrHandler(ModuleDestLabel module_dest, oaknut::XReg src_reg,
                         oaknut::VectorCodeGenerator& code);
    void WriteCntfrqHandler(ModuleDestLabel module_dest, oaknut::XReg dest_reg,
                            oaknut::VectorCodeGenerator& code);
    void WriteCntpctHandler(ModuleDestLabel module_dest, oaknut::XReg dest_reg,
                            oaknut::VectorCodeGenerator& code);

    // Relocation queue helpers.
    void BranchToPatch(uintptr_t module_dest);
    void BranchToPatchPre(uintptr_t module_dest);
    void BranchToModule(uintptr_t module_dest);
    void BranchToModulePre(uintptr_t module_dest);
    void WriteModulePc(uintptr_t module_dest);
    void WriteModulePcPre(uintptr_t module_dest);

    oaknut::VectorCodeGenerator c;     ///< Post-text patch code
    oaknut::VectorCodeGenerator c_pre; ///< Pre-text patch code (split mode)
    oaknut::Label m_save_context{};
    oaknut::Label m_load_context{};
    oaknut::Label m_save_context_pre{};
    oaknut::Label m_load_context_pre{};
    PatchMode mode{PatchMode::None};
    size_t total_program_size{};
    size_t m_relocate_module_index{};
    std::vector<ModulePatch> modules;
    ModulePatch* curr_patch{};

    // Raw instruction buffers backing the code generators.
    std::vector<u32> m_patch_instructions{};
    std::vector<u32> m_patch_instructions_pre{};
};

} // namespace Ryubing::Nce
