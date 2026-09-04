// SPDX-License-Identifier: MIT
//
// Internal (non-ABI) hooks shared between nce.cpp, signal_handlers.cpp and
// the self test. Not part of nce.h; managed code must not call these.

#ifndef RYUBING_NCE_INTERNAL_H
#define RYUBING_NCE_INTERNAL_H

#include <atomic>
#include <cstdint>
#include <unordered_map>

namespace Ryubing::Nce {

// Identity-mapped guest window [base, base+size) and the managed page-fault
// callback. Defined in signal_handlers.cpp, published by nce_set_memory_config.
extern std::atomic<uint64_t> g_guest_host_base;
extern std::atomic<uint64_t> g_guest_as_size;
extern std::atomic<uintptr_t> g_page_fault_handler;

namespace Internal {

// Snapshot / restore of the SVC re-entry trampoline registry (nce.cpp), so the
// self test can register its own trampolines without disturbing a loaded game.
std::unordered_map<uint64_t, uint64_t> SnapshotTrampolines();
void RestoreTrampolines(std::unordered_map<uint64_t, uint64_t> trampolines);

} // namespace Internal
} // namespace Ryubing::Nce

#endif // RYUBING_NCE_INTERNAL_H
