#include "ycore_deinterlace.h"
#include <array>
#include <cstdio>
#include <cstdlib>

#define CHECK(value) do { if (!(value)) { std::fprintf(stderr, "line %d: %s\n", __LINE__, #value); return EXIT_FAILURE; } } while (0)

int main() {
    // Each field is a different instant: a vertical blend would leave combs from the other field.
    const std::array<uint8_t, 6> moving = {10, 200, 30, 220, 50, 240};
    std::array<uint8_t, 6> output{};
    ycore_deinterlace::plane(moving.data(), 1, output.data(), 1, 1, 6, 0, false, false);
    CHECK((output == std::array<uint8_t, 6>{10, 20, 30, 40, 50, 50}));
    ycore_deinterlace::plane(moving.data(), 1, output.data(), 1, 1, 6, 1, false, false);
    CHECK((output == std::array<uint8_t, 6>{200, 200, 210, 220, 230, 240}));
    CHECK(ycore_deinterlace::parity(true, 0) == 0);
    CHECK(ycore_deinterlace::parity(false, 0) == 1);
    CHECK(ycore_deinterlace::parity(false, 1) == 0);
    ycore_deinterlace::plane(moving.data() + 5, -1, output.data(), 1, 1, 5, 0, false, false);
    CHECK(output[0] == 240 && output[1] == 230 && output[4] == 200);
    ycore_deinterlace::plane(moving.data(), 1, output.data(), 1, 1, 1, 1, false, false);
    CHECK(output[0] == 10);
    // Preserve precision: byte-wise interpolation would turn the centre 0x0100 into 0x0180.
    const std::array<uint8_t, 6> words = {0xff, 0x00, 0, 0, 0x01, 0x01};
    ycore_deinterlace::plane(words.data(), 2, output.data(), 2, 2, 3, 0, true, false);
    CHECK(output[2] == 0 && output[3] == 1);
    const std::array<uint8_t, 6> be = {0x00, 0xff, 0, 0, 0x01, 0x01};
    ycore_deinterlace::plane(be.data(), 2, output.data(), 2, 2, 3, 0, true, true);
    CHECK(output[2] == 1 && output[3] == 0);
    CHECK(ycore_deinterlace::field_pts(1'000'000, 40'000, 1) == 1'020'000);
    CHECK(ycore_deinterlace::field_pts(1'000'000, 33'367, 1) == 1'016'683);
    CHECK(ycore_deinterlace::field_pts(INT64_MIN, 40'000, 1) == INT64_MIN);
    CHECK(ycore_deinterlace::field_pts(INT64_MAX - 10, 40'000, 1) == INT64_MAX);
    return EXIT_SUCCESS;
}
