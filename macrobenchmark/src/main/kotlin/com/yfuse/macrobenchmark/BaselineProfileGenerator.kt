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
    fun generate() =
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()
            repeat(2) {
                device.swipe(
                    device.displayWidth / 2,
                    device.displayHeight * 3 / 4,
                    device.displayWidth / 2,
                    device.displayHeight / 4,
                    SWIPE_STEPS,
                )
                device.waitForIdle()
            }
        }

    private companion object {
        const val TARGET_PACKAGE = "com.yfuse"
        const val SWIPE_STEPS = 18
    }
}
