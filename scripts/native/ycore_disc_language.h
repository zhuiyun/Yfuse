#ifndef YFUSE_YCORE_DISC_LANGUAGE_H
#define YFUSE_YCORE_DISC_LANGUAGE_H

#include <cstdint>

namespace ycore_disc {

// The entry of a Blu-ray stream table (libbluray's BLURAY_STREAM_INFO) for an MPEG-TS PID.
template <typename StreamInfo>
const StreamInfo* find_stream_by_pid(const StreamInfo* streams, int count, int pid) {
    for (int index = 0; streams && index < count; ++index) {
        if (streams[index].pid == pid) return &streams[index];
    }
    return nullptr;
}

// A stream table's three-letter ISO 639-2 code, lowercased into [language]. False for anything
// else: an unset "\0\0\0", padding, or bytes that are not letters.
inline bool iso639_language(const uint8_t* code, char language[4]) {
    if (!code) return false;
    for (int index = 0; index < 3; ++index) {
        char letter = static_cast<char>(code[index]);
        if (letter >= 'A' && letter <= 'Z') letter = static_cast<char>(letter - 'A' + 'a');
        if (letter < 'a' || letter > 'z') return false;
        language[index] = letter;
    }
    language[3] = '\0';
    return true;
}

}  // namespace ycore_disc

#endif
