#include "ycore_disc_uri.h"

#include <cassert>
#include <cstdint>
#include <limits>
#include <string>

int main() {
    int64_t source_id = 0;
    assert(ycore_disc::parse_source_id("ycorebd://42", &source_id));
    assert(source_id == 42);

    assert(!ycore_disc::parse_source_id("ycorebd://", &source_id));
    assert(!ycore_disc::parse_source_id("ycorebd://0", &source_id));
    assert(!ycore_disc::parse_source_id("ycorebd://1/secret", &source_id));
    assert(!ycore_disc::parse_source_id("https://example.invalid/movie.iso", &source_id));
    assert(!ycore_disc::parse_source_id("YCOREBD://1", &source_id));
    assert(
        !ycore_disc::parse_source_id(
            "ycorebd://" + std::to_string(std::numeric_limits<int64_t>::max()) + "0",
            &source_id));
}
