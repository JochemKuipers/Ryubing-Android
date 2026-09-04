#pragma once

#include <cassert>

#define ASSERT(x) assert(x)
#define DEBUG_ASSERT(x) assert(x)
#define UNREACHABLE() assert(false)
#define ASSERT_MSG(x, ...) assert(x)
