#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

namespace Ryubing::Nce {

class HostMappedMemory {
public:
    HostMappedMemory(std::uint64_t host_base, std::uint64_t as_size)
        : base_(host_base), size_(as_size) {}

    bool Contains(std::uint64_t addr, std::size_t len) const {
        if (size_ == 0 || base_ == 0)
            return false;
        if (addr < base_)
            return false;
        if (addr + len < addr)
            return false; // overflow
        return (addr + len) <= (base_ + size_);
    }

    std::uint32_t Read32(std::uint64_t addr) const {
        std::uint32_t v = 0;
        ReadBlock(addr, &v, sizeof(v));
        return v;
    }

    bool ReadBlock(std::uint64_t addr, void* dest, std::size_t len) const {
        if (!Contains(addr, len) || dest == nullptr)
            return false;
        // Byte-wise copy: safe for misaligned host accesses that caused SIGBUS.
        auto* src = reinterpret_cast<const std::uint8_t*>(addr);
        auto* dst = static_cast<std::uint8_t*>(dest);
        for (std::size_t i = 0; i < len; ++i)
            dst[i] = src[i];
        return true;
    }

    bool WriteBlock(std::uint64_t addr, const void* src, std::size_t len) const {
        if (!Contains(addr, len) || src == nullptr)
            return false;
        auto* dst = reinterpret_cast<std::uint8_t*>(addr);
        auto* s = static_cast<const std::uint8_t*>(src);
        for (std::size_t i = 0; i < len; ++i)
            dst[i] = s[i];
        return true;
    }

private:
    std::uint64_t base_{};
    std::uint64_t size_{};
};

}  // namespace Ryubing::Nce
