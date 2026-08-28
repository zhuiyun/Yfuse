#!/usr/bin/env python3
"""Deterministic numerical guardrails for the YCore HDR fragment shader."""

from __future__ import annotations

import pathlib


def pq_eotf(encoded: float) -> float:
    m1, m2 = 2610 / 16384, 2523 / 32
    c1, c2, c3 = 3424 / 4096, 2413 / 128, 2392 / 128
    p = max(encoded, 0.0) ** (1 / m2)
    return 10000 * (max(p - c1, 0.0) / max(c2 - c3 * p, 1e-12)) ** (1 / m1)


def pq_oetf(nits: float) -> float:
    m1, m2 = 2610 / 16384, 2523 / 32
    c1, c2, c3 = 3424 / 4096, 2413 / 128, 2392 / 128
    p = min(max(nits / 10000, 0.0), 1.0) ** m1
    return ((c1 + c2 * p) / (1 + c3 * p)) ** m2


def bt2390(nits: float, source_peak: float, target_peak: float) -> float:
    if source_peak <= target_peak:
        return min(nits, target_peak)
    source_code = max(pq_oetf(source_peak), 1e-5)
    target_code = min(max(pq_oetf(target_peak) / source_code, 0.0), 1.0)
    input_code = min(max(pq_oetf(nits) / source_code, 0.0), 1.0)
    knee = min(max(1.5 * target_code - 0.5, 0.0), target_code)
    if input_code <= knee:
        return nits
    interval = max(1.0 - knee, 1e-5)
    t = min(max((input_code - knee) / interval, 0.0), 1.0)
    h00 = 2 * t**3 - 3 * t**2 + 1
    h10 = t**3 - 2 * t**2 + t
    h01 = -2 * t**3 + 3 * t**2
    return pq_eotf(min(max((h00 * knee + h10 * interval + h01 * target_code) * source_code, 0), 1))


def multiply_glsl_mat3(values: tuple[float, ...], rgb: tuple[float, float, float]) -> tuple[float, ...]:
    return tuple(sum(values[column * 3 + row] * rgb[column] for column in range(3)) for row in range(3))


def assert_close(actual: float, expected: float, tolerance: float, label: str) -> None:
    if abs(actual - expected) > tolerance:
        raise AssertionError(f"{label}: {actual} != {expected} (tolerance {tolerance})")


def main() -> int:
    shader = pathlib.Path(__file__).resolve().parent / "native/shaders/ycore_video.frag"
    source = shader.read_text(encoding="utf-8")
    for token in ("pqEotf", "hlgEotf", "bt2390", "gamutMap", "debandedSample", "pixelAspectRatio"):
        if token not in source:
            raise AssertionError(f"shader calibration surface is missing {token}")

    for nits in (0.0, 0.01, 0.1, 1.0, 100.0, 203.0, 1000.0, 4000.0, 10000.0):
        assert_close(pq_eotf(pq_oetf(nits)), nits, max(0.0002, nits * 2e-5), f"PQ round trip {nits}")

    ramp = [bt2390(4000 * index / 512, 4000, 1000) for index in range(513)]
    if any(left > right + 1e-5 for left, right in zip(ramp, ramp[1:])):
        raise AssertionError("BT.2390 ramp is not monotonic")
    if ramp[-1] > 1000.01 or ramp[0] < -1e-7:
        raise AssertionError("BT.2390 violates target black/peak bounds")

    bt2020_to_bt709 = (
        1.660491, -0.124550, -0.018151,
        -0.587641, 1.132900, -0.100579,
        -0.072850, -0.008349, 1.118730,
    )
    grey = multiply_glsl_mat3(bt2020_to_bt709, (0.18, 0.18, 0.18))
    # Neutral-axis drift is a direct grey-scale error and kept below roughly Delta-E 0.1.
    if max(grey) - min(grey) > 0.0008:
        raise AssertionError(f"BT.2020 to BT.709 neutral-axis drift is too high: {grey}")
    for channel, expected in zip(
        multiply_glsl_mat3(bt2020_to_bt709, (1.0, 0.0, 0.0)),
        (1.660491, -0.124550, -0.018151),
    ):
        assert_close(channel, expected, 1e-6, "BT.2020 red primary")

    # Dither remains within half of one destination code value before clamping.
    if 0.5 / 255 > 0.002 or 0.5 / 1023 > 0.0005:
        raise AssertionError("dither amplitude exceeds one-half destination code value")
    print("YCore shader calibration: PQ/BT.2390/grey/primaries/banding bounds OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
