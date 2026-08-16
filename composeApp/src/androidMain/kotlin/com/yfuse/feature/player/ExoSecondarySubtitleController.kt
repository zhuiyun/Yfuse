package com.yfuse.feature.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.hls.HlsTrackMetadataEntry
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.yfuse.core.logging.AppLog
import kotlin.math.abs

/** Stable-enough identity for finding the same subtitle in the text-only secondary player. */
@UnstableApi
internal data class ExoSubtitleTrackIdentity(
    val id: String?,
    val language: String?,
    val label: String?,
    val sampleMimeType: String?,
    val codecs: String?,
    val roleFlags: Int,
    val selectionFlags: Int,
    val renditionGroupId: String?,
    val renditionName: String?,
) {
    fun matches(format: Format): Boolean {
        val other = format.subtitleTrackIdentity()
        if (!renditionGroupId.isNullOrBlank() && !renditionName.isNullOrBlank()) {
            return renditionGroupId == other.renditionGroupId && renditionName == other.renditionName
        }
        if (!id.isNullOrBlank() && id == other.id) return true
        if (language != null && other.language != null && language != other.language) return false
        if (label != null && other.label != null && label != other.label) return false
        if (sampleMimeType != null && other.sampleMimeType != null && sampleMimeType != other.sampleMimeType) {
            return false
        }
        if (codecs != null && other.codecs != null && codecs != other.codecs) return false
        if (roleFlags != other.roleFlags || selectionFlags != other.selectionFlags) return false
        return listOf(language, label, sampleMimeType, codecs).count { it != null } >= 2
    }
}

@UnstableApi
internal fun Format.subtitleTrackIdentity(): ExoSubtitleTrackIdentity {
    var renditionGroupId: String? = null
    var renditionName: String? = null
    val entries = metadata
    if (entries != null) {
        for (index in 0 until entries.length()) {
            val rendition = entries[index] as? HlsTrackMetadataEntry ?: continue
            renditionGroupId = rendition.groupId?.takeIf(String::isNotBlank)
            renditionName = rendition.name?.takeIf(String::isNotBlank)
            if (renditionGroupId != null && renditionName != null) break
        }
    }
    return ExoSubtitleTrackIdentity(
        id = id?.takeIf(String::isNotBlank),
        language = language?.takeIf(String::isNotBlank),
        label = label?.takeIf(String::isNotBlank),
        sampleMimeType = sampleMimeType?.takeIf(String::isNotBlank),
        codecs = codecs?.takeIf(String::isNotBlank),
        roleFlags = roleFlags,
        selectionFlags = selectionFlags,
        renditionGroupId = renditionGroupId,
        renditionName = renditionName,
    )
}

/** A renderer factory that intentionally decodes text only: no second audio/video pipeline. */
@UnstableApi
private class ExoSecondaryTextRenderersFactory(
    private val secondaryOutput: TextOutput,
) : RenderersFactory {
    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput,
    ): Array<Renderer> {
        val fanOut =
            TextOutput { cues ->
                textRendererOutput.onCues(cues)
                secondaryOutput.onCues(cues)
            }
        return arrayOf(TextRenderer(fanOut, eventHandler.looper))
    }
}

/**
 * Runs a second ExoPlayer with only a TextRenderer. It parses the same media item as the main player
 * but never creates audio/video renderers, so dual subtitles are real independent track selections
 * without decoding the picture twice.
 */
@UnstableApi
internal class ExoSecondarySubtitleController(
    context: Context,
    customUserAgent: String,
    private val cueMerger: ExoDualSubtitleCueMerger,
) {
    private var desiredTrack: ExoSubtitleTrackIdentity? = null
    private var prepared = false
    private var enabled = false

    val needsReconciliation: Boolean
        get() = enabled && prepared

    private val httpFactory =
        DefaultHttpDataSource
            .Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)
            .apply {
                customUserAgent.trim().takeIf(String::isNotEmpty)?.let { value ->
                    setDefaultRequestProperties(mapOf("User-Agent" to value))
                }
            }
    private val mediaSourceFactory =
        DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory))
    private val player =
        ExoPlayer
            .Builder(
                context,
                ExoSecondaryTextRenderersFactory(cueMerger.secondaryOutput()),
            ).setMediaSourceFactory(mediaSourceFactory)
            .build()

    private val listener =
        object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                applyDesiredTrack(tracks)
            }

            override fun onPlayerError(error: PlaybackException) {
                cueMerger.clearSecondary()
                AppLog.warning(
                    category = "player.exo.secondary_subtitle",
                    event = "secondary_subtitle_failed",
                    message = "Secondary ExoPlayer subtitle pipeline failed",
                    throwable = error,
                )
            }
        }

    init {
        player.addListener(listener)
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
    }

    fun select(
        identity: ExoSubtitleTrackIdentity,
        mediaItems: List<MediaItem>,
        currentIndex: Int,
        positionMs: Long,
        speed: Float,
        playWhenReady: Boolean,
    ): Boolean {
        if (mediaItems.isEmpty()) return false
        desiredTrack = identity
        enabled = true
        val safeIndex = currentIndex.coerceIn(0, mediaItems.lastIndex)
        val safePositionMs = positionMs.coerceAtLeast(0L)
        if (player.mediaItemCount == 0 || player.mediaItemCount != mediaItems.size) {
            player.setMediaItems(mediaItems, safeIndex, safePositionMs)
            prepared = false
        } else if (player.currentMediaItemIndex != safeIndex) {
            player.seekTo(safeIndex, safePositionMs)
        }
        if (!prepared) {
            player.prepare()
            prepared = true
        }
        player.setPlaybackSpeed(speed)
        player.playWhenReady = playWhenReady
        applyDesiredTrack(player.currentTracks)
        return true
    }

    fun disable() {
        desiredTrack = null
        enabled = false
        player.playWhenReady = false
        player.stop()
        prepared = false
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        cueMerger.clearSecondary()
    }

    fun reconcile(
        mainIndex: Int,
        mainPositionMs: Long,
        mainSpeed: Float,
        mainPlayWhenReady: Boolean,
    ) {
        if (!enabled || !prepared) return
        val safePositionMs = mainPositionMs.coerceAtLeast(0L)
        if (player.currentMediaItemIndex != mainIndex) {
            player.seekTo(mainIndex, safePositionMs)
        } else if (abs(player.currentPosition - mainPositionMs) > SECONDARY_DRIFT_TOLERANCE_MS) {
            player.seekTo(safePositionMs)
        }
        if (abs(player.playbackParameters.speed - mainSpeed) > PLAYBACK_SPEED_TOLERANCE) {
            player.setPlaybackSpeed(mainSpeed)
        }
        player.playWhenReady = mainPlayWhenReady
    }

    fun replaceMediaItem(
        index: Int,
        mediaItem: MediaItem,
    ) {
        if (player.mediaItemCount <= index) return
        val currentPosition = player.currentPosition
        val wasCurrent = player.currentMediaItemIndex == index
        player.replaceMediaItem(index, mediaItem)
        if (enabled && wasCurrent) {
            player.seekTo(index, currentPosition)
            player.prepare()
            prepared = true
        }
    }

    fun appendMediaItems(mediaItems: List<MediaItem>) {
        if (mediaItems.isEmpty() || player.mediaItemCount == 0) return
        player.addMediaItems(mediaItems)
    }

    fun release() {
        player.removeListener(listener)
        player.release()
        cueMerger.clearSecondary()
    }

    private fun applyDesiredTrack(tracks: Tracks) {
        val identity = desiredTrack ?: return
        tracks.groups.withIndex().forEach { (groupIndex, group) ->
            if (group.type != C.TRACK_TYPE_TEXT) return@forEach
            for (trackIndex in 0 until group.length) {
                if (!identity.matches(group.getTrackFormat(trackIndex))) continue
                if (group.isTrackSelected(trackIndex)) return
                player.trackSelectionParameters =
                    player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(
                            TrackSelectionOverride(group.mediaTrackGroup, trackIndex),
                        ).build()
                AppLog.info(
                    category = "player.exo.secondary_subtitle",
                    event = "secondary_subtitle_selected",
                    message = "Secondary subtitle track was selected",
                    attributes =
                        mapOf(
                            "groupIndex" to groupIndex.toString(),
                            "trackIndex" to trackIndex.toString(),
                            "language" to identity.language.orEmpty(),
                            "label" to identity.label.orEmpty(),
                        ),
                )
                return
            }
        }
        cueMerger.clearSecondary()
    }
}

private const val SECONDARY_DRIFT_TOLERANCE_MS = 350L
private const val PLAYBACK_SPEED_TOLERANCE = 0.001f
