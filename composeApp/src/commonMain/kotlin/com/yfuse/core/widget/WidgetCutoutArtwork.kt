package com.yfuse.core.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow

/**
 * An aligned, bounded copy of the source and its foreground in the same pixel coordinates.
 *
 * Both layers must use the same crop and transform. Moving only the foreground would expose the
 * subject still present in [backgroundModel]. Bounds are fractions of the uncropped source.
 */
data class WidgetCutoutArtwork(
    val backgroundModel: String,
    val foregroundModel: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val subjectTop: Float,
    val subjectBottom: Float,
)

/** Image-local progress and failure state; absence of artwork alone does not explain a fallback. */
enum class WidgetCutoutPhase {
    Idle,
    Disabled,
    NeedsKey,
    Queued,
    Downloading,
    Processing,
    Ready,
    Rejected,
    Failed,
    Limited,
    NoCredits,
    InvalidKey,
}

data class WidgetCutoutStatus(
    val phase: WidgetCutoutPhase = WidgetCutoutPhase.Idle,
    val message: String = "",
)

data class WidgetCutoutViewState(
    val artwork: WidgetCutoutArtwork? = null,
    val status: WidgetCutoutStatus = WidgetCutoutStatus(),
    val retry: (() -> Unit)? = null,
)

internal interface WidgetCutoutLoader {
    fun observe(source: String): StateFlow<WidgetCutoutStatus>

    suspend fun get(
        source: String,
        allowProcessing: Boolean,
    ): WidgetCutoutArtwork?

    fun retry(source: String) {}
}

internal data class WidgetCutoutRuntime(
    val configuration: StateFlow<WidgetCutoutConfiguration>,
    val loader: WidgetCutoutLoader,
)

internal val LocalWidgetCutoutRuntime = staticCompositionLocalOf<WidgetCutoutRuntime?> { null }

@Composable
expect fun rememberWidgetCutoutState(
    posterUrl: String?,
    enabled: Boolean,
): WidgetCutoutViewState

@Composable
fun rememberWidgetCutoutArtwork(
    posterUrl: String?,
    enabled: Boolean,
): WidgetCutoutArtwork? = rememberWidgetCutoutState(posterUrl, enabled).artwork
