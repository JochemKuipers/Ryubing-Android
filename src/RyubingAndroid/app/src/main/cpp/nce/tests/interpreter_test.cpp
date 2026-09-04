// SPDX-License-Identifier: MIT
//
// Host-side unit test for the NCE alignment-fault interpreter.
// Exercises HostMappedMemory + Dynarmic Decode + InterpreterVisitor without
// signals (runs on any host arch).

#include <array>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <span>
#include <vector>

#include <sys/mman.h>

#include "host_mapped_memory.h"
#include "interpreter_visitor.h"

using Ryubing::Nce::HostMappedMemory;
using Ryubing::Nce::InterpreterVisitor;
using Ryubing::Nce::VisitorBase;

namespace {

// LDR W0, [X1] / STR W0, [X1] (unsigned immediate, offset 0)
constexpr uint32_t LDR_W0_X1 = 0xB9400020;
constexpr uint32_t STR_W0_X1 = 0xB9000020;

int Fail(const char* msg) {
    std::fprintf(stderr, "FAIL: %s\n", msg);
    return 1;
}

} // namespace

int main() {
    // Allocate a host buffer that acts as the guest AS.
    constexpr size_t AsSize = 4096;
    std::vector<uint8_t> as(AsSize, 0);
    const uint64_t base = reinterpret_cast<uint64_t>(as.data());
    HostMappedMemory memory(base, AsSize);

    // Misaligned u32 payload at offset 1.
    constexpr uint32_t Payload = 0xA1B2C3D4u;
    const uint64_t data_addr = base + 1;
    {
        const auto* p = reinterpret_cast<const uint8_t*>(&Payload);
        for (size_t i = 0; i < 4; ++i) {
            as[1 + i] = p[i];
        }
    }

    // --- HostMappedMemory byte-wise read/write ---
    {
        uint32_t got = 0;
        if (!memory.ReadBlock(data_addr, &got, sizeof(got)) || got != Payload) {
            return Fail("HostMappedMemory misaligned ReadBlock");
        }
        const uint32_t alt = 0x11223344u;
        if (!memory.WriteBlock(data_addr, &alt, sizeof(alt))) {
            return Fail("HostMappedMemory misaligned WriteBlock");
        }
        uint32_t got2 = 0;
        if (!memory.ReadBlock(data_addr, &got2, sizeof(got2)) || got2 != alt) {
            return Fail("HostMappedMemory round-trip");
        }
        // Restore payload for LDR test.
        memory.WriteBlock(data_addr, &Payload, sizeof(Payload));
    }

    if (memory.Contains(base + AsSize - 1, 2)) {
        return Fail("Contains should reject OOB spanning end");
    }
    if (!memory.Contains(base, AsSize)) {
        return Fail("Contains should accept full range");
    }

    // --- Decode + emulate LDR W0, [X1] from a fake PC ---
    {
        std::array<u64, 31> regs{};
        std::array<u128, 32> vregs{};
        u64 sp = base + 0x800;
        const u64 pc = base + 0x100;
        // Place instruction at PC.
        std::memcpy(as.data() + 0x100, &LDR_W0_X1, sizeof(LDR_W0_X1));
        regs[1] = data_addr; // X1 -> misaligned payload

        InterpreterVisitor visitor(memory, std::span<u64, 31>{regs.data(), 31},
                                   std::span<u128, 32>{vregs.data(), 32}, sp, pc);
        const uint32_t insn = memory.Read32(pc);
        auto decoded = Dynarmic::A64::Decode<VisitorBase, bool>(visitor, insn);
        if (!decoded || !*decoded) {
            return Fail("LDR W0,[X1] not decoded/executed by interpreter");
        }
        if (static_cast<uint32_t>(regs[0]) != Payload) {
            std::fprintf(stderr, "FAIL: LDR result 0x%llx expected 0x%x\n",
                         static_cast<unsigned long long>(regs[0]), Payload);
            return 1;
        }
    }

    // --- STR W0, [X1] ---
    {
        std::array<u64, 31> regs{};
        std::array<u128, 32> vregs{};
        u64 sp = base + 0x800;
        const u64 pc = base + 0x104;
        const uint32_t store_val = 0x55AA55AAu;
        std::memcpy(as.data() + 0x104, &STR_W0_X1, sizeof(STR_W0_X1));
        regs[0] = store_val;
        regs[1] = base + 0x201; // misaligned dest

        InterpreterVisitor visitor(memory, std::span<u64, 31>{regs.data(), 31},
                                   std::span<u128, 32>{vregs.data(), 32}, sp, pc);
        auto decoded = Dynarmic::A64::Decode<VisitorBase, bool>(visitor, STR_W0_X1);
        if (!decoded || !*decoded) {
            return Fail("STR W0,[X1] not decoded/executed by interpreter");
        }
        uint32_t got = 0;
        memory.ReadBlock(base + 0x201, &got, sizeof(got));
        if (got != store_val) {
            std::fprintf(stderr, "FAIL: STR wrote 0x%x expected 0x%x\n", got, store_val);
            return 1;
        }
    }

    // --- Same LDR case against a window mapped at a high identity-style base ---
    // NCE guest VAs are host pointers >= 2^36; make sure nothing in the
    // interpreter or HostMappedMemory truncates or mis-compares such addresses.
    {
        constexpr uint64_t HighBaseHint = 0x5000000000ull; // 320 GiB
        constexpr size_t HighSize = 0x10000;
        void* mapped = mmap(reinterpret_cast<void*>(HighBaseHint), HighSize, PROT_READ | PROT_WRITE,
                            MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (mapped == MAP_FAILED) {
            return Fail("mmap for high-base case failed");
        }
        const uint64_t high_base = reinterpret_cast<uint64_t>(mapped);
        if (high_base < (1ull << 36)) {
            std::printf("note: kernel placed high-base mapping at 0x%llx (< 2^36); still testing\n",
                        (unsigned long long)high_base);
        }
        HostMappedMemory high(high_base, HighSize);

        auto* bytes = static_cast<uint8_t*>(mapped);
        constexpr uint32_t HighPayload = 0x0BADF00Du;
        std::memcpy(bytes + 0x801, &HighPayload, sizeof(HighPayload)); // misaligned
        std::memcpy(bytes + 0x100, &LDR_W0_X1, sizeof(LDR_W0_X1));

        if (!high.Contains(high_base + 0x801, 4) || high.Contains(high_base - 1, 1) ||
            high.Contains(high_base + HighSize, 1)) {
            return Fail("HostMappedMemory Contains wrong at high base");
        }

        std::array<u64, 31> regs{};
        std::array<u128, 32> vregs{};
        u64 sp = high_base + 0x8000;
        const u64 pc = high_base + 0x100;
        regs[1] = high_base + 0x801;

        InterpreterVisitor visitor(high, std::span<u64, 31>{regs.data(), 31},
                                   std::span<u128, 32>{vregs.data(), 32}, sp, pc);
        const uint32_t insn = high.Read32(pc);
        if (insn != LDR_W0_X1) {
            return Fail("Read32 at high PC returned wrong instruction");
        }
        auto decoded = Dynarmic::A64::Decode<VisitorBase, bool>(visitor, insn);
        if (!decoded || !*decoded) {
            return Fail("LDR at high base not decoded/executed");
        }
        if (static_cast<uint32_t>(regs[0]) != HighPayload) {
            std::fprintf(stderr, "FAIL: high-base LDR result 0x%llx expected 0x%x\n",
                         static_cast<unsigned long long>(regs[0]), HighPayload);
            return 1;
        }
        munmap(mapped, HighSize);
    }

    std::printf("ok: host_mapped_memory + LDR/STR interpreter (incl. high identity base)\n");
    return 0;
}
