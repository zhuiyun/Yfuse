package com.yfuse.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the hot paths of cold start and of every measured journey for ProfileInstaller-backed
 * production builds. The server-content journeys are skipped where the benchmark app has no
 * signed-in server (see ServerContentJourney.kt), so a profile from such a device covers start,
 * 首页, navigation and 浮起菜单 only.
 */
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

    @Test
    fun navigationJourney() =
        baselineProfileRule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = false) {
            startProductionApp()
            navigateSearchJourney()
        }

    @Test
    fun liftMenu() =
        baselineProfileRule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = false) {
            startHomeFixture()
            liftPosterInView()
            liftMenuJourney()
        }

    @Test
    fun libraryGridPinch() =
        baselineProfileRule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = false) {
            killProcess()
            openLibraryGrid()
            pinchGridDensityJourney()
        }

    @Test
    fun libraryGridFling() =
        baselineProfileRule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = false) {
            killProcess()
            openLibraryGrid()
            flingGridJourney()
        }

    @Test
    fun detailPullBack() =
        baselineProfileRule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = false) {
            killProcess()
            openLibraryGrid()
            detailPullBackJourney()
        }

    @Test
    fun playerEnterExit() =
        baselineProfileRule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = false) {
            killProcess()
            openLibraryGrid()
            openFirstDetail()
            playerEnterExitJourney()
        }

    @Test
    fun playerScrub() =
        baselineProfileRule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = false) {
            killProcess()
            openLibraryGrid()
            openFirstDetail()
            enterPlayer()
            playerScrubJourney()
        }
}
