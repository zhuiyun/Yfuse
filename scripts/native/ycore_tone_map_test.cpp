#include "ycore_parallel_rows.h"
#include "ycore_tone_map.h"

#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <vector>
#include <limits>

namespace {

int g_failures = 0;

// Checks stay live under -DNDEBUG; a release-flagged build of this test must still test.
#define CHECK(condition)                                                                   \
    do {                                                                                   \
        if (!(condition)) {                                                                \
            std::fprintf(stderr, "%s:%d: check failed: %s\n", __FILE__, __LINE__, #condition); \
            ++g_failures;                                                                  \
        }                                                                                  \
    } while (0)

using ycore_tone_map::Mapper;
using ycore_tone_map::RgbaPixel;
using ycore_tone_map::Transfer;

static_assert(offsetof(RgbaPixel, red) == 0, "Android ARGB_8888 stores red first");
static_assert(offsetof(RgbaPixel, green) == 1, "Android ARGB_8888 stores green second");
static_assert(offsetof(RgbaPixel, blue) == 2, "Android ARGB_8888 stores blue third");
static_assert(offsetof(RgbaPixel, alpha) == 3, "Android ARGB_8888 stores alpha last");
static_assert(sizeof(RgbaPixel) == 4, "one pixel is four bytes");

uint16_t code(double encoded) {
    return static_cast<uint16_t>(std::lround(std::clamp(encoded, 0.0, 1.0) * 65535.0));
}

/** Inverse PQ EOTF, for building inputs at an exact luminance. */
double nits_to_pq(double nits) {
    constexpr double m1 = 2610.0 / 16384.0;
    constexpr double m2 = 2523.0 / 32.0;
    constexpr double c1 = 3424.0 / 4096.0;
    constexpr double c2 = 2413.0 / 128.0;
    constexpr double c3 = 2392.0 / 128.0;
    const double powered = std::pow(nits / 10000.0, m1);
    return std::pow((c1 + c2 * powered) / (1.0 + c3 * powered), m2);
}

int channel_distance(const RgbaPixel& first, const RgbaPixel& second) {
    return std::max({
        std::abs(first.red - second.red),
        std::abs(first.green - second.green),
        std::abs(first.blue - second.blue),
        std::abs(first.alpha - second.alpha),
    });
}

void per_picture_peak_changes_do_not_rebuild_transfer_tables() {
    Mapper mapper(Transfer::Pq, 4000.0);
    const uint16_t gray = code(nits_to_pq(600.0));
    const auto static_pixel = mapper.map(gray, gray, gray);
    mapper.set_peak_nits(600.0);
    const auto scene_pixel = mapper.map(gray, gray, gray);
    CHECK(scene_pixel.red > static_pixel.red);
    CHECK(scene_pixel.red >= 254);
    mapper.set_peak_nits(4000.0);
    CHECK(mapper.map(gray, gray, gray).red == static_pixel.red);
    mapper.set_peak_nits(std::numeric_limits<double>::quiet_NaN());
    CHECK(mapper.map(gray, gray, gray).red == Mapper(Transfer::Pq, 1000.0).map(gray, gray, gray).red);
}

void black_and_peaks() {
    const RgbaPixel pq_black = ycore_tone_map::bt2020_to_sdr(0, 0, 0, Transfer::Pq);
    CHECK(pq_black.red == 0 && pq_black.green == 0 && pq_black.blue == 0);
    CHECK(pq_black.alpha == 0xff);

    const RgbaPixel hlg_peak = ycore_tone_map::bt2020_to_sdr(65535, 65535, 65535, Transfer::Hlg);
    CHECK(hlg_peak.red == 255 && hlg_peak.green == 255 && hlg_peak.blue == 255);

    // The mastering peak lands on SDR white rather than clipping early or staying grey.
    const uint16_t pq_1000 = code(nits_to_pq(1000.0));
    const RgbaPixel pq_peak = ycore_tone_map::bt2020_to_sdr(pq_1000, pq_1000, pq_1000, Transfer::Pq, 1000.0);
    CHECK(pq_peak.red >= 253 && pq_peak.green >= 253 && pq_peak.blue >= 253);
}

void mid_tones() {
    const RgbaPixel pq_100_nits = ycore_tone_map::bt2020_to_sdr(33297, 33297, 33297, Transfer::Pq);
    CHECK(pq_100_nits.red > 140 && pq_100_nits.red < 180);
    CHECK(pq_100_nits.red == pq_100_nits.green && pq_100_nits.green == pq_100_nits.blue);

    // BT.2408 puts reference white at 203 cd/m2 in PQ and at 75% signal in HLG. Both systems must
    // reach the same SDR level for it; HLG used to land about twice as dark.
    const uint16_t pq_white = code(nits_to_pq(203.0));
    const uint16_t hlg_white = code(0.75);
    const RgbaPixel pq = ycore_tone_map::bt2020_to_sdr(pq_white, pq_white, pq_white, Transfer::Pq, 1000.0);
    const RgbaPixel hlg = ycore_tone_map::bt2020_to_sdr(hlg_white, hlg_white, hlg_white, Transfer::Hlg);
    CHECK(std::abs(pq.red - hlg.red) <= 2);
    CHECK(hlg.red > 170 && hlg.red < 215);
}

void hue_and_order() {
    const RgbaPixel red = ycore_tone_map::bt2020_to_sdr(code(nits_to_pq(400.0)), 0, 0, Transfer::Pq);
    CHECK(red.red > 200 && red.green < 40 && red.blue < 40);
    const RgbaPixel blue = ycore_tone_map::bt2020_to_sdr(0, 0, code(nits_to_pq(400.0)), Transfer::Pq);
    CHECK(blue.blue > 200 && blue.red < 40 && blue.green < 60);

    // The HLG OOTF scales all three channels by one luminance gain, so a colour keeps its hue
    // instead of drifting toward its strongest primary as a per-channel gamma would push it.
    const RgbaPixel orange = ycore_tone_map::bt2020_to_sdr(code(0.8), code(0.6), code(0.2), Transfer::Hlg);
    CHECK(orange.red > orange.green && orange.green > orange.blue);
}

void gray_ramps_are_monotonic() {
    for (const Transfer transfer : {Transfer::Pq, Transfer::Hlg}) {
        const Mapper mapper(transfer, 1000.0);
        int previous_reference = -1;
        int previous_mapped = -1;
        for (int value = 0; value <= 65535; value += 64) {
            const auto gray = static_cast<uint16_t>(value);
            const int reference = ycore_tone_map::bt2020_to_sdr(gray, gray, gray, transfer).green;
            const int mapped = mapper.map(gray, gray, gray).green;
            CHECK(reference >= previous_reference);
            CHECK(mapped >= previous_mapped);
            previous_reference = reference;
            previous_mapped = mapped;
        }
    }
}

void table_mapper_matches_reference() {
    for (const Transfer transfer : {Transfer::Pq, Transfer::Hlg}) {
        for (const double peak : {600.0, 1000.0, 4000.0}) {
            const Mapper mapper(transfer, peak);
            int worst = 0;
            constexpr int kSteps = 24;
            for (int red = 0; red <= kSteps; ++red) {
                for (int green = 0; green <= kSteps; ++green) {
                    for (int blue = 0; blue <= kSteps; ++blue) {
                        const uint16_t r = code(static_cast<double>(red) / kSteps);
                        const uint16_t g = code(static_cast<double>(green) / kSteps);
                        const uint16_t b = code(static_cast<double>(blue) / kSteps);
                        const RgbaPixel expected = ycore_tone_map::bt2020_to_sdr(r, g, b, transfer, peak);
                        worst = std::max(worst, channel_distance(expected, mapper.map(r, g, b)));
                    }
                }
            }
            if (worst > 2) std::fprintf(stderr, "table mapper drifts by %d codes\n", worst);
            CHECK(worst <= 2);
        }
    }
}

void row_workers_cover_every_row_once() {
    for (const int extra : {0, 1, 3, 7}) {
        ycore_parallel::RowWorkers workers(extra, "YCoreRowsTest");
        CHECK(workers.bands() == extra + 1);
        for (const int rows : {0, 1, 2, 3, 7, 8, 1080, 2161}) {
            for (int round = 0; round < 25; ++round) {
                std::vector<std::atomic<int>> visits(static_cast<size_t>(std::max(rows, 1)));
                for (std::atomic<int>& visit : visits) visit.store(0);
                std::atomic<int> calls{0};
                workers.run(rows, [&](int first, int end) {
                    CHECK(first >= 0 && first < end && end <= rows);
                    calls.fetch_add(1);
                    for (int row = first; row < end; ++row) visits[static_cast<size_t>(row)].fetch_add(1);
                });
                for (int row = 0; row < rows; ++row) CHECK(visits[static_cast<size_t>(row)].load() == 1);
                CHECK(calls.load() == std::min(rows, workers.bands()));
            }
        }
    }
}

}  // namespace

int main() {
    black_and_peaks();
    per_picture_peak_changes_do_not_rebuild_transfer_tables();
    mid_tones();
    hue_and_order();
    gray_ramps_are_monotonic();
    table_mapper_matches_reference();
    row_workers_cover_every_row_once();
    if (g_failures != 0) {
        std::fprintf(stderr, "%d tone-map check(s) failed\n", g_failures);
        return EXIT_FAILURE;
    }
    return EXIT_SUCCESS;
}
