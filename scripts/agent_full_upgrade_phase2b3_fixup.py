from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
mode = sys.argv[1] if len(sys.argv) > 1 else "post"


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8")


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    text = read(path)
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:180]!r}")
    write(path, text.replace(old, new, count))


player_component = "composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerComponent.kt"

if mode == "pre":
    replace(
        player_component,
        '    mediaSourceId: String? = null,\n',
        '    private val mediaSourceId: String? = null,\n',
    )
    replace(
        player_component,
        '''            serverId,
            mediaSourceId,
        ).create()
''',
        '''            serverId = serverId,
        mediaSourceId = mediaSourceId,
    ).create()
''',
    )
    print("phase2b3 pre-fixup applied")
    raise SystemExit(0)

detail_component = "composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailComponent.kt"
text = read(detail_component)
if 'import com.yfuse.core.data.PlaybackFailoverPlan\n' not in text:
    text = text.replace(
        'import com.yfuse.core.data.EmbyRepository\n',
        'import com.yfuse.core.data.EmbyRepository\nimport com.yfuse.core.data.PlaybackFailoverPlan\n',
        1,
    )
if 'import com.yfuse.core.sync.watchKey\n' not in text:
    text = text.replace(
        'import com.yfuse.core.util.componentScope\n',
        'import com.yfuse.core.util.componentScope\nimport com.yfuse.core.sync.watchKey\n',
        1,
    )
old = '''                releaseOwnedPreload()
                val prepared = PlayerStoreFactory(
                    storeFactory = storeFactory,
                    repo = repo,
                    registry = registry,
                    itemId = target.id,
                    startPositionTicks = state.playPositionTicks,
                    serverId = server.id,
                    mediaSourceId = state.selectedVersionId,
                ).create()
'''
new = '''                releaseOwnedPreload()
                dependencies.playbackFailoverRequest.set(
                    PlaybackFailoverPlan(
                        itemId = target.id,
                        mediaKey = target.providerIds.watchKey(target.id),
                        fallbackServerIds = state.sources.asSequence()
                            .filter { it.reachable && it.source != null && it.serverId != server.id }
                            .map { it.serverId }
                            .distinct()
                            .toList(),
                    ),
                )
                val prepared = PlayerStoreFactory(
                    storeFactory = storeFactory,
                    repo = repo,
                    registry = registry,
                    itemId = target.id,
                    startPositionTicks = state.playPositionTicks,
                    serverId = server.id,
                    mediaSourceId = state.selectedVersionId,
                    failoverRequest = dependencies.playbackFailoverRequest,
                    healthMonitor = dependencies.serverHealthMonitor,
                ).create()
'''
if old not in text:
    raise SystemExit("preloaded player store anchor missing")
text = text.replace(old, new, 1)
write(detail_component, text)

player_store = "composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerStore.kt"
text = read(player_store)
text = text.replace('mediaSourceId = mediaSourceId,', 'mediaSourceId = effectiveMediaSourceId,')
write(player_store, text.rstrip() + "\n")

for relative in (
    "composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/DownloadsScreen.kt",
    "composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/ProfileSettingsScreens.kt",
    "composeApp/src/commonMain/kotlin/com/yfuse/core/data/PlaybackFailoverRequest.kt",
    "composeApp/src/commonTest/kotlin/com/yfuse/feature/player/PlaybackFailoverPolicyTest.kt",
):
    path = ROOT / relative
    if path.exists():
        path.write_text(path.read_text(encoding="utf-8").rstrip() + "\n", encoding="utf-8")

print("phase2b3 post-fixup applied")
