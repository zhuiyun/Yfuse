package com.yfuse.core2.api

import kotlinx.coroutines.CancellationException

/** Stable media-pipeline stage used by diagnostics and Core2's own failure ledger. */
enum class YPlaybackFailureStage {
    SourceOpen,
    Demux,
    Bitstream,
    VideoDecoderConfigure,
    VideoDecoderQueue,
    VideoRenderer,
    AudioDecoderConfigure,
    AudioDecoderQueue,
    AudioRenderer,
    Seek,
    Unknown,
}

/**
 * Machine-readable Core2 pipeline failure.
 *
 * User-visible code should render its own localized message rather than exposing [cause]. Network
 * URLs, headers and provider tokens must never be embedded into [safeDetail].
 */
class YPlaybackException(
    val category: YPlaybackFailureCategory,
    val stage: YPlaybackFailureStage,
    val safeDetail: String? = null,
    cause: Throwable? = null,
) : RuntimeException(
        buildString {
            append("YCore2 ")
            append(stage.name)
            append(" failure")
            safeDetail?.takeIf(String::isNotBlank)?.let {
                append(": ")
                append(it)
            }
        },
        cause,
    )

inline fun <T> yPlaybackStage(
    category: YPlaybackFailureCategory,
    stage: YPlaybackFailureStage,
    safeDetail: String? = null,
    block: () -> T,
): T =
    try {
        block()
    } catch (failure: YPlaybackException) {
        throw failure
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        throw YPlaybackException(
            category = category,
            stage = stage,
            safeDetail = safeDetail,
            cause = failure,
        )
    }
