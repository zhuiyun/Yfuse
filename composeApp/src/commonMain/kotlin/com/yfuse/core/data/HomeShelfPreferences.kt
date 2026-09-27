package com.yfuse.core.data

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 首页's shelves in the order the person put them, and the ones they hid. Shelves are named by
 * stable ids, not positions, so a shelf that is empty today keeps its place for tomorrow.
 */
data class HomeShelfLayout(
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
) {
    /**
     * Every shelf in [available] (given in the page's own order), rearranged: those this layout has
     * placed come first, in its order, and any it has never seen follow in the page's order — so a
     * shelf that appears later never lands in the middle of an arrangement someone made.
     */
    fun arranged(available: List<String>): List<String> {
        val present = available.toSet()
        val placed = order.filter { it in present }.distinct()
        return placed + available.filterNot { it in placed }
    }

    /** What the page shows: [arranged] without the hidden shelves. */
    fun visible(available: List<String>): List<String> = arranged(available).filterNot { it in hidden }

    /** [id] moved to position [to] of [arranged]. Saved ids the page does not offer now keep their entries. */
    fun moved(
        available: List<String>,
        id: String,
        to: Int,
    ): HomeShelfLayout {
        val current = arranged(available).toMutableList()
        val from = current.indexOf(id)
        if (from < 0) return this
        current.removeAt(from)
        current.add(to.coerceIn(0, current.size), id)
        return copy(order = current + order.filterNot { it in current })
    }

    fun withVisible(
        id: String,
        visible: Boolean,
    ): HomeShelfLayout = copy(hidden = if (visible) hidden - id else hidden + id)
}

/** Where [HomeShelfLayout] is kept between launches. */
class HomeShelfPreferences(
    private val settings: Settings,
) {
    private companion object {
        const val KEY_ORDER = "home.shelves.order"
        const val KEY_HIDDEN = "home.shelves.hidden"

        /** Ids are ASCII plus a shelf's title, which never holds a line break. */
        const val SEPARATOR = "\n"
    }

    private val _layout = MutableStateFlow(read())
    val layout: StateFlow<HomeShelfLayout> = _layout.asStateFlow()

    fun update(layout: HomeShelfLayout) {
        settings.putString(KEY_ORDER, layout.order.joinToString(SEPARATOR))
        settings.putString(KEY_HIDDEN, layout.hidden.joinToString(SEPARATOR))
        _layout.value = layout
    }

    /** Back to the page's own order with every shelf showing. */
    fun reset() {
        settings.remove(KEY_ORDER)
        settings.remove(KEY_HIDDEN)
        _layout.value = HomeShelfLayout()
    }

    private fun read(): HomeShelfLayout = HomeShelfLayout(order = list(KEY_ORDER), hidden = list(KEY_HIDDEN).toSet())

    private fun list(key: String): List<String> =
        settings
            .getStringOrNull(key)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()
}
