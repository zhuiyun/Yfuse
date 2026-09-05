from pathlib import Path

path = Path("composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerStore.kt")
text = path.read_text()
old = '''            scope.launch {
                // This is a user-facing wall-clock deadline, not coroutine virtual time.
                // Keeping the timer on Default prevents runTest from jumping directly to
                // 30 s while MockEngine/serialization is still completing on another
                // dispatcher, which used to cancel otherwise healthy queue builds.
                withContext(Dispatchers.Default) {
                    delay(queueLoadTimeoutMs)
                }
                if (loadAttempt != attempt || !job.isActive) return@launch
                job.cancel()
                AppLog.warning(
                    category = "feature.player",
                    event = "queue_load_timeout",
                    message = "Playback queue preparation timed out",
                    attributes = mapOf("timeoutMs" to queueLoadTimeoutMs.toString()),
                )
                dispatch(PlayerMsg.Failed("播放准备超时，请检查服务器连接后重试"))
            }
'''
new = '''            scope.launch {
                // Race queue completion against a real wall-clock deadline. A completed queue
                // ends this watcher immediately, so no sleeping timeout coroutine can outlive the
                // load/store and leak a late dispatch into the next test or a disposed screen.
                val timedOut =
                    withContext(Dispatchers.Default) {
                        withTimeoutOrNull(queueLoadTimeoutMs) {
                            job.join()
                            false
                        } ?: true
                    }
                if (!timedOut || loadAttempt != attempt || !job.isActive) return@launch
                job.cancel()
                AppLog.warning(
                    category = "feature.player",
                    event = "queue_load_timeout",
                    message = "Playback queue preparation timed out",
                    attributes = mapOf("timeoutMs" to queueLoadTimeoutMs.toString()),
                )
                dispatch(PlayerMsg.Failed("播放准备超时，请检查服务器连接后重试"))
            }
'''
if old not in text:
    raise SystemExit("expected timeout block not found")
path.write_text(text.replace(old, new, 1))
