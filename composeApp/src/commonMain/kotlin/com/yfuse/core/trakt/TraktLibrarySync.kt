package com.yfuse.core.trakt

import com.yfuse.core.personal.PersonalCollection
import com.yfuse.core.personal.PersonalEntry
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * Lists use a three-way merge: an edit since the last successful sync wins a conflict.
 * History is additive, deduplicated by media, and never replaces local resume progress.
 */
internal class TraktLibrarySync(
    private val sink: TraktLibrarySink,
    private val checkpoint: TraktSyncCheckpoint,
    private val readPage: suspend (PersonalCollection, Int) -> TraktPage,
    private val activity: suspend () -> String,
    private val write: suspend (PersonalCollection, TraktLibraryMedia, Boolean, Long?, () -> Unit) -> Unit,
    private val ensureCurrent: () -> Unit,
    private val pendingHistory: (TraktLibraryMedia) -> Boolean = { false },
    private val progress: (String) -> Unit,
) {
    suspend fun sync(collection: PersonalCollection): TraktSyncResult {
        var result = TraktSyncResult(collection)
        try {
            ensureCurrent()
            val snapshot = sink.snapshot()
            val previous = checkpoint.read(collection)
            val local = snapshot.entries.filter { it.collection == collection }
            val remote = readRemote(collection)
            ensureScope(snapshot)
            val remoteIndex = mutableMapOf<String, TraktListItem>()
            remote.forEach { item -> item.libraryMedia()?.aliases()?.forEach { remoteIndex.putIfAbsent(it, item) } }
            val consumed = mutableSetOf<TraktListItem>()
            val next = mutableMapOf<String, TraktSyncMark>()
            for (entry in local) {
                ensureScope(snapshot)
                val media = entry.media.traktMedia()
                if (media == null ||
                    !supported(collection, media) ||
                    collection == PersonalCollection.History &&
                    (!entry.completed || entry.deleted)
                ) {
                    result = result.copy(skipped = result.skipped + 1)
                    // Local unwatched/deleted history must block re-import through any known alias.
                    media?.aliases()?.mapNotNull(remoteIndex::get)?.let(consumed::addAll)
                    continue
                }
                val match = media.aliases().firstNotNullOfOrNull(remoteIndex::get)
                match?.let(consumed::add)
                var current = entry
                var present = match != null
                val baseline = previous[entry.media.identity]
                val edited = baseline == null || baseline.stamp != entry.stamp || baseline.deleted != entry.deleted
                when {
                    collection != PersonalCollection.History &&
                        !edited &&
                        baseline?.remotePresent == true &&
                        !present &&
                        !entry.deleted -> {
                        current = sink.apply(snapshot, entry, entry.copy(deleted = true))
                        result = result.copy(removed = result.removed + 1)
                    }
                    collection != PersonalCollection.History &&
                        !edited &&
                        baseline?.remotePresent == false &&
                        present &&
                        entry.deleted -> {
                        current = sink.apply(snapshot, entry, entry.copy(deleted = false))
                        result = result.copy(imported = result.imported + 1)
                    }
                    entry.deleted && present -> {
                        check(sink.isCurrent(snapshot, entry)) { "同步期间条目已修改" }
                        progress("正在移除云端${collection.traktLabel}条目…")
                        if (!writeMatched(
                                collection,
                                match!!.libraryMedia()!!,
                                true,
                                null,
                            ) { ensureEntry(snapshot, entry) }
                        ) {
                            result = result.copy(skipped = result.skipped + 1, unmatched = result.unmatched + 1)
                            baseline?.let { next[entry.media.identity] = it }
                            continue
                        }
                        ensureScope(snapshot)
                        present = false
                        result = result.copy(removed = result.removed + 1)
                    }
                    !entry.deleted && !present -> {
                        if (collection == PersonalCollection.History && pendingHistory(media)) {
                            result = result.copy(skipped = result.skipped + 1)
                            continue
                        }
                        // A local edit while earlier network requests were in flight is handled next run.
                        check(sink.isCurrent(snapshot, entry)) { "同步期间条目已修改" }
                        progress("正在上传${collection.traktLabel}条目…")
                        val matched =
                            writeMatched(
                                collection,
                                media,
                                false,
                                entry.watchedAtEpochMs.takeIf {
                                    collection ==
                                        PersonalCollection.History
                                },
                            ) {
                                ensureEntry(snapshot, entry)
                                check(collection != PersonalCollection.History || !pendingHistory(media))
                            }
                        if (!matched) {
                            result = result.copy(skipped = result.skipped + 1, unmatched = result.unmatched + 1)
                            baseline?.let { next[entry.media.identity] = it }
                            continue
                        }
                        ensureScope(snapshot)
                        present = true
                        result = result.copy(uploaded = result.uploaded + 1)
                    }
                }
                next[entry.media.identity] = TraktSyncMark(current.stamp, current.deleted, present)
            }
            val localAliases =
                local
                    .flatMap {
                        it.media
                            .traktMedia()
                            ?.aliases()
                            .orEmpty()
                    }.toMutableSet()
            for (item in remote) {
                ensureScope(snapshot)
                val media = item.libraryMedia()
                if (item in consumed || media?.aliases()?.any { it in localAliases } == true) continue
                val ref = item.toPersonalMedia()
                val watchedAt = item.watchedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                if (media == null ||
                    ref == null ||
                    ref.title.isBlank() ||
                    !supported(collection, media) ||
                    collection == PersonalCollection.History &&
                    (watchedAt == null || watchedAt <= 0)
                ) {
                    result = result.copy(skipped = result.skipped + 1)
                    continue
                }
                val incoming =
                    PersonalEntry(
                        snapshot.profileId,
                        collection,
                        ref,
                        com.yfuse.core.personal
                            .PersonalStamp(),
                        completed = collection == PersonalCollection.History,
                        watchedAtEpochMs = watchedAt ?: 0,
                    )
                val added = sink.apply(snapshot, null, incoming)
                next[ref.identity] = TraktSyncMark(added.stamp, false, true)
                localAliases.addAll(media.aliases())
                result = result.copy(imported = result.imported + 1)
            }
            ensureScope(snapshot)
            checkpoint.write(collection, next)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            result = result.copy(error = syncFailure(failure))
        }
        return result
    }

    private suspend fun readRemote(collection: PersonalCollection): List<TraktListItem> {
        val before = activity()
        val items = mutableListOf<TraktListItem>()
        for (page in 1..200) {
            ensureCurrent()
            progress("正在读取${collection.traktLabel} · 第 $page 页")
            val response = readPage(collection, page)
            check(items.size + response.items.size <= 20_000)
            // Missing identities must never be interpreted as a remote deletion.
            check(response.items.all { it.type !in setOf("movie", "show", "episode") || it.libraryMedia() != null })
            check(response.items.all { it.type in setOf("movie", "show", "episode", "season") })
            items += response.items
            if (!response.hasNext) {
                check(before == activity()) { "Trakt 清单读取期间发生变化" }
                return items
            }
        }
        error("Trakt 清单超出本轮读取范围")
    }

    private fun ensureScope(snapshot: TraktLibrarySnapshot) {
        ensureCurrent()
        if (sink.scopeToken != snapshot.scope) throw CancellationException("Personal profile changed")
    }

    private suspend fun writeMatched(
        collection: PersonalCollection,
        media: TraktLibraryMedia,
        remove: Boolean,
        watchedAt: Long?,
        guard: () -> Unit,
    ): Boolean =
        try {
            write(collection, media, remove, watchedAt, guard)
            true
        } catch (_: TraktUnmatchedItemException) {
            false
        }

    private fun ensureEntry(
        snapshot: TraktLibrarySnapshot,
        entry: PersonalEntry,
    ) {
        ensureScope(snapshot)
        check(sink.isCurrent(snapshot, entry)) { "同步期间条目已修改" }
    }

    private fun supported(
        collection: PersonalCollection,
        media: TraktLibraryMedia,
    ) = when (collection) {
        PersonalCollection.Favorite -> media.type in setOf("movie", "show")
        PersonalCollection.WatchLater -> media.type in setOf("movie", "show", "episode")
        PersonalCollection.History -> media.type in setOf("movie", "episode")
    }
}

internal fun syncFailure(failure: Exception): String =
    when ((failure as? TraktApiException)?.status) {
        401, 403 -> "授权失效或没有权限，请重新连接后重试"
        420 -> "已达到 Trakt 账号清单额度；已完成内容保留"
        429 -> "Trakt 请求过于频繁，请稍后重试"
        else ->
            when (failure.message) {
                "个人数据过多，请先整理清单" ->
                    "个人库已达 1,000 条容量上限（含其他家庭资料及删除记录）；未导入内容保留在 Trakt"
                "同步期间条目已修改", "同步期间条目已修改，请重试" ->
                    "本机条目在同步期间已修改，请重试；已保留最新修改"
                "Trakt 清单读取期间发生变化" ->
                    "云端清单在读取期间发生变化，请重新同步"
                "Trakt 清单超出本轮读取范围" ->
                    "云端清单超出本轮读取范围，本项未应用同步"
                else -> "未完成，请重试；已完成内容保留，未完成内容不会标记成功"
            }
    }
