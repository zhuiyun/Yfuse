package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidSecureSurfacePolicyTest {
    @Test
    fun protectedContentRequiresSecureOutput() {
        assertFalse(secureSurfaceRequirementSatisfied(protectedContent = true, outputSecure = false))
        assertTrue(secureSurfaceRequirementSatisfied(protectedContent = true, outputSecure = true))
    }

    @Test
    fun clearContentAcceptsEitherOutput() {
        assertTrue(secureSurfaceRequirementSatisfied(protectedContent = false, outputSecure = false))
        assertTrue(secureSurfaceRequirementSatisfied(protectedContent = false, outputSecure = true))
    }
}
