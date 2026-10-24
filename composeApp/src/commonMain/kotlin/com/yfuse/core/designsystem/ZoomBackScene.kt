package com.yfuse.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

/** Keep a side preview inside the current scene; the gesture controller owns its animation. */
internal class ZoomBackSceneStrategy<T : Any>(
    private val host: ZoomBackNavHost,
) : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T> {
        val top = entries.last()
        val preview = host.controller.active && host.pageKey == top.contentKey
        return ZoomBackScene(
            key = top.contentKey,
            entries = entries.takeLast(if (preview) 2 else 1),
            previousEntries = entries.dropLast(1),
        )
    }
}

/**
 * The scene key and the top entry's composition key survive preview and cancellation. The
 * underlying entry can therefore appear without restarting the detail or its shared poster.
 * Navigation still removes exactly one entry when the controller commits the side slide.
 */
private data class ZoomBackScene<T : Any>(
    override val key: Any,
    override val entries: List<NavEntry<T>>,
    override val previousEntries: List<NavEntry<T>>,
) : Scene<T> {
    override val content: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            entries.forEach { entry ->
                key(entry.contentKey) { entry.Content() }
            }
        }
    }
}
