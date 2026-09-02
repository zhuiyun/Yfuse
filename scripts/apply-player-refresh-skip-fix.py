from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    if old not in text:
        raise SystemExit(f"expected snippet not found in {path}: {old[:160]!r}")
    path.write_text(text.replace(old, new, 1))


skip = Path("composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerSkipCoordinator.kt")
activity = Path("composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerActivity.kt")

replace_once(
    skip,
    "private const val AUTO_SKIP_COUNTDOWN_SECONDS = 5\n",
    "private const val AUTO_SKIP_COUNTDOWN_SECONDS = 5\nprivate const val AUTO_SKIP_COUNTDOWN_TICK_MS = 100L\n",
)

old_skip = '''    val armed =\n        occurrence != null &&\n            mode == SkipMode.Auto &&\n            !watchGuest &&\n            canArmAutomaticSkip(\n                segmentType = activeSegment?.type,\n                playbackReady = playbackState.playing && !playbackState.buffering,\n                creditsEnteredFromPlayback = creditsEnteredFromPlayback,\n            ) &&\n            occurrence != settled.value\n    val latestSkipSegment by rememberUpdatedState(skipSegment)\n    LaunchedEffect(occurrence, armed) {\n        if (!armed) {\n            countdownSeconds = null\n            return@LaunchedEffect\n        }\n        for (remaining in AUTO_SKIP_COUNTDOWN_SECONDS downTo 1) {\n            countdownSeconds = remaining\n            delay(1_000L)\n        }\n        countdownSeconds = null\n        settled.value = occurrence\n        latestSkipSegment()\n    }\n'''

new_skip = '''    val playbackReady = playbackState.playing && !playbackState.buffering\n    var armedOccurrence by remember(currentItem?.id) {\n        mutableStateOf<Pair<String, PlaybackSegmentType>?>(null)\n    }\n    val latestOccurrence by rememberUpdatedState(occurrence)\n    val latestPlaybackReady by rememberUpdatedState(playbackReady)\n    val latestMode by rememberUpdatedState(mode)\n    val latestWatchGuest by rememberUpdatedState(watchGuest)\n    val latestSkipSegment by rememberUpdatedState(skipSegment)\n\n    // Once an intro/credits occurrence has armed, transient transport buffering must not disarm it.\n    // Otherwise every short YCore Range stall cancels this effect and starts the 5-second countdown\n    // from the beginning. Leaving the segment, changing mode, becoming a watch guest or settling the\n    // occurrence still clears it immediately.\n    LaunchedEffect(\n        occurrence,\n        mode,\n        watchGuest,\n        playbackReady,\n        creditsEnteredFromPlayback,\n        settled.value,\n    ) {\n        if (\n            occurrence == null ||\n            mode != SkipMode.Auto ||\n            watchGuest ||\n            occurrence == settled.value\n        ) {\n            armedOccurrence = null\n            countdownSeconds = null\n            return@LaunchedEffect\n        }\n        if (\n            armedOccurrence != occurrence &&\n            canArmAutomaticSkip(\n                segmentType = activeSegment?.type,\n                playbackReady = playbackReady,\n                creditsEnteredFromPlayback = creditsEnteredFromPlayback,\n            )\n        ) {\n            armedOccurrence = occurrence\n        }\n    }\n\n    // Buffering/pausing freezes the remaining countdown instead of resetting it. The coroutine is\n    // keyed only by the latched occurrence, so frequent position and buffering state updates cannot\n    // recreate the timer.\n    LaunchedEffect(armedOccurrence) {\n        val armed = armedOccurrence\n        if (armed == null) {\n            countdownSeconds = null\n            return@LaunchedEffect\n        }\n        var remainingMs = AUTO_SKIP_COUNTDOWN_SECONDS * 1_000L\n        countdownSeconds = AUTO_SKIP_COUNTDOWN_SECONDS\n        while (remainingMs > 0L) {\n            if (\n                latestOccurrence != armed ||\n                latestMode != SkipMode.Auto ||\n                latestWatchGuest ||\n                settled.value == armed\n            ) {\n                countdownSeconds = null\n                if (armedOccurrence == armed) armedOccurrence = null\n                return@LaunchedEffect\n            }\n            if (!latestPlaybackReady) {\n                delay(AUTO_SKIP_COUNTDOWN_TICK_MS)\n                continue\n            }\n            delay(AUTO_SKIP_COUNTDOWN_TICK_MS)\n            if (!latestPlaybackReady) continue\n            remainingMs = (remainingMs - AUTO_SKIP_COUNTDOWN_TICK_MS).coerceAtLeast(0L)\n            if (remainingMs > 0L) {\n                countdownSeconds =\n                    ((remainingMs + 999L) / 1_000L)\n                        .toInt()\n                        .coerceAtLeast(1)\n            }\n        }\n        countdownSeconds = null\n        settled.value = armed\n        if (armedOccurrence == armed) armedOccurrence = null\n        latestSkipSegment()\n    }\n'''
replace_once(skip, old_skip, new_skip)

old_activity = '''                playbackItems.value =\n                    playbackItems.value.toMutableList().apply { set(index, refreshed) }\n                queueResume.value = index to positionMs\n                queueRevision.value++\n                AppLog.info(\n'''
new_activity = '''                val currentQueue = playbackItems.value\n                val refreshedQueue =\n                    currentQueue.toMutableList().apply { set(index, refreshed) }\n                val playbackSourcesChanged = !currentQueue.hasSamePlaybackSourcesAs(refreshedQueue)\n                playbackItems.value = refreshedQueue\n                if (playbackSourcesChanged) {\n                    queueResume.value = index to positionMs\n                    queueRevision.value++\n                }\n                AppLog.info(\n'''
replace_once(activity, old_activity, new_activity)

old_log = '''                            "playMethod" to refreshed.playMethod.name,\n                            "mediaSourceId" to refreshed.versionId.orEmpty(),\n'''
new_log = '''                            "playMethod" to refreshed.playMethod.name,\n                            "mediaSourceId" to refreshed.versionId.orEmpty(),\n                            "engineRestarted" to playbackSourcesChanged.toString(),\n'''
replace_once(activity, old_log, new_log)

print("player refresh + skip countdown patch applied")
