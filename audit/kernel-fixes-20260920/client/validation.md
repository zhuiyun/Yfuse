# MDK state and engine replacement validation (2026-09-20)

## Changes

- MDK load epochs reject stale transitions. Fallback INVALID settling uses monotonic elapsed time after actual media submission, rather than consuming a polling-count budget during encoder cleanup or event bursts. The settling decision precedes terminal-error handling, seeks, end handling, and old position reads.
- MDK JNI playback evidence selects the explicitly selected audio stream and emits no stream codec data when the selected index is absent/out of range.
- PlayerRoot retains its complete Compose call graph while a PreparingVideoEngine holds playback intent, seek, speed, and complete media queue objects. Construction runs from the slot's coroutine after prior resources retire. Binding retains the actual requested engine kind and unique native-crash owner.
- Process-scoped retirement and construction reservations survive PlayerRoot disposal. A canceled noncooperative construction must return and retire its candidate before any later request/root can construct. A timeout or release failure never opens the barrier. Owner diagnostics clear only after completed release, using rendering evidence captured before release resets state.
- Exo and MDK expose awaited release completion on their existing owner thread. Cleanup attempts subsequent resources even after an earlier step fails and aggregates errors. MPV native completion is reported only after destroy succeeds; stop failure still attempts destroy.

## Executed validation

- `test-slot.ps1`: compiled current PlaybackEngineSlot.kt and PlaybackResourceCleanup.kt with their current behavior tests using the cached Kotlin 2.4.20 compiler, actual existing app contracts/classes, coroutines 1.11.0, serialization 1.8.0 and JUnit 4.13.2. Result: **12 tests passed** (10 slot tests + 2 cleanup tests). See slot-compile.log and slot-tests.log.
- Slot tests cover blocked release, controls and queue during waiting, request supersession, close/reenter barriers, timeout, release failure/manual retry, noncooperative factory cancellation, crash-owner lifetime, frozen rendering evidence, and diagnostic cleanup failure.
- `format.ps1`: scoped ktlint formatting succeeded for 10 owned Kotlin files listed in format-paths.json.
- Earlier MDK isolated source validation: **10 tests passed** (5 new MdkLoadStateGate tests + existing MDK/MPV end-state tests). See `audit/health-fixes-20260920/mdk/tests.log` and test-isolated.ps1. New tests cover initial failure, event bursts, encoder-cleanup delay, old epochs, and healthy replacement.

## Limits

- No Gradle, APK packaging, or native compilation was run by this subtask. Root coordinates full Kotlin/Compose/JNI validation and the separately owned WatchGatedPlayback control tests.
- Isolated tests do not instantiate real Media3, MDK, or MPV decoders. Device validation remains necessary for output evidence, visible handover, SDK teardown completion, and release timing.
- Waiting for the release barrier suspends the coroutine and does not use runBlocking. Existing synchronous Exo/MDK SDK release phases remain on their required/current owner thread; this change does not establish that those native calls are short or remove their possible UI-thread cost.
- A factory must clean up resources if it throws before returning an engine. Current production construction is synchronous on its existing owner thread; the slot additionally handles late returned engines from a suspended/noncooperative factory.
