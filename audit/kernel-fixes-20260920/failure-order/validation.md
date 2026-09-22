# Enhanced failure publication ordering

The demux control timeout previously reached NativeEnhancedYPlayer.publishFailure(), which called session.close() before publishing Failed. If the old reader ignored interruption/cancellation, close waited at the resource ownership barrier and prevented the error state from reaching the UI.

The minimal fix sets prepared/requestedPlay false and publishes Failed, stopped playback intent, buffering=false and stopped output diagnostics before the existing session.close()/proxy.close() cleanup. Cleanup still runs on the same worker and keeps its ownership barrier. No new decoder is authorized by publication of the failure.

Validation:

- Source review confirms the Failed state update finishes before the potentially blocking session.close() call (AndroidNativeEnhancedYPlayer.kt, publishFailure).
- Single-file ktlint formatting succeeded; format-files.log is empty on success.
- Existing AndroidDemuxReadControlTest `cleanup retains native ownership while a timed out read ignores cancellation` checks that close cannot proceed until the blocked owner returns.
- Existing PlaybackEngineSlotTest behavior tests check that blocked/failed/timed-out release, request replacement and Root exit/reentry cannot create the next engine early. The earlier standalone run passed all 12 slot/cleanup tests; this ordering-only follow-up did not rerun them independently.
- No implementation-mirroring helper or static source-text assertion was added. Root coordinates the final full build/tests against the final source fingerprint. Real NativeEnhanced failure publication during blocked native IO remains a device validation item.
