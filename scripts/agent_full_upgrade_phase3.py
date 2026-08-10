from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    text = read(path)
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:180]!r}")
    write(path, text.replace(old, new, count))


if (ROOT / "composeApp/src/commonMain/kotlin/com/yfuse/core/data/PlaybackFailoverRequest.kt").exists():
    print("phase3 already applied")
    raise SystemExit(0)

write(
    "composeApp/src/commonMain/kotlin/com/yfuse/core/data/PlaybackFailoverRequest.kt",
    '''package com.yfuse.core.data

/** One-shot exact-media failover plan produced by Detail and consumed by the Player queue. */
data class PlaybackFailoverPlan(
    val itemId: String,
    val mediaKey: String,
    val fallbackServerIds: List<String>,
)

class PlaybackFailoverRequest {
    private var pending: PlaybackFailoverPlan? = null

    @Synchronized
    fun set(plan: PlaybackFailoverPlan) {
        pending = plan
    }

    @Synchronized
    fun consume(itemId: String): PlaybackFailoverPlan? {
        val plan = pending?.takeIf { it.itemId == itemId }
        if (plan != null) pending = null
        return plan
    }
}
''',
)

# DI/AppDependencies.
app_module = "composeApp/src/commonMain/kotlin/com/yfuse/di/AppModule.kt"
replace(app_module, 'import com.yfuse.core.data.PlaybackPreferences\n', 'import com.yfuse.core.data.PlaybackPreferences\nimport com.yfuse.core.data.PlaybackFailoverRequest\n')
replace(app_module, '    single { PlaybackPreferences(get()) }\n', '    single { PlaybackPreferences(get()) }\n    single { PlaybackFailoverRequest() }\n')

deps = "composeApp/src/commonMain/kotlin/com/yfuse/app/AppDependencies.kt"
replace(deps, 'import com.yfuse.core.data.PlaybackPreferences\n', 'import com.yfuse.core.data.PlaybackPreferences\nimport com.yfuse.core.data.PlaybackFailoverRequest\n')
replace(deps, '    val playbackPreferences: PlaybackPreferences,\n', '    val playbackPreferences: PlaybackPreferences,\n    val playbackFailoverRequest: PlaybackFailoverRequest,\n')
main = "composeApp/src/androidMain/kotlin/com/yfuse/MainActivity.kt"
replace(main, '                    playbackPreferences = koin.get(),\n', '                    playbackPreferences = koin.get(),\n                    playbackFailoverRequest = koin.get(),\n')

# Detail writes a one-shot plan before launching playback.
detail_store = "composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailStore.kt"
replace(detail_store, 'import com.yfuse.core.data.PlaybackTrackRequest\n', 'import com.yfuse.core.data.PlaybackTrackRequest\nimport com.yfuse.core.data.PlaybackFailoverPlan\nimport com.yfuse.core.data.PlaybackFailoverRequest\n')
replace(detail_store, 'import com.yfuse.core.sync.ServerSyncManager\n', 'import com.yfuse.core.sync.ServerSyncManager\nimport com.yfuse.core.sync.watchKey\n')
replace(detail_store, '    private val syncManager: ServerSyncManager,\n) {\n', '    private val syncManager: ServerSyncManager,\n    private val playbackFailoverRequest: PlaybackFailoverRequest = PlaybackFailoverRequest(),\n) {\n')
replace(
    detail_store,
    '''            playbackTrackRequest.set(
                itemId = target.id,
                audioLanguage = current.preferredAudioLanguage,
                subtitleLanguage = current.preferredSubtitleLanguage,
            )
            publish(
''',
    '''            playbackTrackRequest.set(
                itemId = target.id,
                audioLanguage = current.preferredAudioLanguage,
                subtitleLanguage = current.preferredSubtitleLanguage,
            )
            val mediaKey = target.providerIds.watchKey(target.id)
            val fallbackServers = current.sources.asSequence()
                .filter { it.reachable && it.source != null && it.serverId != server.id }
                .map { it.serverId }
                .distinct()
                .toList()
            playbackFailoverRequest.set(
                PlaybackFailoverPlan(
                    itemId = target.id,
                    mediaKey = mediaKey,
                    fallbackServerIds = fallbackServers,
                ),
            )
            publish(
''',
)
detail_comp = "composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailComponent.kt"
replace(detail_comp, '        syncManager = dependencies.serverSyncManager,\n', '        syncManager = dependencies.serverSyncManager,\n        playbackFailoverRequest = dependencies.playbackFailoverRequest,\n')

# Player gets dependencies through components rather than a new service locator.
player_comp = "composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerComponent.kt"
replace(player_comp, 'import com.yfuse.core.data.ServerRegistry\n', 'import com.yfuse.core.data.ServerRegistry\nimport com.yfuse.app.AppDependencies\n')
replace(player_comp, '    private val mediaSourceId: String? = null,\n', '    private val mediaSourceId: String? = null,\n    private val dependencies: AppDependencies,\n')
replace(player_comp, '        mediaSourceId = mediaSourceId,\n    ).create()\n', '        mediaSourceId = mediaSourceId,\n        failoverRequest = dependencies.playbackFailoverRequest,\n        healthMonitor = dependencies.serverHealthMonitor,\n    ).create()\n')

for path in (
    "composeApp/src/commonMain/kotlin/com/yfuse/feature/home/HomeTabComponent.kt",
    "composeApp/src/commonMain/kotlin/com/yfuse/feature/library/LibraryComponent.kt",
    "composeApp/src/commonMain/kotlin/com/yfuse/feature/search/SearchComponent.kt",
):
    text = read(path)
    needle = '                mediaSourceId = config.mediaSourceId,\n                onBack ='
    if needle not in text:
        raise SystemExit(f"PlayerComponent call anchor missing in {path}")
    text = text.replace(
        needle,
        '                mediaSourceId = config.mediaSourceId,\n                dependencies = dependencies,\n                onBack =',
    )
    write(path, text)

# PlayerStore performs failover only for transport/network/5xx metadata failures and resolves
# the exact media identity on the fallback server. 401/403 never switch accounts silently.
player_store = "composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerStore.kt"
replace(player_store, 'import com.yfuse.core.data.ServerRegistry\n', 'import com.yfuse.core.data.ServerRegistry\nimport com.yfuse.core.data.PlaybackFailoverRequest\nimport com.yfuse.core.data.ServerHealthMonitor\nimport com.yfuse.core.data.ServerHealthStatus\n')
replace(player_store, 'import com.yfuse.core.network.EmbyStream\n', 'import com.yfuse.core.network.EmbyStream\nimport com.yfuse.core.network.EmbyError\nimport com.yfuse.core.network.EmbyErrorException\n')
replace(player_store, '    private val mediaSourceId: String? = null,\n) {\n', '    private val mediaSourceId: String? = null,\n    private val failoverRequest: PlaybackFailoverRequest = PlaybackFailoverRequest(),\n    private val healthMonitor: ServerHealthMonitor? = null,\n) {\n')
replace(
    player_store,
    '''            val server = serverId?.let(registry::serverById) ?: registry.defaultServer
            val startMs = startPositionTicks / 10_000L
            scope.launch {
                if (server == null) {
''',
    '''            val primaryServer = serverId?.let(registry::serverById) ?: registry.defaultServer
            val startMs = startPositionTicks / 10_000L
            scope.launch {
                if (primaryServer == null) {
''',
)
replace(
    player_store,
    '''                    return@launch
                }

                var negotiatedVersions: List<MediaVersion> = emptyList()
''',
    '''                    return@launch
                }

                var server = primaryServer
                var effectiveItemId = itemId
                var effectiveMediaSourceId = mediaSourceId
                var detailResult = repo.itemDetail(server, effectiveItemId)
                val failoverPlan = failoverRequest.consume(itemId)
                val primaryFailure = detailResult.exceptionOrNull()
                if (primaryFailure == null) {
                    healthMonitor?.recordSuccess(server.id)
                } else {
                    healthMonitor?.recordFailure(server.id, primaryFailure)
                }
                if (primaryFailure?.isPlaybackFailoverEligible() == true && failoverPlan != null) {
                    for (fallbackId in failoverPlan.fallbackServerIds) {
                        val fallback = registry.serverById(fallbackId) ?: continue
                        if (healthMonitor?.health?.value?.get(fallback.id)?.status == ServerHealthStatus.AuthRequired) continue
                        val hitResult = repo.findByMediaKey(fallback, failoverPlan.mediaKey)
                        val hit = hitResult.getOrNull()
                        if (hit == null) {
                            hitResult.exceptionOrNull()?.let { healthMonitor?.recordFailure(fallback.id, it) }
                            continue
                        }
                        val fallbackDetail = repo.itemDetail(fallback, hit.id)
                        val resolved = fallbackDetail.getOrNull()
                        if (resolved != null) {
                            AppLog.warning(
                                category = "feature.player",
                                event = "playback_server_failover",
                                message = "Primary server failed before playback; switched to an exact-media fallback",
                                attributes = mapOf(
                                    "fromServerId" to server.id,
                                    "toServerId" to fallback.id,
                                    "mediaKey" to failoverPlan.mediaKey,
                                ),
                            )
                            server = fallback
                            effectiveItemId = hit.id
                            effectiveMediaSourceId = null
                            detailResult = fallbackDetail
                            healthMonitor?.recordSuccess(fallback.id)
                            break
                        } else {
                            fallbackDetail.exceptionOrNull()?.let { healthMonitor?.recordFailure(fallback.id, it) }
                        }
                    }
                }

                var negotiatedVersions: List<MediaVersion> = emptyList()
''',
)
# The old detail request is now already resolved above.
old_detail = '''                val detailResult = repo.itemDetail(server, itemId)
                detailResult.onFailure {
'''
if old_detail not in read(player_store):
    raise SystemExit("old Player detail request anchor missing")
replace(player_store, old_detail, '                detailResult.onFailure {\n')
# Scope effective id/version through the rest of queue build.
text = read(player_store)
start = text.index('                fun itemOf(')
end = text.index('        override fun executeIntent', start)
region = text[start:end]
region = region.replace('id == itemId', 'id == effectiveItemId')
region = region.replace('it.id == mediaSourceId', 'it.id == effectiveMediaSourceId')
region = region.replace('itemId = itemId,', 'itemId = effectiveItemId,')
region = region.replace('fallbackId = itemId', 'fallbackId = effectiveItemId')
region = region.replace('repo.trickplayInfo(server, itemId)', 'repo.trickplayInfo(server, effectiveItemId)')
region = region.replace('ep.id == itemId', 'ep.id == effectiveItemId')
region = region.replace('it.id == itemId', 'it.id == effectiveItemId')
region = region.replace('id = itemId,', 'id = effectiveItemId,')
text = text[:start] + region + text[end:]
write(player_store, text)
# Helper at end.
text = read(player_store)
text += '''

internal fun Throwable.isPlaybackFailoverEligible(): Boolean =
    when (val error = (this as? EmbyErrorException)?.error) {
        EmbyError.Network -> true
        is EmbyError.Server -> error.code in 500..599
        else -> false
    }
'''
write(player_store, text)

# Simple pure policy test: auth/access problems never fail over.
write(
    "composeApp/src/commonTest/kotlin/com/yfuse/feature/player/PlaybackFailoverPolicyTest.kt",
    '''package com.yfuse.feature.player

import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackFailoverPolicyTest {
    @Test
    fun network_and_5xx_can_fail_over() {
        assertTrue(EmbyErrorException(EmbyError.Network).isPlaybackFailoverEligible())
        assertTrue(EmbyErrorException(EmbyError.Server(503)).isPlaybackFailoverEligible())
    }

    @Test
    fun auth_and_client_errors_never_fail_over() {
        assertFalse(EmbyErrorException(EmbyError.Unauthorized).isPlaybackFailoverEligible())
        assertFalse(EmbyErrorException(EmbyError.Server(404)).isPlaybackFailoverEligible())
    }
}
''',
)

print("phase3 failover patch applied")
