package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import kotlin.reflect.KProperty

/** The playing media keeps its identity when an asynchronously loaded catalog inserts past episodes. */
internal class AndroidQueueCursor(
    initialItemId: String,
    private val items: () -> List<YMediaItem>,
) {
    private var itemId = initialItemId

    operator fun getValue(
        owner: Any?,
        property: KProperty<*>,
    ): Int =
        items().indexOfFirst { it.id == itemId }.also { check(it >= 0) { "Current media was removed from the queue" } }

    operator fun setValue(
        owner: Any?,
        property: KProperty<*>,
        index: Int,
    ) {
        itemId = items()[index].id
    }
}
