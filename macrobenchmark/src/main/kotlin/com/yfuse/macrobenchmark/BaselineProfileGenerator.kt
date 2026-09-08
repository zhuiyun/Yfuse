package com.yfuse.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Records cold-start and first-scroll hot paths for ProfileInstaller-backed production builds. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() =
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startProductionApp()
        }

    @Test
    fun homeJourney() =
        baselineProfileRule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = false) {
            startHomeFixture()
            scrollHomeJourney()
        }
}
