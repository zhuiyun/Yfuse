#include "ycore_extradata_budget.h"

#include <cassert>
#include <limits>

int main() {
    using ycore_demux::ExtradataBudget;
    ExtradataBudget budget;
    const int limit = static_cast<int>(ExtradataBudget::kMaximumBytes);
    assert(!budget.allows(0, -1, false));
    assert(!budget.allows(0, std::numeric_limits<int>::max(), false));
    assert(!budget.allows(0, limit + 1, true));
    assert(budget.allows(0, limit - 1, true));
    budget.copied(0, limit - 1, true);
    assert(!budget.allows(1, 2, true));
    assert(budget.allows(1, 1, true));
    budget.copied(1, 1, true);
    assert(!budget.allows(2, 1, true));
    assert(budget.allows(0, limit - 1, true));
    budget.copied(0, limit - 1, true);
    assert(!budget.allows(2, 1, true));
    assert(budget.allows(3, limit, false));
    budget.copied(3, limit, false);
    assert(!budget.allows(4, 1, false));

    ExtradataBudget many_fonts;
    for (int index = 0; index < 128; ++index) {
        assert(many_fonts.allows(index, 1, true));
        many_fonts.copied(index, 1, true);
    }
    assert(!many_fonts.allows(128, 1, true));
}
