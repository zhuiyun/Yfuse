#include "ycore_tone_map.h"

#include <cassert>

int main() {
    using ycore_tone_map::Transfer;
    const auto pq_black = ycore_tone_map::bt2020_to_sdr(0, 0, 0, Transfer::Pq);
    assert(pq_black.red == 0 && pq_black.green == 0 && pq_black.blue == 0);
    assert(pq_black.alpha == 0xff);

    const auto pq_reference_white =
        ycore_tone_map::bt2020_to_sdr(33297, 33297, 33297, Transfer::Pq);
    assert(pq_reference_white.red > 140 && pq_reference_white.red < 180);
    assert(pq_reference_white.red == pq_reference_white.green);
    assert(pq_reference_white.green == pq_reference_white.blue);

    const auto hlg_white = ycore_tone_map::bt2020_to_sdr(65535, 65535, 65535, Transfer::Hlg);
    assert(hlg_white.red == 255 && hlg_white.green == 255 && hlg_white.blue == 255);

    const auto saturated = ycore_tone_map::bt2020_to_sdr(65535, 0, 0, Transfer::Pq);
    assert(saturated.red >= saturated.green && saturated.red >= saturated.blue);
    return 0;
}
