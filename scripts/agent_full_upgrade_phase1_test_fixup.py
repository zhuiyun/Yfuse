from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / "composeApp/src/commonTest/kotlin/com/yfuse/feature/detail/DetailStoreTest.kt"
text = path.read_text(encoding="utf-8")

text = text.replace(
    "class DetailStoreTest {\n",
    "class DetailStoreTest {\n    private lateinit var testPlaybackTrackRequest: PlaybackTrackRequest\n    private lateinit var testSyncManager: ServerSyncManager\n",
    1,
)
text = text.replace(
    '''        val syncRegistry = testRegistry()
        val syncRepo = testRepo { json("{}") }
        startKoin {
            modules(
                module {
                    single { PlaybackTrackRequest() }
                    single { ServerSyncManager(syncRepo, syncRegistry, MapSettings()) }
                },
            )
        }
''',
    '''        val syncRegistry = testRegistry()
        val syncRepo = testRepo { json("{}") }
        testPlaybackTrackRequest = PlaybackTrackRequest()
        testSyncManager = ServerSyncManager(syncRepo, syncRegistry, MapSettings())
        startKoin {
            modules(
                module {
                    single { testPlaybackTrackRequest }
                    single { testSyncManager }
                },
            )
        }
''',
    1,
)
text = text.replace(
    '''            sourceSelectionTimeoutMs = sourceSelectionTimeoutMs,
            mainContext = Dispatchers.Unconfined,
        ).create()
''',
    '''            sourceSelectionTimeoutMs = sourceSelectionTimeoutMs,
            mainContext = Dispatchers.Unconfined,
            playbackTrackRequest = testPlaybackTrackRequest,
            syncManager = testSyncManager,
        ).create()
''',
    1,
)
text = text.replace(
    '''            serverId = "one",
            mainContext = mainContext,
        ).create()
''',
    '''            serverId = "one",
            mainContext = mainContext,
            playbackTrackRequest = testPlaybackTrackRequest,
            syncManager = testSyncManager,
        ).create()
''',
    1,
)
path.write_text(text, encoding="utf-8")
print("phase1 test DI fixup applied")
