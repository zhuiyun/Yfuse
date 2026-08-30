package com.yfuse.tv.integration

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import com.yfuse.core.logging.AppLog

internal data class TvProgramRecord(
    val internalProviderId: String,
    val mediaType: ContinueWatchingMediaType,
    val title: String,
    val description: String?,
    val posterArtUri: String?,
    val playbackUri: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastEngagementEpochMs: Long,
)

internal fun ContinueWatchingEntry.toTvProgramRecord(): TvProgramRecord =
    TvProgramRecord(
        internalProviderId = identity.platformId,
        mediaType = mediaType,
        title = title.take(MAX_TITLE_CHARS),
        description =
            listOfNotNull(subtitle, description)
                .joinToString(" · ")
                .take(MAX_DESCRIPTION_CHARS)
                .takeIf(String::isNotBlank),
        posterArtUri = sanitizeTvArtworkUri(posterArtUri),
        playbackUri = TvPlaybackDeepLinkCodec.encode(identity, positionMs),
        positionMs = positionMs.coerceAtLeast(0L),
        durationMs = durationMs.coerceAtLeast(0L),
        lastEngagementEpochMs = lastEngagementEpochMs.coerceAtLeast(0L),
    )

/** API 26+ Watch Next upsert/update/delete built directly on Android's TvProvider contract. */
internal class WatchNextProgramPublisher(
    private val context: Context,
) {
    private val resolver: ContentResolver = context.contentResolver

    fun upsert(record: TvProgramRecord): TvProviderWriteResult =
        providerCall {
            val values = record.watchNextValues()
            val existing = rowIds(TvContract.WatchNextPrograms.CONTENT_URI, record.internalProviderId)
            if (existing.isEmpty()) {
                val inserted = resolver.insert(TvContract.WatchNextPrograms.CONTENT_URI, values)
                requireNotNull(inserted) { "TvProvider rejected Watch Next insert" }
            } else {
                val primary = ContentUris.withAppendedId(TvContract.WatchNextPrograms.CONTENT_URI, existing.first())
                check(resolver.update(primary, values, null, null) == 1) {
                    "TvProvider rejected Watch Next update"
                }
                existing.drop(1).forEach { duplicate ->
                    resolver.delete(
                        ContentUris.withAppendedId(TvContract.WatchNextPrograms.CONTENT_URI, duplicate),
                        null,
                        null,
                    )
                }
            }
        }

    fun delete(internalProviderId: String): TvProviderWriteResult =
        providerCall {
            rowIds(TvContract.WatchNextPrograms.CONTENT_URI, internalProviderId).forEach { rowId ->
                resolver.delete(
                    ContentUris.withAppendedId(TvContract.WatchNextPrograms.CONTENT_URI, rowId),
                    null,
                    null,
                )
            }
        }

    private fun TvProgramRecord.watchNextValues(): ContentValues =
        ContentValues().apply {
            put(COLUMN_INTERNAL_PROVIDER_ID, internalProviderId)
            put(COLUMN_TYPE, mediaType.programType)
            put(COLUMN_WATCH_NEXT_TYPE, WATCH_NEXT_TYPE_CONTINUE)
            put(COLUMN_TITLE, title)
            description?.let { put(COLUMN_SHORT_DESCRIPTION, it) }
            posterArtUri?.let { put(COLUMN_POSTER_ART_URI, it) }
            put(COLUMN_INTENT_URI, playbackUri)
            if (durationMs > 0L) put(COLUMN_DURATION_MILLIS, durationMs)
            put(COLUMN_LAST_PLAYBACK_POSITION_MILLIS, positionMs)
            put(COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, lastEngagementEpochMs)
        }

    private fun rowIds(
        contentUri: Uri,
        internalProviderId: String,
    ): List<Long> {
        val result = mutableListOf<Long>()
        resolver
            .query(
                contentUri,
                arrayOf(COLUMN_ID, COLUMN_INTERNAL_PROVIDER_ID),
                null,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(COLUMN_ID)
                val providerIdColumn = cursor.getColumnIndexOrThrow(COLUMN_INTERNAL_PROVIDER_ID)
                while (cursor.moveToNext()) {
                    if (cursor.getString(providerIdColumn) == internalProviderId) {
                        result += cursor.getLong(idColumn)
                    }
                }
            }
        return result
    }
}

/** A single optional Yfuse home-screen channel, mirroring the same five safe continuation cards. */
internal class PreviewChannelPublisher(
    private val context: Context,
) {
    private val resolver = context.contentResolver

    fun replace(
        entries: List<TvProgramRecord>,
        previouslyPublishedIds: Set<String>,
    ): TvProviderWriteResult =
        providerCall {
            val channelId = findChannelId() ?: createChannel()
            entries.forEach { upsertProgram(channelId, it).requireSuccess() }
            val desired = entries.mapTo(linkedSetOf(), TvProgramRecord::internalProviderId)
            (previouslyPublishedIds - desired).forEach { deleteProgram(it).requireSuccess() }
        }

    /** Must only be called from an Activity after the user explicitly asks to add the channel. */
    fun requestBrowsableFromForeground(): TvProviderWriteResult =
        providerCall {
            val channelId = findChannelId() ?: createChannel()
            TvContract.requestChannelBrowsable(context, channelId)
        }

    private fun findChannelId(): Long? {
        resolver
            .query(
                TvContract.Channels.CONTENT_URI,
                arrayOf(COLUMN_ID, COLUMN_INTERNAL_PROVIDER_ID),
                null,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(COLUMN_ID)
                val providerIdColumn = cursor.getColumnIndexOrThrow(COLUMN_INTERNAL_PROVIDER_ID)
                while (cursor.moveToNext()) {
                    if (cursor.getString(providerIdColumn) == CHANNEL_INTERNAL_PROVIDER_ID) {
                        return cursor.getLong(idColumn)
                    }
                }
            }
        return null
    }

    private fun createChannel(): Long {
        val appLink =
            context.packageManager
                .getLeanbackLaunchIntentForPackage(context.packageName)
                ?.toUri(Intent.URI_INTENT_SCHEME)
                ?: "yfuse://tv/home"
        val values =
            ContentValues().apply {
                put(COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW)
                put(COLUMN_DISPLAY_NAME, CHANNEL_DISPLAY_NAME)
                put(COLUMN_DESCRIPTION, CHANNEL_DESCRIPTION)
                put(COLUMN_APP_LINK_INTENT_URI, appLink)
                put(COLUMN_INTERNAL_PROVIDER_ID, CHANNEL_INTERNAL_PROVIDER_ID)
            }
        val uri = requireNotNull(resolver.insert(TvContract.Channels.CONTENT_URI, values)) {
            "TvProvider rejected Preview Channel insert"
        }
        val channelId = ContentUris.parseId(uri)
        writeApplicationLogo(channelId)
        return channelId
    }

    private fun writeApplicationLogo(channelId: Long) {
        runCatching {
            val drawable = context.applicationInfo.loadIcon(context.packageManager)
            val pixels = (80f * context.resources.displayMetrics.density).toInt().coerceAtLeast(80)
            val bitmap =
                if (drawable is BitmapDrawable && drawable.bitmap.width == pixels && drawable.bitmap.height == pixels) {
                    drawable.bitmap
                } else {
                    Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888).also { target ->
                        val canvas = Canvas(target)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                    }
                }
            resolver.openOutputStream(TvContract.buildChannelLogoUri(channelId)).use { output ->
                requireNotNull(output)
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        }.onFailure { error ->
            AppLog.warning(
                category = "tv.preview_channel",
                event = "logo_write_failed",
                message = "Preview Channel was created but its application logo could not be stored",
                throwable = error,
            )
        }
    }

    private fun upsertProgram(
        channelId: Long,
        record: TvProgramRecord,
    ): TvProviderWriteResult =
        providerCall {
            val values = record.previewValues(channelId)
            val existing = previewProgramIds(record.internalProviderId)
            if (existing.isEmpty()) {
                requireNotNull(resolver.insert(TvContract.PreviewPrograms.CONTENT_URI, values)) {
                    "TvProvider rejected Preview Program insert"
                }
            } else {
                check(
                    resolver.update(
                        ContentUris.withAppendedId(TvContract.PreviewPrograms.CONTENT_URI, existing.first()),
                        values,
                        null,
                        null,
                    ) == 1,
                ) { "TvProvider rejected Preview Program update" }
                existing.drop(1).forEach { duplicate ->
                    resolver.delete(
                        ContentUris.withAppendedId(TvContract.PreviewPrograms.CONTENT_URI, duplicate),
                        null,
                        null,
                    )
                }
            }
        }

    private fun deleteProgram(internalProviderId: String): TvProviderWriteResult =
        providerCall {
            previewProgramIds(internalProviderId).forEach { rowId ->
                resolver.delete(
                    ContentUris.withAppendedId(TvContract.PreviewPrograms.CONTENT_URI, rowId),
                    null,
                    null,
                )
            }
        }

    private fun previewProgramIds(internalProviderId: String): List<Long> {
        val ids = mutableListOf<Long>()
        resolver
            .query(
                TvContract.PreviewPrograms.CONTENT_URI,
                arrayOf(COLUMN_ID, COLUMN_INTERNAL_PROVIDER_ID),
                null,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(COLUMN_ID)
                val providerIdColumn = cursor.getColumnIndexOrThrow(COLUMN_INTERNAL_PROVIDER_ID)
                while (cursor.moveToNext()) {
                    if (cursor.getString(providerIdColumn) == internalProviderId) {
                        ids += cursor.getLong(idColumn)
                    }
                }
            }
        return ids
    }

    private fun TvProgramRecord.previewValues(channelId: Long): ContentValues =
        ContentValues().apply {
            put(COLUMN_CHANNEL_ID, channelId)
            put(COLUMN_INTERNAL_PROVIDER_ID, internalProviderId)
            put(COLUMN_TYPE, mediaType.programType)
            put(COLUMN_TITLE, title)
            description?.let { put(COLUMN_SHORT_DESCRIPTION, it) }
            posterArtUri?.let { put(COLUMN_POSTER_ART_URI, it) }
            put(COLUMN_INTENT_URI, playbackUri)
            if (durationMs > 0L) put(COLUMN_DURATION_MILLIS, durationMs)
            put(COLUMN_LAST_PLAYBACK_POSITION_MILLIS, positionMs)
        }
}

/** Watch Next is required; Preview Channel is deliberately best effort and never masks success. */
internal class AndroidTvProviderContinueWatchingPublisher(
    private val context: Context,
    private val publicationIndex: TvProviderPublicationIndex = TvProviderPublicationIndex(context),
) : ContinueWatchingPublisher {
    override suspend fun replace(entries: List<ContinueWatchingEntry>): ContinueWatchingPublishResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return ContinueWatchingPublishResult.Unavailable(
                backend = ContinueWatchingBackend.WatchNext,
                reason = "android_api_below_26",
                terminal = true,
            )
        }
        if (!context.packageManager.hasSystemFeature("android.software.leanback")) {
            return ContinueWatchingPublishResult.Unavailable(
                backend = ContinueWatchingBackend.WatchNext,
                reason = "not_an_android_tv_device",
                terminal = true,
            )
        }

        val records = entries.map(ContinueWatchingEntry::toTvProgramRecord)
        val desiredIds = records.mapTo(linkedSetOf(), TvProgramRecord::internalProviderId)
        val oldWatchNextIds = publicationIndex.watchNextIds()
        val watchNext = WatchNextProgramPublisher(context)
        records.forEach { record ->
            when (val result = watchNext.upsert(record)) {
                TvProviderWriteResult.Success -> Unit
                is TvProviderWriteResult.Failure -> return result.toPublishResult()
            }
        }
        (oldWatchNextIds - desiredIds).forEach { staleId ->
            when (val result = watchNext.delete(staleId)) {
                TvProviderWriteResult.Success -> Unit
                is TvProviderWriteResult.Failure -> return result.toPublishResult()
            }
        }
        publicationIndex.replaceWatchNextIds(desiredIds)

        val preview = PreviewChannelPublisher(context)
        val previewResult = preview.replace(records, publicationIndex.previewIds())
        val degraded =
            when (previewResult) {
                TvProviderWriteResult.Success -> {
                    publicationIndex.replacePreviewIds(desiredIds)
                    null
                }
                is TvProviderWriteResult.Failure -> "preview_channel:${previewResult.reason}"
            }
        return ContinueWatchingPublishResult.Published(
            backend = ContinueWatchingBackend.WatchNext,
            publishedCount = records.size,
            deletedCount = (oldWatchNextIds - desiredIds).size,
            degradedSurface = degraded,
        )
    }
}

internal sealed interface TvProviderWriteResult {
    data object Success : TvProviderWriteResult

    data class Failure(
        val reason: String,
        val retryable: Boolean,
    ) : TvProviderWriteResult
}

private inline fun providerCall(block: () -> Unit): TvProviderWriteResult =
    try {
        block()
        TvProviderWriteResult.Success
    } catch (error: TvProviderWriteException) {
        TvProviderWriteResult.Failure(error.providerReason, error.providerRetryable)
    } catch (error: SecurityException) {
        AppLog.warning(
            category = "tv.provider",
            event = "permission_denied",
            message = "TvProvider denied a Yfuse program update",
            throwable = error,
        )
        TvProviderWriteResult.Failure("write_epg_permission_denied", retryable = false)
    } catch (error: IllegalArgumentException) {
        AppLog.warning(
            category = "tv.provider",
            event = "provider_unavailable",
            message = "TvProvider rejected a Yfuse program operation",
            throwable = error,
        )
        TvProviderWriteResult.Failure("tv_provider_unavailable", retryable = false)
    } catch (error: Throwable) {
        AppLog.warning(
            category = "tv.provider",
            event = "write_failed",
            message = "A TvProvider program update failed and will be retried",
            throwable = error,
        )
        TvProviderWriteResult.Failure("tv_provider_write_failed", retryable = true)
    }

private fun TvProviderWriteResult.requireSuccess() {
    if (this is TvProviderWriteResult.Failure) throw TvProviderWriteException(reason, retryable)
}

private class TvProviderWriteException(
    val providerReason: String,
    val providerRetryable: Boolean,
) : IllegalStateException(providerReason)

private fun TvProviderWriteResult.Failure.toPublishResult(): ContinueWatchingPublishResult =
    ContinueWatchingPublishResult.Failed(
        backend = ContinueWatchingBackend.WatchNext,
        reason = reason,
        retryable = retryable,
    )

private val ContinueWatchingMediaType.programType: Int
    get() =
        when (this) {
            ContinueWatchingMediaType.Movie -> TvContract.PreviewPrograms.TYPE_MOVIE
            ContinueWatchingMediaType.Episode -> TvContract.PreviewPrograms.TYPE_TV_EPISODE
        }

private const val MAX_TITLE_CHARS = 200
private const val MAX_DESCRIPTION_CHARS = 1_000
private const val CHANNEL_INTERNAL_PROVIDER_ID = "yfuse.continue_watching.v1"
private const val CHANNEL_DISPLAY_NAME = "Yfuse · 继续观看"
private const val CHANNEL_DESCRIPTION = "在 Yfuse 中继续播放"

// Framework column names are stable contract values. Keeping them local avoids an AndroidX
// tvprovider dependency in the shared mobile/TV source set while still using TvProvider itself.
private const val COLUMN_ID = "_id"
private const val COLUMN_CHANNEL_ID = "channel_id"
private const val COLUMN_TYPE = "type"
private const val COLUMN_TITLE = "title"
private const val COLUMN_SHORT_DESCRIPTION = "short_description"
private const val COLUMN_POSTER_ART_URI = "poster_art_uri"
private const val COLUMN_INTENT_URI = "intent_uri"
private const val COLUMN_INTERNAL_PROVIDER_ID = "internal_provider_id"
private const val COLUMN_DURATION_MILLIS = "duration_millis"
private const val COLUMN_LAST_PLAYBACK_POSITION_MILLIS = "last_playback_position_millis"
private const val COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS = "last_engagement_time_utc_millis"
private const val COLUMN_WATCH_NEXT_TYPE = "watch_next_type"
private const val COLUMN_DISPLAY_NAME = "display_name"
private const val COLUMN_DESCRIPTION = "description"
private const val COLUMN_APP_LINK_INTENT_URI = "app_link_intent_uri"
private const val WATCH_NEXT_TYPE_CONTINUE = 0
