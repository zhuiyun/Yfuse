package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

class AccentColorContractTest {
    @Test
    fun everyAccentProducesDistinctInteractiveColorInBothThemes() {
        listOf(false, true).forEach { dark ->
            val resolved = AccentColor.entries.map { it.resolveColors(dark).accent }
            assertEquals(
                AccentColor.entries.size,
                resolved.distinct().size,
                "Every visible accent choice must change the resolved interactive colour",
            )
        }
    }
}
