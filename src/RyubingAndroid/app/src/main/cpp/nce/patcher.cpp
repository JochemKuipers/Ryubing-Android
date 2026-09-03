// SPDX-License-Identifier: MIT
//
// NCE binary patcher implementation. Ported from eden's patcher.cpp.

#include <cassert>
#include <cstring>
#include <bit>

#include <android/log.h>

#include "guest_context.h"
#include "instructions.h"
#include "patcher.h"

#define LOG_TAG "RyubingNCE"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace Ryubing::Nce {

using namespace oaknut::util;
using NativeExecutionParameters = Ryubing::Nce::NativeExecutionParameters;

constexpr size_t MaxRelativeBranch = 128 * 1024 * 1024; // A64 B/BL range
constexpr u32 ModuleCodeIndex = 0x24 / sizeof(u32);     // Skip NSO header

inline size_t AlignUp(size_t v, size_t a) { return (v + a - 1) & ~(a - 1); }

// Guest CNTFRQ the Switch exposes (19.2 MHz).
constexpr u64 GuestCntfrq = 19200000;

Patcher::Patcher() : c(m_patch_instructions), c_pre(m_patch_instructions_pre) {
    c.dw(0);      // First word: branch to module (relocated later)
    c_pre.dw(0);

    c.l(m_save_context);            // Save context helper
    WriteSaveContext(c);
    c_pre.l(m_save_context_pre);
    WriteSaveContext(c_pre);

    c.l(m_load_context);            // Load context helper
    WriteLoadContext(c);
    c_pre.l(m_load_context_pre);
    WriteLoadContext(c_pre);
}

Patcher::~Patcher() = default;

bool Patcher::PatchText(std::span<const u8> program_image, const CodeSegment& code) {
    const size_t image_size = program_image.size();

    // Split mode for large modules (>128MB A64 branch reach).
    bool use_split = false;
    if (image_size > MaxRelativeBranch) {
        if (total_program_size > 0) {
            return false;
        }
        use_split = true;
    } else if (total_program_size + image_size > MaxRelativeBranch && total_program_size > 0) {
        return false;
    }

    modules.emplace_back();
    curr_patch = &modules.back();

    if (use_split) {
        curr_patch->m_branch_to_module_relocations_pre.push_back({0, 0});
    } else {
        curr_patch->m_branch_to_module_relocations.push_back({0, 0});
    }

    const auto text = std::span{program_image}.subspan(code.offset, code.size);
    const auto text_words =
        std::span<const u32>{reinterpret_cast<const u32*>(text.data()), text.size() / sizeof(u32)};

    for (u32 i = ModuleCodeIndex; i < static_cast<u32>(text_words.size()); i++) {
        const u32 inst = text_words[i];

        const auto AddRelocations = [&](bool& pre_buffer) {
            const uintptr_t this_offset = i * sizeof(u32);
            const uintptr_t next_offset = this_offset + sizeof(u32);
            pre_buffer = use_split && (this_offset < MaxRelativeBranch);
            if (pre_buffer) {
                this->BranchToPatchPre(this_offset);
            } else {
                this->BranchToPatch(this_offset);
            }
            return next_offset;
        };

        // SVC
        if (auto svc = SVC{inst}; svc.Verify()) {
            bool pre_buffer = false;
            auto ret = AddRelocations(pre_buffer);
            if (pre_buffer) {
                WriteSvcTrampoline(ret, svc.GetValue(), c_pre, m_save_context_pre, m_load_context_pre);
            } else {
                WriteSvcTrampoline(ret, svc.GetValue(), c, m_save_context, m_load_context);
            }
            continue;
        }

        // MRS Xn, TPIDR_EL0 / TPIDRRO_EL0
        if (auto mrs = MRS{inst};
            mrs.Verify() && (mrs.GetSystemReg() == TpidrroEl0 || mrs.GetSystemReg() == TpidrEl0)) {
            const auto src_reg = mrs.GetSystemReg() == TpidrroEl0
                                     ? oaknut::SystemReg::TPIDRRO_EL0
                                     : oaknut::SystemReg::TPIDR_EL0;
            const auto dest_reg = oaknut::XReg{static_cast<int>(mrs.GetRt())};
            bool pre_buffer = false;
            auto ret = AddRelocations(pre_buffer);
            if (pre_buffer) {
                WriteMrsHandler(ret, dest_reg, src_reg, c_pre);
            } else {
                WriteMrsHandler(ret, dest_reg, src_reg, c);
            }
            continue;
        }

        // MRS Xn, CNTPCT_EL0
        if (auto mrs = MRS{inst}; mrs.Verify() && mrs.GetSystemReg() == CntpctEl0) {
            bool pre_buffer = false;
            auto ret = AddRelocations(pre_buffer);
            if (pre_buffer) {
                WriteCntpctHandler(ret, oaknut::XReg{static_cast<int>(mrs.GetRt())}, c_pre);
            } else {
                WriteCntpctHandler(ret, oaknut::XReg{static_cast<int>(mrs.GetRt())}, c);
            }
            continue;
        }

        // MRS Xn, CNTFRQ_EL0
        if (auto mrs = MRS{inst}; mrs.Verify() && mrs.GetSystemReg() == CntfrqEl0) {
            bool pre_buffer = false;
            auto ret = AddRelocations(pre_buffer);
            if (pre_buffer) {
                WriteCntfrqHandler(ret, oaknut::XReg{static_cast<int>(mrs.GetRt())}, c_pre);
            } else {
                WriteCntfrqHandler(ret, oaknut::XReg{static_cast<int>(mrs.GetRt())}, c);
            }
            continue;
        }

        // MSR TPIDR_EL0, Xn
        if (auto msr = MSR{inst}; msr.Verify() && msr.GetSystemReg() == TpidrEl0) {
            bool pre_buffer = false;
            auto ret = AddRelocations(pre_buffer);
            if (pre_buffer) {
                WriteMsrHandler(ret, oaknut::XReg{static_cast<int>(msr.GetRt())}, c_pre);
            } else {
                WriteMsrHandler(ret, oaknut::XReg{static_cast<int>(msr.GetRt())}, c);
            }
            continue;
        }

        // Exclusives: record for ordered conversion in RelocateAndCopy.
        if (auto exclusive = Exclusive{inst}; exclusive.Verify()) {
            curr_patch->m_exclusives.push_back(i);
        }
    }

    total_program_size += image_size;
    if (use_split) {
        this->mode = PatchMode::Split;
    } else {
        this->mode = image_size > MaxRelativeBranch ? PatchMode::PreText : PatchMode::PostData;
    }
    return true;
}


bool Patcher::RelocateAndCopy(uint64_t load_base, const CodeSegment& code,
                              std::vector<u8>& program_image, EntryTrampolines* out_trampolines) {
    const size_t patch_size = GetSectionSize();
    const size_t pre_patch_size = GetPreSectionSize();
    const size_t image_size =
        (mode == PatchMode::Split) ? program_image.size() - pre_patch_size : program_image.size();

    const auto text = std::span{program_image}.subspan(code.offset, code.size);
    const auto text_words =
        std::span<u32>{reinterpret_cast<u32*>(text.data()), text.size() / sizeof(u32)};

    const auto ApplyBranchToPatchRelocation = [&](u32* target, const Relocation& rel) {
        oaknut::CodeGenerator rc{target};
        if (mode == PatchMode::PreText) {
            rc.B(rel.patch_offset - static_cast<ptrdiff_t>(patch_size) - rel.module_offset);
        } else {
            rc.B(static_cast<ptrdiff_t>(total_program_size) - rel.module_offset + rel.patch_offset);
        }
    };

    const auto ApplyBranchToPatchRelocationPre = [&](u32* target, const Relocation& rel) {
        oaknut::CodeGenerator rc{target};
        rc.B(static_cast<ptrdiff_t>(rel.patch_offset) - static_cast<ptrdiff_t>(pre_patch_size) -
             rel.module_offset);
    };

    const auto ApplyBranchToPatchRelocationPostSplit = [&](u32* target, const Relocation& rel) {
        oaknut::CodeGenerator rc{target};
        rc.B(static_cast<ptrdiff_t>(image_size) + rel.patch_offset - rel.module_offset);
    };

    const auto ApplyBranchToModuleRelocation = [&](u32* target, const Relocation& rel) {
        oaknut::CodeGenerator rc{target};
        if (mode == PatchMode::PreText) {
            rc.B(static_cast<ptrdiff_t>(patch_size) - rel.patch_offset + rel.module_offset);
        } else {
            rc.B(rel.module_offset - static_cast<ptrdiff_t>(total_program_size) - rel.patch_offset);
        }
    };

    const auto ApplyBranchToModuleRelocationPre = [&](u32* target, const Relocation& rel) {
        oaknut::CodeGenerator rc{target};
        rc.B(static_cast<ptrdiff_t>(pre_patch_size) + rel.module_offset - rel.patch_offset);
    };

    const auto ApplyBranchToModuleRelocationPostSplit = [&](u32* target, const Relocation& rel) {
        oaknut::CodeGenerator rc{target};
        rc.B(rel.module_offset - static_cast<ptrdiff_t>(image_size) - rel.patch_offset);
    };

    const auto RebasePatch = [&](ptrdiff_t patch_offset) -> uintptr_t {
        if (mode == PatchMode::PreText) {
            return load_base + patch_offset;
        } else {
            return load_base + total_program_size + patch_offset;
        }
    };

    const auto RebasePc = [&](uintptr_t module_offset) -> uintptr_t {
        if (mode == PatchMode::PreText) {
            return load_base + patch_size + module_offset;
        }
        if (mode == PatchMode::Split) {
            return load_base + pre_patch_size + module_offset;
        }
        return load_base + module_offset;
    };


    // We are now ready to relocate!
    auto& patch = modules[m_relocate_module_index++];

    if (mode == PatchMode::Split) {
        for (const Relocation& rel : patch.m_branch_to_pre_patch_relocations) {
            ApplyBranchToPatchRelocationPre(text_words.data() + rel.module_offset / sizeof(u32), rel);
        }
        for (const Relocation& rel : patch.m_branch_to_patch_relocations) {
            ApplyBranchToPatchRelocationPostSplit(text_words.data() + rel.module_offset / sizeof(u32), rel);
        }
        for (const Relocation& rel : patch.m_branch_to_module_relocations_pre) {
            ApplyBranchToModuleRelocationPre(
                m_patch_instructions_pre.data() + rel.patch_offset / sizeof(u32), rel);
        }
        for (const Relocation& rel : patch.m_branch_to_module_relocations) {
            ApplyBranchToModuleRelocationPostSplit(
                m_patch_instructions.data() + rel.patch_offset / sizeof(u32), rel);
        }
        for (const Relocation& rel : patch.m_write_module_pc_relocations_pre) {
            oaknut::CodeGenerator rc{m_patch_instructions_pre.data() + rel.patch_offset / sizeof(u32)};
            rc.dx(RebasePc(rel.module_offset));
        }
        for (const Relocation& rel : patch.m_write_module_pc_relocations) {
            oaknut::CodeGenerator rc{m_patch_instructions.data() + rel.patch_offset / sizeof(u32)};
            rc.dx(RebasePc(rel.module_offset));
        }
        for (const Trampoline& rel : patch.m_trampolines_pre) {
            (*out_trampolines)[RebasePc(rel.module_offset)] = load_base + rel.patch_offset;
        }
        for (const Trampoline& rel : patch.m_trampolines) {
            (*out_trampolines)[RebasePc(rel.module_offset)] =
                load_base + pre_patch_size + image_size + rel.patch_offset;
        }
    } else {
        for (const Relocation& rel : patch.m_branch_to_patch_relocations) {
            ApplyBranchToPatchRelocation(text_words.data() + rel.module_offset / sizeof(u32), rel);
        }
        for (const Relocation& rel : patch.m_branch_to_module_relocations) {
            ApplyBranchToModuleRelocation(m_patch_instructions.data() + rel.patch_offset / sizeof(u32), rel);
        }
        for (const Relocation& rel : patch.m_write_module_pc_relocations) {
            oaknut::CodeGenerator rc{m_patch_instructions.data() + rel.patch_offset / sizeof(u32)};
            rc.dx(RebasePc(rel.module_offset));
        }
    }

    if (mode != PatchMode::Split) {
        for (const Trampoline& rel : patch.m_trampolines) {
            (*out_trampolines)[RebasePc(rel.module_offset)] = RebasePatch(rel.patch_offset);
        }
    }

    // Convert exclusives to ordered (LDAR/STLR) semantics.
    for (const size_t i : patch.m_exclusives) {
        auto exclusive = Exclusive{text_words[i]};
        text_words[i] = exclusive.AsOrdered();
    }

    total_program_size -= image_size;

    // Only copy to the program image of the last module.
    if (m_relocate_module_index == modules.size()) {
        if (this->mode == PatchMode::PreText) {
            assert(image_size == total_program_size);
            std::memcpy(program_image.data(), m_patch_instructions.data(),
                        m_patch_instructions.size() * sizeof(u32));
        } else if (this->mode == PatchMode::Split) {
            const size_t current_size = program_image.size();
            program_image.resize(current_size + patch_size);
            std::memcpy(program_image.data(), m_patch_instructions_pre.data(),
                        m_patch_instructions_pre.size() * sizeof(u32));
            std::memcpy(program_image.data() + current_size, m_patch_instructions.data(),
                        m_patch_instructions.size() * sizeof(u32));
        } else {
            program_image.resize(image_size + patch_size);
            std::memcpy(program_image.data() + image_size, m_patch_instructions.data(),
                        m_patch_instructions.size() * sizeof(u32));
        }
        return true;
    }

    return false;
}

size_t Patcher::GetSectionSize() const noexcept {
    return AlignUp(m_patch_instructions.size() * sizeof(u32), NcePageSize);
}

size_t Patcher::GetPreSectionSize() const noexcept {
    return AlignUp(m_patch_instructions_pre.size() * sizeof(u32), NcePageSize);
}


void Patcher::WriteLoadContext(oaknut::VectorCodeGenerator& cg) {
    // This function was called, which modifies X30, so use that as a scratch
    // register. SP contains the guest X30, so save our return X30 to SP + 8,
    // since we have allocated 16 bytes of stack.
    cg.STR(X30, SP, 8);
    cg.MRS(X30, oaknut::SystemReg::TPIDR_EL0);
    cg.LDR(X30, X30, offsetof(NativeExecutionParameters, native_context));

    // Load system registers.
    cg.LDR(W0, X30, offsetof(GuestContext, fpsr));
    cg.MSR(oaknut::SystemReg::FPSR, X0);
    cg.LDR(W0, X30, offsetof(GuestContext, fpcr));
    cg.MSR(oaknut::SystemReg::FPCR, X0);
    cg.LDR(W0, X30, offsetof(GuestContext, nzcv));
    cg.MSR(oaknut::SystemReg::NZCV, X0);

    // Load all vector registers.
    static constexpr size_t VEC_OFF = offsetof(GuestContext, vector_registers);
    for (int i = 0; i <= 30; i += 2) {
        cg.LDP(oaknut::QReg{i}, oaknut::QReg{i + 1}, X30, VEC_OFF + 16 * i);
    }

    // Load all general-purpose registers except X30.
    for (int i = 0; i <= 28; i += 2) {
        cg.LDP(oaknut::XReg{i}, oaknut::XReg{i + 1}, X30, 8 * i);
    }

    // Reload our return X30 from the stack and return.
    // The patch code will reload the guest X30 for us.
    cg.LDR(X30, SP, 8);
    cg.RET();
}

void Patcher::WriteSaveContext(oaknut::VectorCodeGenerator& cg) {
    // This function was called, which modifies X30, so use that as a scratch
    // register. SP contains the guest X30, so save our X30 to SP + 8.
    cg.STR(X30, SP, 8);
    cg.MRS(X30, oaknut::SystemReg::TPIDR_EL0);
    cg.LDR(X30, X30, offsetof(NativeExecutionParameters, native_context));

    // Store all general-purpose registers except X30.
    for (int i = 0; i <= 28; i += 2) {
        cg.STP(oaknut::XReg{i}, oaknut::XReg{i + 1}, X30, 8 * i);
    }

    // Store all vector registers.
    static constexpr size_t VEC_OFF = offsetof(GuestContext, vector_registers);
    for (int i = 0; i <= 30; i += 2) {
        cg.STP(oaknut::QReg{i}, oaknut::QReg{i + 1}, X30, VEC_OFF + 16 * i);
    }

    // Store guest system registers, X30 and SP, using X0 as scratch.
    cg.STR(X0, SP, PRE_INDEXED, -16);
    cg.LDR(X0, SP, 16);
    cg.STR(X0, X30, 8 * 30);
    cg.ADD(X0, SP, 32);
    cg.STR(X0, X30, offsetof(GuestContext, sp));
    cg.MRS(X0, oaknut::SystemReg::FPSR);
    cg.STR(W0, X30, offsetof(GuestContext, fpsr));
    cg.MRS(X0, oaknut::SystemReg::FPCR);
    cg.STR(W0, X30, offsetof(GuestContext, fpcr));
    cg.MRS(X0, oaknut::SystemReg::NZCV);
    cg.STR(W0, X30, offsetof(GuestContext, nzcv));
    cg.LDR(X0, SP, POST_INDEXED, 16);

    // Reload our return X30 from the stack, and return.
    cg.LDR(X30, SP, 8);
    cg.RET();
}


void Patcher::WriteSvcTrampoline(ModuleDestLabel module_dest, u32 svc_id,
                                 oaknut::VectorCodeGenerator& cg, oaknut::Label& save_ctx,
                                 oaknut::Label& load_ctx) {
    // Determine if we're writing to the pre-patch buffer.
    const bool is_pre = (&cg == &c_pre);

    // We are about to start saving state, so we need to lock the context.
    this->LockContext(cg);

    // Store guest X30 to the stack. Then, save the context and restore the
    // stack. This saves all registers except PC, which we know at patch time.
    cg.STR(X30, SP, PRE_INDEXED, -16);
    cg.BL(save_ctx);
    cg.LDR(X30, SP, POST_INDEXED, 16);

    // Now that we've saved all registers, we can use any registers as scratch.
    // Store PC + 4 to the context, since we know the instruction offset.
    oaknut::Label pc_after_svc;
    cg.MRS(X1, oaknut::SystemReg::TPIDR_EL0);
    cg.LDR(X1, X1, offsetof(NativeExecutionParameters, native_context));
    cg.LDR(X2, pc_after_svc);
    cg.STR(X2, X1, offsetof(GuestContext, pc));

    // Store SVC number to execute when we return.
    cg.MOV(X2, svc_id);
    cg.STR(W2, X1, offsetof(GuestContext, svc));

    // We are calling a SVC. Clear esr_el1 and return it.
    oaknut::Label retry;
    cg.ADD(X2, X1, offsetof(GuestContext, esr_el1));
    cg.l(retry);
    cg.LDAXR(X0, X2);
    cg.STLXR(W3, XZR, X2);
    cg.CBNZ(W3, retry);

    // Add "calling SVC" flag. Since this is X0, this is now our return value.
    cg.ORR(X0, X0, static_cast<u64>(HaltReason::SupervisorCall));

    // Offset the GuestContext pointer to the HostContext member.
    // STP has limited range of [-512, 504] which we can't reach otherwise.
    // NB: Due to this all offsets below are from the start of HostContext.
    cg.ADD(X1, X1, offsetof(GuestContext, host_ctx));

    // Reload host TPIDR_EL0 and SP.
    static_assert(offsetof(HostContext, host_sp) + 8 == offsetof(HostContext, host_tpidr_el0));
    cg.LDP(X2, X3, X1, offsetof(HostContext, host_sp));
    cg.MOV(SP, X2);
    cg.MSR(oaknut::SystemReg::TPIDR_EL0, X3);

    // Load callee-saved host registers and return to host.
    static constexpr size_t HOST_REGS_OFF = offsetof(HostContext, host_saved_regs);
    static constexpr size_t HOST_VREGS_OFF = offsetof(HostContext, host_saved_vregs);
    cg.LDP(X19, X20, X1, HOST_REGS_OFF);
    cg.LDP(X21, X22, X1, HOST_REGS_OFF + 2 * sizeof(u64));
    cg.LDP(X23, X24, X1, HOST_REGS_OFF + 4 * sizeof(u64));
    cg.LDP(X25, X26, X1, HOST_REGS_OFF + 6 * sizeof(u64));
    cg.LDP(X27, X28, X1, HOST_REGS_OFF + 8 * sizeof(u64));
    cg.LDP(X29, X30, X1, HOST_REGS_OFF + 10 * sizeof(u64));
    cg.LDP(Q8, Q9, X1, HOST_VREGS_OFF);
    cg.LDP(Q10, Q11, X1, HOST_VREGS_OFF + 2 * sizeof(u128));
    cg.LDP(Q12, Q13, X1, HOST_VREGS_OFF + 4 * sizeof(u128));
    cg.LDP(Q14, Q15, X1, HOST_VREGS_OFF + 6 * sizeof(u128));
    cg.RET();

    // Write the post-SVC trampoline address, which will jump back to the
    // guest after restoring its state.
    if (is_pre) {
        curr_patch->m_trampolines_pre.push_back({static_cast<ptrdiff_t>(cg.offset()), module_dest});
    } else {
        curr_patch->m_trampolines.push_back({static_cast<ptrdiff_t>(cg.offset()), module_dest});
    }

    // Host called this location. Save the return address so we can
    // unwind the stack properly when jumping back.
    cg.MRS(X2, oaknut::SystemReg::TPIDR_EL0);
    cg.LDR(X2, X2, offsetof(NativeExecutionParameters, native_context));
    cg.ADD(X0, X2, offsetof(GuestContext, host_ctx));
    cg.STR(X30, X0, offsetof(HostContext, host_saved_regs) + 11 * sizeof(u64));

    // Reload all guest registers except X30 and PC.
    // The function also expects 16 bytes of stack already allocated.
    cg.STR(X30, SP, PRE_INDEXED, -16);
    cg.BL(load_ctx);
    cg.LDR(X30, SP, POST_INDEXED, 16);

    // Use X1 as a scratch register to restore X30.
    cg.STR(X1, SP, PRE_INDEXED, -16);
    cg.MRS(X1, oaknut::SystemReg::TPIDR_EL0);
    cg.LDR(X1, X1, offsetof(NativeExecutionParameters, native_context));
    cg.LDR(X30, X1, offsetof(GuestContext, cpu_registers) + sizeof(u64) * 30);
    cg.LDR(X1, SP, POST_INDEXED, 16);

    // Unlock the context.
    this->UnlockContext(cg);

    // Jump back to the instruction after the emulated SVC.
    if (is_pre) {
        this->BranchToModulePre(module_dest);
    } else {
        this->BranchToModule(module_dest);
    }

    // Store PC after call.
    cg.l(pc_after_svc);
    if (is_pre) {
        this->WriteModulePcPre(module_dest);
    } else {
        this->WriteModulePc(module_dest);
    }
}


void Patcher::WriteMrsHandler(ModuleDestLabel module_dest, oaknut::XReg dest_reg,
                              oaknut::SystemReg src_reg, oaknut::VectorCodeGenerator& cg) {
    cg.MRS(dest_reg, oaknut::SystemReg::TPIDR_EL0);
    if (src_reg == oaknut::SystemReg::TPIDRRO_EL0) {
        cg.LDR(dest_reg, dest_reg, offsetof(NativeExecutionParameters, tpidrro_el0));
    } else {
        cg.LDR(dest_reg, dest_reg, offsetof(NativeExecutionParameters, tpidr_el0));
    }
    if (&cg == &c_pre) {
        this->BranchToModulePre(module_dest);
    } else {
        this->BranchToModule(module_dest);
    }
}

void Patcher::WriteMsrHandler(ModuleDestLabel module_dest, oaknut::XReg src_reg,
                              oaknut::VectorCodeGenerator& cg) {
    const auto scratch_reg = src_reg.index() == 0 ? X1 : X0;
    cg.STR(scratch_reg, SP, PRE_INDEXED, -16);
    cg.MRS(scratch_reg, oaknut::SystemReg::TPIDR_EL0);
    cg.STR(src_reg, scratch_reg, offsetof(NativeExecutionParameters, tpidr_el0));
    cg.LDR(scratch_reg, SP, POST_INDEXED, 16);
    if (&cg == &c_pre) {
        this->BranchToModulePre(module_dest);
    } else {
        this->BranchToModule(module_dest);
    }
}

void Patcher::WriteCntfrqHandler(ModuleDestLabel module_dest, oaknut::XReg dest_reg,
                                 oaknut::VectorCodeGenerator& cg) {
    // The Switch exposes a fixed 19.2 MHz counter frequency.
    cg.MOV(dest_reg, GuestCntfrq);
    if (&cg == &c_pre) {
        this->BranchToModulePre(module_dest);
    } else {
        this->BranchToModule(module_dest);
    }
}

void Patcher::WriteCntpctHandler(ModuleDestLabel module_dest, oaknut::XReg dest_reg,
                                 oaknut::VectorCodeGenerator& cg) {
    // Scale the host counter to the guest frequency.
    // factor = (guest_freq << 64) / host_freq as a 128-bit fixed-point value.
    u64 host_cntfrq;
    asm volatile("mrs %0, cntfrq_el0" : "=r"(host_cntfrq));
    const unsigned __int128 factor128 =
        (static_cast<unsigned __int128>(GuestCntfrq) << 64) / host_cntfrq;
    const u64 factor_hi = static_cast<u64>(factor128 >> 64);
    const u64 factor_lo = static_cast<u64>(factor128);

    const auto use_x2_x3 = dest_reg.index() == 0 || dest_reg.index() == 1;
    oaknut::XReg scratch0 = use_x2_x3 ? X2 : X0;
    oaknut::XReg scratch1 = use_x2_x3 ? X3 : X1;

    oaknut::Label factorlo;
    oaknut::Label factorhi;

    cg.STP(scratch0, scratch1, SP, PRE_INDEXED, -16);   // Save scratches
    cg.MRS(dest_reg, oaknut::SystemReg::CNTVCT_EL0);    // Load counter
    cg.LDR(scratch0, factorlo);                         // Load factor
    cg.LDR(scratch1, factorhi);
    cg.UMULH(scratch0, dest_reg, scratch0);             // High 64 of lo*lo
    cg.MADD(dest_reg, dest_reg, scratch1, scratch0);    // Combine
    cg.LDP(scratch0, scratch1, SP, POST_INDEXED, 16);   // Restore scratches

    if (&cg == &c_pre) {
        this->BranchToModulePre(module_dest);
    } else {
        this->BranchToModule(module_dest);
    }

    cg.l(factorlo);
    cg.dx(factor_lo);
    cg.l(factorhi);
    cg.dx(factor_hi);
}


void Patcher::LockContext(oaknut::VectorCodeGenerator& cg) {
    oaknut::Label retry;

    cg.STP(X0, X1, SP, PRE_INDEXED, -16);   // Save scratches
    cg.l(retry);
    cg.CLREX();
    cg.MRS(X0, oaknut::SystemReg::TPIDR_EL0);
    cg.ADD(X0, X0, offsetof(NativeExecutionParameters, lock));

    static_assert(SpinLockLocked == 0);

    cg.LDAXR(W1, X0);      // Load-linked with acquire ordering
    cg.CBZ(W1, retry);     // If locked, clear monitor and retry
    cg.STXR(W1, WZR, X0);  // Store-conditional locked (0)
    cg.CBNZ(W1, retry);    // If we failed to store, retry

    cg.LDP(X0, X1, SP, POST_INDEXED, 16);   // Reload scratches
}

void Patcher::UnlockContext(oaknut::VectorCodeGenerator& cg) {
    cg.STP(X0, X1, SP, PRE_INDEXED, -16);   // Save scratches
    cg.MRS(X0, oaknut::SystemReg::TPIDR_EL0);
    cg.ADD(X0, X0, offsetof(NativeExecutionParameters, lock));
    cg.MOV(W1, SpinLockUnlocked);
    cg.STLR(W1, X0);       // Store with release ordering
    cg.LDP(X0, X1, SP, POST_INDEXED, 16);   // Reload scratches
}

// --- Relocation queue helpers ---

void Patcher::BranchToPatch(uintptr_t module_dest) {
    curr_patch->m_branch_to_patch_relocations.push_back(
        {static_cast<ptrdiff_t>(c.offset()), module_dest});
}

void Patcher::BranchToPatchPre(uintptr_t module_dest) {
    curr_patch->m_branch_to_pre_patch_relocations.push_back(
        {static_cast<ptrdiff_t>(c_pre.offset()), module_dest});
}

void Patcher::BranchToModule(uintptr_t module_dest) {
    curr_patch->m_branch_to_module_relocations.push_back(
        {static_cast<ptrdiff_t>(c.offset()), module_dest});
    c.dw(0);
}

void Patcher::BranchToModulePre(uintptr_t module_dest) {
    curr_patch->m_branch_to_module_relocations_pre.push_back(
        {static_cast<ptrdiff_t>(c_pre.offset()), module_dest});
    c_pre.dw(0);
}

void Patcher::WriteModulePc(uintptr_t module_dest) {
    curr_patch->m_write_module_pc_relocations.push_back(
        {static_cast<ptrdiff_t>(c.offset()), module_dest});
    c.dx(0);
}

void Patcher::WriteModulePcPre(uintptr_t module_dest) {
    curr_patch->m_write_module_pc_relocations_pre.push_back(
        {static_cast<ptrdiff_t>(c_pre.offset()), module_dest});
    c_pre.dx(0);
}

} // namespace Ryubing::Nce

