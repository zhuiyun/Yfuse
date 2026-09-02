from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


path = Path("composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerStore.kt")
text = path.read_text(encoding="utf-8")

text = replace_once(
    text,
    '''                val detail = detailResult.getOrNull()
                val seriesId = detail?.seriesId
                // Queue artwork and sibling episodes are independent of PlaybackInfo. Starting
                // them together keeps first launch latency to the slowest required request instead
                // of adding three server round trips before the activity can open.
                val seriesDetailDeferred =
                    if (detail?.type == "Episode" && seriesId != null) {
                        async { repo.itemDetail(server, seriesId) }
                    } else {
                        null
                    }
                val episodesDeferred =
                    if (detail?.type == "Episode" && seriesId != null) {
                        async {
                            repo.episodes(
                                server,
                                seriesId,
                                null,
                                includeMediaSources = true,
                            )
                        }
                    } else {
                        null
                    }
                val requestedSessionId = EmbyStream.newPlaySessionId()
''',
    '''                val detail = detailResult.getOrNull()
                val seriesId = detail?.seriesId
                val requestedSessionId = EmbyStream.newPlaySessionId()
''',
    "speculative episode preload block",
)

text = replace_once(
    text,
    '''                    dispatch(PlayerMsg.Failed("所选资源与服务器返回不一致，请刷新详情后重试"))
                    seriesDetailDeferred?.cancel()
                    episodesDeferred?.cancel()
                    return@launch
''',
    '''                    dispatch(PlayerMsg.Failed("所选资源与服务器返回不一致，请刷新详情后重试"))
                    return@launch
''',
    "mismatch cancellation block",
)

text = replace_once(
    text,
    '''                    val seriesDetail = requireNotNull(seriesDetailDeferred).await().getOrNull()
                    val seriesProviderIds = seriesDetail?.providerIds.orEmpty()
''',
    '''                    val seriesDetail = repo.itemDetail(server, seriesId).getOrNull()
                    val seriesProviderIds = seriesDetail?.providerIds.orEmpty()
''',
    "series detail deferred await",
)

text = replace_once(
    text,
    '''                    val episodesResult = requireNotNull(episodesDeferred).await()
                    episodesResult.onFailure {
''',
    '''                    val episodesResult =
                        repo.episodes(
                            server,
                            seriesId,
                            null,
                            includeMediaSources = true,
                        )
                    episodesResult.onFailure {
''',
    "episodes deferred await",
)

path.write_text(text, encoding="utf-8")
Path("scripts/apply-player-queue-deterministic.py").unlink()
Path(".github/workflows/apply-ycore-dolby-profile-fix.yml").unlink()
