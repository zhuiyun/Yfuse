#ifndef YFUSE_YCORE_DISC_URI_H
#define YFUSE_YCORE_DISC_URI_H

#include <cstdint>
#include <limits>
#include <string>

namespace ycore_disc {

inline bool parse_source_id(const std::string& uri, int64_t* source_id) {
    constexpr char kPrefix[] = "ycorebd://";
    if (!source_id || uri.compare(0, sizeof(kPrefix) - 1, kPrefix) != 0) return false;
    const std::string value = uri.substr(sizeof(kPrefix) - 1);
    if (value.empty()) return false;
    int64_t parsed = 0;
    for (const char character : value) {
        if (character < '0' || character > '9') return false;
        const int digit = character - '0';
        if (parsed > (std::numeric_limits<int64_t>::max() - digit) / 10) return false;
        parsed = parsed * 10 + digit;
    }
    if (parsed <= 0) return false;
    *source_id = parsed;
    return true;
}

}  // namespace ycore_disc

#endif
