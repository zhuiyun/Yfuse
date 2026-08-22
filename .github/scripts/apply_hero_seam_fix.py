from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/Hero.kt",
    '''internal fun heroPageFadeMaskStops(): Array<Pair<Float, Color>> =
    arrayOf(
        0f to Color.Transparent,
        0.28f to Color.Black.copy(alpha = 0.10f),
        0.58f to Color.Black.copy(alpha = 0.55f),
        0.82f to Color.Black.copy(alpha = 0.94f),
        0.90f to Color.Black,
        1f to Color.Black,
    )''',
    '''internal fun heroPageFadeMaskStops(): Array<Pair<Float, Color>> =
    arrayOf(
        0f to Color.Transparent,
        0.24f to Color.Black.copy(alpha = 0.04f),
        0.50f to Color.Black.copy(alpha = 0.28f),
        0.72f to Color.Black.copy(alpha = 0.62f),
        0.88f to Color.Black.copy(alpha = 0.86f),
        0.97f to Color.Black.copy(alpha = 0.97f),
        1f to Color.Black,
    )''',
)

replace_once(
    "composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/DominantColor.kt",
    ''' * Squaring the mask coverage concentrates the fit at the lower edge that must meet the page,
 * while still following the exact S-curve used to remove the artwork. The final colour is a
 * direct linear-light average of source pixels; this weight does not brand, brighten, darken,
 * desaturate, or otherwise retone those pixels.''',
    ''' * Cubing the mask coverage concentrates the fit on the final visible rows that actually meet
 * the page. This matters when the lower hero changes brightness quickly: averaging too much of the
 * earlier fade makes the page target look like a separate colour band even though the last pixel is
 * mathematically continuous. The colour itself is still a direct linear-light source average.''',
)
replace_once(
    "composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/DominantColor.kt",
    "    return coverage * coverage\n",
    "    return coverage * coverage * coverage\n",
)

replace_once(
    "composeApp/src/commonMain/kotlin/com/yfuse/feature/library/LibraryHomeScreen.kt",
    '''                                        }.padding(top = 78.dp),''',
    '''                                        // Match the visual lift exactly: a larger top inset left a
                                        // full-width strip of bare page colour between the hero melt and
                                        // the first library card, which read as a horizontal seam.
                                        }.padding(top = HeroLift),''',
)

replace_once(
    "composeApp/src/commonTest/kotlin/com/yfuse/core/designsystem/DominantColorTest.kt",
    '''    @Test
    fun heroMask_revealsTheExactPageColourAtTheLastPixel() {
        val stops = heroPageFadeMaskStops()

        assertTrue(stops.first().second.alpha == 0f)
        assertTrue(stops[stops.lastIndex - 1].first == 0.90f)
        assertTrue(stops[stops.lastIndex - 1].second.alpha == 1f)
        assertTrue(stops.last().second.alpha == 1f)
        assertTrue(
            stops
                .asList()
                .zipWithNext()
                .all { (current, next) -> current.second.alpha <= next.second.alpha },
        )
    }''',
    '''    @Test
    fun heroMask_revealsThePageOnlyAtTheLastPixelWithoutAFlatTail() {
        val stops = heroPageFadeMaskStops()

        assertTrue(stops.first().second.alpha == 0f)
        assertTrue(stops.dropLast(1).all { it.second.alpha < 1f })
        assertTrue(stops.last().first == 1f)
        assertTrue(stops.last().second.alpha == 1f)
        assertTrue(
            stops
                .asList()
                .zipWithNext()
                .all { (current, next) -> current.second.alpha <= next.second.alpha },
        )
    }''',
)

print("hero seam patch applied")
