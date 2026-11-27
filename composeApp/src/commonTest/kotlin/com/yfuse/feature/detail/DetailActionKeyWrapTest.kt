package com.yfuse.feature.detail

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailActionKeyWrapTest {
    @Test
    fun keepsFiveCircularKeysSingleRowOnStandardPhonesAndWrapsOnlyExtremeCases() {
        assertFalse(shouldWrapDetailActionKeys(keyCount = 5, maxWidthDp = 328f, fontScale = 1.0f))
        assertFalse(shouldWrapDetailActionKeys(keyCount = 4, maxWidthDp = 300f, fontScale = 1.3f))
        assertTrue(shouldWrapDetailActionKeys(keyCount = 5, maxWidthDp = 260f, fontScale = 1.0f))
        assertTrue(shouldWrapDetailActionKeys(keyCount = 5, maxWidthDp = 420f, fontScale = 1.5f))
    }
}
