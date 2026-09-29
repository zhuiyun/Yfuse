package com.yfuse.macrobenchmark

import android.graphics.Rect
import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import java.util.regex.Pattern
import kotlin.math.abs

/*
 * Journeys over server content: 库's grid, 详情 and the player. 首页 has a local fixture, but these
 * pages, their stores and the player all read a real media server, so they run where the
 * benchmark app (the .benchmark package) is signed in to one, as on the physical performance
 * phone, and are skipped, not failed, where it is not, such as the CI emulator.
 */

internal const val SERVER_CONTENT_TIMEOUT_MS = 20_000L
private const val SIGNED_IN =
    "Needs the .benchmark app signed in to a media server with a library; sign in once on this device"

/** The grid page's own total, `128 部` (`12 个` for a directory). Nothing else on 库 reads like it. */
internal val GRID_COUNT: BySelector = By.text(Pattern.compile("\\d+ [部个]"))

/** 详情's play key: 播放, or 继续播放 when there is progress. */
internal val DETAIL_PLAY: BySelector = By.text(Pattern.compile("(继续)?播放"))

/** The personal lists 库 shows above the libraries; their 全部 opens a list rather than a library. */
private val PERSONAL_LISTS: BySelector = By.text(Pattern.compile("我的收藏|稍后观看"))

private const val GRID_MIN_POSTERS = 6

/** 库 → the first library's 全部, and waits until its grid holds a screenful of posters. */
internal fun MacrobenchmarkScope.openLibraryGrid() {
    startProductionApp()
    awaitObject(dockTab("库"), SERVER_CONTENT_TIMEOUT_MS, "Library tab missing").click()
    // Without a server 库 has no 全部 at all; with one, wait for the load before looking past the lists.
    assumeTrue(SIGNED_IN, device.wait(Until.hasObject(By.text("全部")), SERVER_CONTENT_TIMEOUT_MS))
    val seeAll = libraryEntry()
    assumeTrue("Needs a library besides 我的收藏 and 稍后观看", seeAll != null)
    checkNotNull(seeAll).click()
    assertOnScreen("The library grid did not open", device.wait(Until.hasObject(GRID_COUNT), SERVER_CONTENT_TIMEOUT_MS))
    val filled = pollFor(SERVER_CONTENT_TIMEOUT_MS) { libraryGrid()?.takeIf { it.childCount >= GRID_MIN_POSTERS } }
    assumeTrue("Needs a library with at least $GRID_MIN_POSTERS posters", filled != null)
}

/** A 全部 that opens a library: not one on a personal list's header row. Scrolls 库 to find one. */
private fun MacrobenchmarkScope.libraryEntry(): UiObject2? {
    repeat(4) {
        val lists = device.findObjects(PERSONAL_LISTS).map { it.visibleBounds }
        val entry =
            device
                .findObjects(By.text("全部"))
                .sortedBy { it.visibleBounds.top }
                .firstOrNull { candidate -> lists.none { sameRow(it, candidate.visibleBounds) } }
        if (entry != null) return entry
        val page = device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.area() } ?: return null
        val bounds = page.visibleBounds
        device.swipe(
            bounds.centerX(),
            bounds.top + bounds.height() * 4 / 5,
            bounds.centerX(),
            bounds.top + bounds.height() / 3,
            40,
        )
        device.waitForIdle()
    }
    return null
}

/**
 * The poster grid: the largest scrollable on the page, since the genre chips scroll too. Found
 * afresh for every gesture: Compose rebuilds the nodes when the grid re-lays itself out.
 */
internal fun MacrobenchmarkScope.libraryGrid(): UiObject2? =
    device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.area() }

/** Opens 详情 of the grid's first poster and returns once its play key is on screen. */
internal fun MacrobenchmarkScope.openFirstDetail() {
    val grid = libraryGrid() ?: failOnScreen("Library grid missing")
    val poster = grid.children.firstOrNull { it.isClickable } ?: failOnScreen("Library grid has no poster to open")
    poster.click()
    assertOnScreen("详情 did not open", device.wait(Until.hasObject(DETAIL_PLAY), SERVER_CONTENT_TIMEOUT_MS))
}

/** The play key in the page, below the top bar's own 播放 chip that fades in on scroll. */
internal fun MacrobenchmarkScope.detailPlayKey(): UiObject2 =
    device.findObjects(DETAIL_PLAY).maxByOrNull { it.visibleBounds.top } ?: failOnScreen("详情 play key missing")

private fun sameRow(
    a: Rect,
    b: Rect,
): Boolean = abs(a.centerY() - b.centerY()) < maxOf(a.height(), b.height())

private fun Rect.area(): Int = width() * height()

private inline fun <T : Any> pollFor(
    timeoutMs: Long,
    probe: () -> T?,
): T? {
    val end = SystemClock.uptimeMillis() + timeoutMs
    while (true) {
        probe()?.let { return it }
        if (SystemClock.uptimeMillis() >= end) return null
        SystemClock.sleep(250L)
    }
}
