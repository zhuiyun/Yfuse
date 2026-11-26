package com.yfuse.feature.detail

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailActionKeyWrapTest {
    @Test
    fun wrapsFourOrMoreKeysOnNarrowPaneOrLargeFontScale() {
        assertTrue(shouldWrapDetailActionKeys(keyCount = 4, maxWidthDp = 320f, fontScale = 1.0f))
        assertTrue(shouldWrapDetailActionKeys(keyCount = 4, maxWidthDp = 420f, fontScale = 1.3f))
        assertFalse(shouldWrapDetailActionKeys(keyCount = 4, maxWidthDp = 420f, fontScale = 1.0f))
        assertFalse(shouldWrapDetailActionKeys(keyCount = 3, maxWidthDp = 300f, fontScale = 1.4f))
    }
}
