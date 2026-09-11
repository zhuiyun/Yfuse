package com.yfuse.core.designsystem

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

/** Apply item motion at the actual lazy root; retained departing nodes can dissolve in place. */
internal fun LazyListScope.motionItem(
    key: Any? = null,
    arrival: Boolean = true,
    contentType: Any? = null,
    content: @Composable LazyItemScope.() -> Unit,
) {
    item(key = key, contentType = contentType) {
        val scope = this
        val identity = key ?: remember { Any() }
        MotionLazyRoot(motionAwareItem().then(if (arrival) Modifier.skeletonArrival(identity) else Modifier)) {
            CompositionLocalProvider(LocalSkeletonArrival provides null) { scope.content() }
        }
    }
}

internal fun <T> LazyListScope.motionItems(
    items: List<T>,
    key: ((T) -> Any)? = null,
    contentType: (T) -> Any? = { null },
    itemContent: @Composable LazyItemScope.(T) -> Unit,
) {
    motionItems(
        count = items.size,
        key = if (key == null) null else { index -> key.invoke(items[index]) },
        contentType = { index -> contentType(items[index]) },
    ) { index -> itemContent(items[index]) }
}

internal fun <T> LazyListScope.motionItemsIndexed(
    items: List<T>,
    key: ((Int, T) -> Any)? = null,
    contentType: (Int, T) -> Any? = { _, _ -> null },
    itemContent: @Composable LazyItemScope.(Int, T) -> Unit,
) {
    motionItems(
        count = items.size,
        key = if (key == null) null else { index -> key.invoke(index, items[index]) },
        contentType = { index -> contentType(index, items[index]) },
    ) { index -> itemContent(index, items[index]) }
}

internal fun LazyListScope.motionItems(
    count: Int,
    key: ((Int) -> Any)? = null,
    contentType: (Int) -> Any? = { null },
    itemContent: @Composable LazyItemScope.(Int) -> Unit,
) {
    items(count = count, key = key, contentType = contentType) { index ->
        val scope = this
        val identity = key?.invoke(index) ?: remember { Any() }
        MotionLazyRoot(motionAwareItem().skeletonArrival(identity)) {
            CompositionLocalProvider(LocalSkeletonArrival provides null) { scope.itemContent(index) }
        }
    }
}

internal fun LazyGridScope.motionItem(
    key: Any? = null,
    span: (LazyGridItemSpanScope.() -> GridItemSpan)? = null,
    contentType: Any? = null,
    content: @Composable LazyGridItemScope.() -> Unit,
) {
    item(key = key, span = span, contentType = contentType) {
        val scope = this
        val identity = key ?: remember { Any() }
        MotionLazyRoot(motionAwareItem().skeletonArrival(identity)) {
            CompositionLocalProvider(LocalSkeletonArrival provides null) { scope.content() }
        }
    }
}

internal fun <T> LazyGridScope.motionItems(
    items: List<T>,
    key: ((T) -> Any)? = null,
    span: (LazyGridItemSpanScope.(T) -> GridItemSpan)? = null,
    contentType: (T) -> Any? = { null },
    itemContent: @Composable LazyGridItemScope.(T) -> Unit,
) {
    motionItems(
        count = items.size,
        key = if (key == null) null else { index -> key.invoke(items[index]) },
        span = if (span == null) null else { index -> span(items[index]) },
        contentType = { index -> contentType(items[index]) },
    ) { index -> itemContent(items[index]) }
}

internal fun <T> LazyGridScope.motionItemsIndexed(
    items: List<T>,
    key: ((Int, T) -> Any)? = null,
    span: (LazyGridItemSpanScope.(Int, T) -> GridItemSpan)? = null,
    contentType: (Int, T) -> Any? = { _, _ -> null },
    itemContent: @Composable LazyGridItemScope.(Int, T) -> Unit,
) {
    motionItems(
        count = items.size,
        key = if (key == null) null else { index -> key.invoke(index, items[index]) },
        span = if (span == null) null else { index -> span(index, items[index]) },
        contentType = { index -> contentType(index, items[index]) },
    ) { index -> itemContent(index, items[index]) }
}

internal fun LazyGridScope.motionItems(
    count: Int,
    key: ((Int) -> Any)? = null,
    span: (LazyGridItemSpanScope.(Int) -> GridItemSpan)? = null,
    contentType: (Int) -> Any? = { null },
    itemContent: @Composable LazyGridItemScope.(Int) -> Unit,
) {
    items(count = count, key = key, span = span, contentType = contentType) { index ->
        val scope = this
        val identity = key?.invoke(index) ?: remember { Any() }
        MotionLazyRoot(motionAwareItem().skeletonArrival(identity)) {
            CompositionLocalProvider(LocalSkeletonArrival provides null) { scope.itemContent(index) }
        }
    }
}

/** Lazy items may emit multiple roots. Preserve their main-axis stacking rather than overlaying them. */
@Composable
private fun MotionLazyRoot(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val horizontal = !constraints.hasBoundedWidth && constraints.hasBoundedHeight
        val children = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = if (horizontal) children.sumOf { it.width } else children.maxOfOrNull { it.width } ?: 0
        val height = if (horizontal) children.maxOfOrNull { it.height } ?: 0 else children.sumOf { it.height }
        layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {
            var cursor = 0
            children.forEach { child ->
                if (horizontal) {
                    child.placeRelative(cursor, 0)
                    cursor += child.width
                } else {
                    child.placeRelative(0, cursor)
                    cursor += child.height
                }
            }
        }
    }
}
