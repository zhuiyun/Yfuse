#include "ycore_disc_language.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

// Checked in every build type; assert() would vanish under NDEBUG.
#define CHECK(condition)                                                     \
    do {                                                                     \
        if (!(condition)) {                                                  \
            std::fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #condition); \
            std::exit(1);                                                    \
        }                                                                    \
    } while (false)

namespace {

// The two fields of libbluray's BLURAY_STREAM_INFO the lookup reads.
struct Stream {
    uint8_t lang[4];
    uint16_t pid;
};

}  // namespace

int main() {
    const Stream audio[] = {{{'e', 'n', 'g', 0}, 0x1100}, {{'c', 'h', 'i', 0}, 0x1101}};
    CHECK(ycore_disc::find_stream_by_pid(audio, 2, 0x1101) == &audio[1]);
    CHECK(ycore_disc::find_stream_by_pid(audio, 2, 0x1200) == nullptr);
    CHECK(ycore_disc::find_stream_by_pid<Stream>(nullptr, 2, 0x1100) == nullptr);
    CHECK(ycore_disc::find_stream_by_pid(audio, 1, 0x1101) == nullptr);

    char language[4] = {};
    CHECK(ycore_disc::iso639_language(audio[1].lang, language));
    CHECK(std::strcmp(language, "chi") == 0);
    const uint8_t upper[4] = {'J', 'P', 'N', 0};
    CHECK(ycore_disc::iso639_language(upper, language));
    CHECK(std::strcmp(language, "jpn") == 0);

    const uint8_t unset[4] = {0, 0, 0, 0};
    const uint8_t padded[4] = {'e', 'n', ' ', 0};
    const uint8_t digits[4] = {'0', '0', '1', 0};
    CHECK(!ycore_disc::iso639_language(unset, language));
    CHECK(!ycore_disc::iso639_language(padded, language));
    CHECK(!ycore_disc::iso639_language(digits, language));
    CHECK(!ycore_disc::iso639_language(nullptr, language));
    return 0;
}
