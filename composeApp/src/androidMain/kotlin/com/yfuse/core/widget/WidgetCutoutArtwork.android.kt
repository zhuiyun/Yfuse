package com.yfuse.core.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.designsystem.LocalRouteVisible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.net.URI

private val trustedPreviewPosters =
    setOf(
        "file:///android_asset/widget-preview/space.png",
        "file:///android_asset/widget-preview/rain.png",
        "file:///android_asset/widget-preview/breakout-astronaut.png",
    )

@Composable
actual fun rememberWidgetCutoutState(
    posterUrl: String?,
    enabled: Boolean,
): WidgetCutoutViewState {
    val context = LocalContext.current.applicationContext
    val visible = LocalRouteVisible.current
    val runtime =
        LocalWidgetCutoutRuntime.current ?: remember(context) {
            WidgetCutoutRuntime(
                widgetCutoutPreferences().configuration,
                object : WidgetCutoutLoader {
                    override fun peek(source: String) = PoofWidgetCutoutStore.pipeline(context).peek(source)

                    override fun observe(source: String) = PoofWidgetCutoutStore.pipeline(context).observe(source)

                    override suspend fun get(
                        source: String,
                        allowProcessing: Boolean,
                    ) = WidgetCutoutStore.get(context, source, allowProcessing)

                    override fun retry(source: String) = PoofWidgetCutoutStore.pipeline(context).retry(source)
                },
            )
        }
    val configuration by runtime.configuration.collectAsState()
    val source = remember(posterUrl) { safeCutoutSource(posterUrl) }
    val observed = remember(source, runtime) { runtime.loader.observe(source.orEmpty()) }
    val status by observed.collectAsState()
    var attempt by remember(source) { mutableIntStateOf(0) }
    var artwork by remember(source, runtime.loader, configuration.enabled) {
        mutableStateOf(
            precomputedPreviewCutout(source)
                ?: source?.takeIf { configuration.enabled }?.let(runtime.loader::peek),
        )
    }
    LaunchedEffect(source, enabled, visible, configuration, attempt) {
        logWidgetCutout(
            "card_request",
            posterUrl,
            mapOf(
                "validSource" to (source != null).toString(),
                "selected" to enabled.toString(),
                "visible" to visible.toString(),
                "enabled" to configuration.enabled.toString(),
                "configured" to configuration.configured.toString(),
            ),
        )
        if (source == null || !visible) {
            logWidgetCutout(
                "card_request_skipped",
                posterUrl,
                mapOf(
                    "reason" to if (source == null) "invalid_or_missing_source" else "route_not_visible",
                ),
            )
            return@LaunchedEffect
        }
        try {
            artwork = precomputedPreviewCutout(source) ?: runtime.loader.get(source, allowProcessing = false)
            if (artwork == null && enabled && configuration.enabled && configuration.configured) {
                delay(450)
                artwork = runtime.loader.get(source, allowProcessing = true)
            }
            logWidgetCutout("card_request_finished", source, mapOf("hasArtwork" to (artwork != null).toString()))
        } catch (cancelled: CancellationException) {
            logWidgetCutout("card_request_cancelled", source)
            throw cancelled
        }
    }
    return WidgetCutoutViewState(
        artwork,
        when {
            artwork != null -> WidgetCutoutStatus(WidgetCutoutPhase.Ready, "抠图完成")
            !configuration.enabled -> WidgetCutoutStatus(WidgetCutoutPhase.Disabled, "海报抠图已关闭")
            !configuration.configured -> WidgetCutoutStatus(WidgetCutoutPhase.NeedsKey, "先配置 Poof Key")
            source == null -> WidgetCutoutStatus(WidgetCutoutPhase.Failed, "没有可处理的海报")
            else -> status
        },
        retry =
            source?.let {
                {
                    runtime.loader.retry(it)
                    attempt++
                }
            },
    )
}

/** Reviewed, precomputed artwork; the app only reads the aligned PNG pair. */
private fun precomputedPreviewCutout(source: String?): WidgetCutoutArtwork? =
    if (source == "file:///android_asset/widget-preview/breakout-astronaut.png") {
        WidgetCutoutArtwork(
            backgroundModel = source,
            foregroundModel = "file:///android_asset/widget-cutout/preview-astronaut.png",
            sourceWidth = 512,
            sourceHeight = 768,
            subjectTop = .153125f,
            subjectBottom = .815625f,
        )
    } else {
        null
    }

/** Never allow an arbitrary file/content URI to enter the image loader through widget data. */
private fun safeCutoutSource(value: String?): String? {
    val source = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 8192 } ?: return null
    if (source in trustedPreviewPosters) return source
    return try {
        val uri = URI(source)
        source.takeIf { uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() }
    } catch (_: Exception) {
        null
    }
}

/** One bounded pipeline for both phone and TV. It shares the application's Coil/network policy. */
internal object WidgetCutoutStore {
    suspend fun get(
        context: Context,
        source: String,
        allowProcessing: Boolean = true,
    ): WidgetCutoutArtwork? {
        val safeSource = safeCutoutSource(source) ?: return null
        return try {
            if (safeSource in trustedPreviewPosters) {
                precomputedPreviewCutout(safeSource)
            } else {
                PoofWidgetCutoutStore.get(context, safeSource, allowProcessing)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }
}
