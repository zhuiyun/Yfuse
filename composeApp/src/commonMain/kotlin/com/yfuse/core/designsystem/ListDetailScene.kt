package com.yfuse.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

internal val LocalInListPane = compositionLocalOf { false }

/** Keep the nearest browsing destination alongside its selected child in the same NavDisplay. */
internal class ListDetailSceneStrategy<T : Any>(
    private val listKeys: Set<Any>,
) : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T> {
        val top = entries.last()
        val source = entries.dropLast(1).lastOrNull { it.contentKey in listKeys }
        return ListDetailScene(listOfNotNull(source, top), entries.dropLast(1))
    }
}

private data class ListDetailScene<T : Any>(
    override val entries: List<NavEntry<T>>,
    override val previousEntries: List<NavEntry<T>>,
) : Scene<T> {
    override val key: Any = entries.first().contentKey
    override val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxSize()) {
            entries.forEachIndexed { index, entry ->
                key(entry.contentKey) {
                    if (index == 1) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(LocalPalette.current.border))
                    }
                    val fraction = if (index == 0) 0.45f else 0.55f
                    Box(Modifier.weight(fraction).fillMaxHeight().clipToBounds()) {
                        CompositionLocalProvider(LocalInListPane provides (entries.size > 1 && index == 0)) {
                            entry.Content()
                        }
                    }
                }
            }
        }
    }
}
