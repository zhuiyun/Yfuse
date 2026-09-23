package com.yfuse.feature.handoff

import android.content.Context
import android.content.Intent
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.handoff.ActiveHandoffPlayback
import com.yfuse.core.handoff.HandoffMedia
import com.yfuse.core.handoff.HandoffPlaybackRegistry
import com.yfuse.core.handoff.HandoffStartResult
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core2.android.core2NativeBaselineBlockReason
import com.yfuse.feature.player.ActivePlayback
import com.yfuse.feature.player.PlaybackSelection
import com.yfuse.feature.player.PlayerActivity
import com.yfuse.feature.player.PlayerState
import com.yfuse.feature.player.PlayerStoreFactory
import com.yfuse.feature.player.networkShortfallMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.yfuse.core.platform.AppBuildConfig as BuildConfig

/** Resolves metadata without starting an engine, then acknowledges actual playback after commit. */
class AndroidHandoffReceiver(
    context: Context,
    private val repository: EmbyRepository,
    private val registry: ServerRegistry,
    private val personal: PersonalLibraryRepository,
    private val storeFactory: StoreFactory,
    private val preferences: PlaybackPreferences,
    private val theme: ThemePreferences,
    private val bridge: HandoffPlaybackRegistry,
) : HandoffPlaybackRegistry.Receiver {
    private val appContext = context.applicationContext
    private var prepared: PlayerState? = null
    private var preparedScope: String? = null
    private var expected: HandoffMedia? = null
    private var launched: HandoffMedia? = null
    private var failure: String? = null

    override suspend fun prepare(media: HandoffMedia): Boolean {
        failure = null
        return withTimeoutOrNull(PREPARE_TIMEOUT_MS) { resolve(media) }
            ?: false.also { failure = "在本机服务器上查找这部影片超时" }
    }

    private suspend fun resolve(media: HandoffMedia): Boolean {
        if (ActivePlayback.state.value.active) return false.also { failure = "本机正在播放其他影片" }
        if (media.profileId != personal.activeProfileId) return false.also { failure = "本机当前的资料与来源设备不同" }
        release()
        val token = personal.scopeToken
        val original = registry.serverById(media.serverId)
        val candidates =
            listOfNotNull(original) +
                registry.data.value.servers
                    .filter { it.id != original?.id }
        for (server in candidates) {
            val itemId =
                if (server.id == original?.id) {
                    media.itemId
                } else {
                    // Server-local identities cannot safely identify the same film on another server.
                    val keys = (listOf(media.mediaKey) + media.aliases).filterNot { it.startsWith("emby:") }
                    var found: String? = null
                    for (key in keys) {
                        val result = repository.findByMediaKey(server, key)
                        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                        found = result.getOrNull()?.id
                        if (found != null) break
                    }
                    found ?: continue
                }
            val store =
                PlayerStoreFactory(
                    storeFactory,
                    repository,
                    registry,
                    itemId,
                    startPositionTicks = media.positionMs.coerceAtMost(Long.MAX_VALUE / 10_000) * 10_000,
                    serverId = server.id,
                    mediaSourceId = media.mediaSourceId.takeIf { server.id == original?.id },
                    mediaVersionPreference = preferences.mediaVersionPreference.value,
                ).create()
            val state =
                try {
                    store.states.first { !it.loading }
                } finally {
                    store.dispose()
                }
            if (personal.scopeToken != token) return false.also { failure = "本机资料已切换" }
            val item = state.items.getOrNull(state.startIndex) ?: continue
            if (state.error != null) continue
            if ((item.matchKeys + item.watchKey).none { it in media.aliases || it == media.mediaKey }) continue
            if (server.id == original?.id &&
                media.mediaSourceId != null &&
                item.versionId != media.mediaSourceId
            ) {
                continue
            }
            if (!compatibleHandoffTimeline(
                    media.durationMs,
                    item.durationMsHint,
                    sameVersion = server.id == media.serverId && item.versionId == media.mediaSourceId,
                )
            ) {
                continue
            }
            if (BuildConfig.YFUSE_NATIVE_ONLY_RUNTIME || preferences.core2NativeOnlyEnabled.value) {
                if (state.items.core2NativeBaselineBlockReason(state.startIndex) != null) continue
            }
            prepared = state
            preparedScope = token
            expected =
                media.copy(
                    serverId = server.id,
                    itemId = item.id,
                    mediaKey = item.watchKey,
                    mediaSourceId = item.versionId,
                )
            return true
        }
        failure = "本机的服务器里没有找到这部影片的同一版本"
        return false
    }

    override suspend fun start(media: HandoffMedia): Boolean {
        val queue = prepared ?: return false
        val target = expected ?: return false
        val item = queue.items.getOrNull(queue.startIndex) ?: return false
        if (preparedScope != personal.scopeToken ||
            !personal.canAccessServer(target.serverId) ||
            media.profileId != personal.activeProfileId
        ) {
            return false.also { failure = "本机资料已切换" }
        }
        if (ActivePlayback.state.value.active) return false.also { failure = "本机正在播放其他影片" }
        val intent =
            PlayerActivity
                .intent(
                    appContext,
                    queue.items,
                    queue.startIndex,
                    media.positionMs,
                    theme.engine.value,
                    theme.decoder.value,
                    theme.autoNext.value,
                    startPlaybackRequested = true,
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val committed =
            media.forHandoffTarget(
                serverId = target.serverId,
                itemId = target.itemId,
                mediaKey = target.mediaKey,
                mediaSourceId = target.mediaSourceId,
            )
        launched = committed
        bridge.offerPreferences(committed)
        PlaybackSelection.update(item)
        try {
            appContext.startActivity(intent)
            when (val result = bridge.awaitPlayback(target)) {
                is HandoffStartResult.Playing -> return preparedScope == personal.scopeToken
                is HandoffStartResult.Failed ->
                    failure = result.last.shortfall() ?: "本机播放器未能打开这部影片"
                is HandoffStartResult.Stalled ->
                    failure = result.last?.shortfall() ?: "本机长时间没有收到播放数据"
            }
        } catch (thrown: Throwable) {
            PlayerActivity.discardLaunch(intent)
            if (thrown is CancellationException) throw thrown
            failure = "本机播放器未能启动"
            release()
            return false
        }
        PlayerActivity.discardLaunch(intent)
        release()
        return false
    }

    /**
     * Also answers while a transfer is still waiting and the request runs out: the latest state the
     * launched player published says whether the link was the reason.
     */
    override fun failureReason(): String? =
        failure ?: launched?.let { target ->
            bridge.activePlayback.value
                ?.takeIf { it.media.serverId == target.serverId && it.media.itemId == target.itemId }
                ?.shortfall()
        }

    private fun ActiveHandoffPlayback.shortfall(): String? =
        networkShortfallMessage(networkBitsPerSecond, sourceBitsPerSecond)?.let { "本机$it" }

    override suspend fun release() {
        launched?.let { target ->
            bridge.clearPreferences(target)
            val selected = PlaybackSelection.state.value
            if (selected.serverId == target.serverId && selected.itemId == target.itemId) ActivePlayback.close()
        }
        launched = null
        prepared = null
        preparedScope = null
        expected = null
    }

    override fun transferCompleted() {
        launched = null
        prepared = null
        preparedScope = null
        expected = null
    }
}

/** Resolving the title on this device's servers, before the source is asked to pause. */
private const val PREPARE_TIMEOUT_MS = 25_000L
