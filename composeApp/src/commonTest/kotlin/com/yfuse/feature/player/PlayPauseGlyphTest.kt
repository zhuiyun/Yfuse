package com.yfuse.feature.player

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import com.yfuse.core.designsystem.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

/** The morph must start and end on the icons it replaces, or the key would jump when it settles. */
class PlayPauseGlyphTest {
    private fun ImageVector.onlyPath(): VectorPath = root.filterIsInstance<VectorPath>().single()

    private fun assertCorners(
        expected: List<Float>,
        actual: FloatArray,
    ) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertEquals(expected[it], actual[it], 0.001f, "coordinate $it") }
    }

    @Test
    fun startsAsThePlayTriangleCutDownItsMiddle() {
        val path = AppIcons.Play.onlyPath()
        val (a, b, c) =
            path.pathData.mapNotNull {
                when (it) {
                    is PathNode.MoveTo -> it.x to it.y
                    is PathNode.LineTo -> it.x to it.y
                    else -> null
                }
            }
        val top = (a.first + b.first) / 2f to (a.second + b.second) / 2f
        val bottom = (b.first + c.first) / 2f to (b.second + c.second) / 2f
        val expected = listOf(a, top, bottom, c, top, b, b, bottom).flatMap { listOf(it.first, it.second) }

        assertCorners(expected, playPauseCorners(0f))
        assertEquals(path.strokeLineWidth, playPauseStroke(0f), 0.001f)
    }

    @Test
    fun endsAsThePauseBarsInsideTheirRoundedCorners() {
        val path = AppIcons.Pause.onlyPath()
        val bars = mutableListOf<MutableList<PathNode>>()
        for (node in path.pathData) {
            if (node is PathNode.MoveTo) bars.add(mutableListOf(node)) else bars.last().add(node)
        }
        val corner = bars.first().filterIsInstance<PathNode.RelativeArcTo>().first()
        val radius = corner.horizontalEllipseRadius
        val expected =
            bars.flatMap { nodes ->
                // A rounded bar starts where its top-left corner ends, then runs right and down.
                val start = nodes.filterIsInstance<PathNode.MoveTo>().single()
                val right = nodes.filterIsInstance<PathNode.HorizontalTo>().first().x
                val bottom = nodes.filterIsInstance<PathNode.VerticalTo>().first().y
                val top = start.y + radius
                listOf(start.x, top, right, top, right, bottom, start.x, bottom)
            }

        assertEquals(2, bars.size)
        assertCorners(expected, playPauseCorners(1f))
        assertEquals(path.strokeLineWidth + 2 * radius, playPauseStroke(1f), 0.001f)
    }

    @Test
    fun movesEveryCornerInAStraightLine() {
        val start = playPauseCorners(0f)
        val end = playPauseCorners(1f)
        val middle = playPauseCorners(0.5f)
        middle.indices.forEach { assertEquals((start[it] + end[it]) / 2f, middle[it], 0.001f) }
        assertCorners(end.toList(), playPauseCorners(1.4f))
    }
}
