package com.yfuse.tv.integration

/**
 * Typed seam for the allowlisted `engage-tv` SDK.
 *
 * The current APK does not depend on that SDK, so production code deliberately supplies no
 * adapter. Once enrollment, user-profile mapping and cross-device consent are complete, a
 * dependency-backed adapter can atomically publish the full list through
 * `publishContinuationCluster`; no reflection is used to manufacture an apparent success.
 */
interface EngageContinuationAdapter {
    suspend fun isContinuationServiceAvailable(): Boolean

    suspend fun replaceContinuation(entries: List<ContinueWatchingEntry>): Result<Unit>
}

enum class EngageIntegrationState {
    MissingSdk,
    SdkPresentAdapterMissing,
    ServiceUnavailable,
    Ready,
}

class EngageSdkPresenceProbe(
    private val classLoader: ClassLoader = EngageSdkPresenceProbe::class.java.classLoader,
) {
    fun sdkPresent(): Boolean =
        REQUIRED_CLASSES.all { className ->
            runCatching { Class.forName(className, false, classLoader) }.isSuccess
        }

    private companion object {
        val REQUIRED_CLASSES =
            listOf(
                "com.google.android.engage.service.AppEngagePublishClient",
                "com.google.android.engage.service.PublishContinuationClusterRequest",
            )
    }
}

class EngageContinueWatchingPublisher(
    private val adapter: EngageContinuationAdapter? = null,
    private val presenceProbe: EngageSdkPresenceProbe = EngageSdkPresenceProbe(),
    private val sdkPresent: () -> Boolean = presenceProbe::sdkPresent,
) : ContinueWatchingPublisher {
    suspend fun integrationState(): EngageIntegrationState {
        if (!sdkPresent()) return EngageIntegrationState.MissingSdk
        val configured = adapter ?: return EngageIntegrationState.SdkPresentAdapterMissing
        return if (configured.isContinuationServiceAvailable()) {
            EngageIntegrationState.Ready
        } else {
            EngageIntegrationState.ServiceUnavailable
        }
    }

    override suspend fun replace(entries: List<ContinueWatchingEntry>): ContinueWatchingPublishResult {
        return when (integrationState()) {
            EngageIntegrationState.MissingSdk ->
                ContinueWatchingPublishResult.Unavailable(
                    backend = ContinueWatchingBackend.Engage,
                    reason = "engage_tv_sdk_missing",
                    terminal = true,
                )

            EngageIntegrationState.SdkPresentAdapterMissing ->
                ContinueWatchingPublishResult.Unavailable(
                    backend = ContinueWatchingBackend.Engage,
                    reason = "engage_adapter_not_configured",
                    terminal = true,
                )

            EngageIntegrationState.ServiceUnavailable ->
                ContinueWatchingPublishResult.Unavailable(
                    backend = ContinueWatchingBackend.Engage,
                    reason = "engage_continuation_service_unavailable",
                    terminal = false,
                )

            EngageIntegrationState.Ready -> {
                val result = adapter?.replaceContinuation(entries)
                if (result?.isSuccess == true) {
                    ContinueWatchingPublishResult.Published(
                        backend = ContinueWatchingBackend.Engage,
                        publishedCount = entries.size,
                    )
                } else {
                    ContinueWatchingPublishResult.Failed(
                        backend = ContinueWatchingBackend.Engage,
                        reason = "engage_publish_failed",
                        retryable = true,
                    )
                }
            }
        }
    }
}

/** Uses the legacy on-device provider only when Engage is genuinely unavailable. */
internal class EngageThenWatchNextPublisher(
    private val engage: ContinueWatchingPublisher,
    private val watchNext: ContinueWatchingPublisher,
) : ContinueWatchingPublisher {
    override suspend fun replace(entries: List<ContinueWatchingEntry>): ContinueWatchingPublishResult {
        return when (val primary = engage.replace(entries)) {
            is ContinueWatchingPublishResult.Published -> primary
            is ContinueWatchingPublishResult.Failed -> primary
            is ContinueWatchingPublishResult.Unavailable -> watchNext.replace(entries)
        }
    }
}
