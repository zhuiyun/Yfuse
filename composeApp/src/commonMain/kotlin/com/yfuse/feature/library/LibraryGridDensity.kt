package com.yfuse.feature.library

import com.russhwolf.settings.Settings
import com.yfuse.core.designsystem.rubberBand
import kotlin.math.max
import kotlin.math.roundToInt

// ---------------------------------------------------------------- 捏合换密度 (5.6)

/** The fewest posters a row may have. */
internal const val GRID_MIN_COLUMNS = 2

/** The densest a phone's grid goes; from here the titles make way for the posters. */
internal const val GRID_DENSE_COLUMNS = 5

/** A 2:3 poster is half as tall again as it is wide. */
internal const val GRID_POSTER_ASPECT = 1.5f

/** 2 to 5 columns on a phone; a wider window may go as dense as its adaptive layout already was. */
internal fun gridColumnRange(adaptiveColumns: Int): IntRange =
    GRID_MIN_COLUMNS..max(GRID_DENSE_COLUMNS, adaptiveColumns)

/** How many columns `GridCells.Adaptive(minTile)` lays [available] pixels out in, [spacing] apart. */
internal fun adaptiveGridColumns(
    available: Float,
    minTile: Float,
    spacing: Float,
): Int = if (minTile <= 0f) 1 else ((available + spacing) / (minTile + spacing)).toInt().coerceAtLeast(1)

/** Titles go at five across: under a 60 dp poster a caption is two characters wide. */
internal fun gridShowsTitles(columns: Int): Boolean = columns < GRID_DENSE_COLUMNS

/**
 * The columns a pinch has reached: [start] columns divided by how far the fingers have spread
 * ([zoom] above 1 is spreading, fewer and bigger posters). Past either end of [range] the grid
 * follows ever more reluctantly rather than stopping dead.
 */
internal fun pinchedColumns(
    start: Float,
    zoom: Float,
    range: IntRange,
): Float {
    if (zoom <= 0f || !zoom.isFinite()) return start
    val raw = start / zoom
    val low = range.first.toFloat()
    val high = range.last.toFloat()
    return when {
        raw < low -> low - rubberBand(low - raw, 1f)
        raw > high -> high + rubberBand(raw - high, 1f)
        else -> raw
    }
}

/** The whole column count a pinch shows, and settles on: the nearest in [range]. */
internal fun settledColumns(
    columns: Float,
    range: IntRange,
): Int = if (columns.isFinite()) columns.roundToInt().coerceIn(range) else range.first

/**
 * How much the grid laid out at [shown] columns is scaled while the pinch is at [columns], so its
 * posters are the size [columns] would give them. 1 once the pinch has settled.
 */
internal fun pinchScale(
    shown: Int,
    columns: Float,
): Float = if (columns > 0f) shown / columns else 1f

/** The width of one tile when [available] pixels hold [columns] of them [spacing] apart. */
internal fun gridTileWidth(
    available: Float,
    columns: Int,
    spacing: Float,
): Float = if (columns <= 0) 0f else ((available - spacing * (columns - 1)) / columns).coerceAtLeast(0f)

/**
 * Where the anchor poster's row has to begin, from the top of the grid, for the point [fraction]
 * of the way down that poster to stay at [focusY] once the poster is [height] tall: the poster
 * under the pinch stays under the fingers while every row around it re-flows.
 */
internal fun anchoredRowTop(
    focusY: Float,
    fraction: Float,
    height: Float,
): Float = focusY - fraction.coerceIn(0f, 1f) * height

/** Remembers each library's column count, so a grid opens as dense as it was left. */
class LibraryGridColumnsPreferences(
    private val settings: Settings,
) {
    fun columns(libraryKey: String): Int? =
        settings.getIntOrNull(key(libraryKey))?.takeIf { it in GRID_MIN_COLUMNS..MAX_REMEMBERED_COLUMNS }

    fun setColumns(
        libraryKey: String,
        columns: Int,
    ) {
        settings.putInt(key(libraryKey), columns.coerceIn(GRID_MIN_COLUMNS, MAX_REMEMBERED_COLUMNS))
    }

    private fun key(libraryKey: String): String = "library.gridColumns.v1.${libraryKey.take(MAX_KEY_ID_CHARS)}"

    private companion object {
        const val MAX_KEY_ID_CHARS = 120

        /** Enough for the widest tablet's adaptive grid; anything more is a corrupt value. */
        const val MAX_REMEMBERED_COLUMNS = 12
    }
}
