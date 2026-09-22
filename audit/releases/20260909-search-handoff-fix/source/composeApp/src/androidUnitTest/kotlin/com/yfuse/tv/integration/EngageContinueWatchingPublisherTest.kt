package com.yfuse.tv.integration

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EngageContinueWatchingPublisherTest {
    @Test
    fun missingSdkAndMissingAdapterAreReportedWithoutPretendingToPublish() =
        runTest {
            val missingSdk = EngageContinueWatchingPublisher(sdkPresent = { false })
            val adapterMissing = EngageContinueWatchingPublisher(sdkPresent = { true })

            assertEquals(EngageIntegrationState.MissingSdk, missingSdk.integrationState())
            assertEquals(EngageIntegrationState.SdkPresentAdapterMissing, adapterMissing.integrationState())
            assertIs<ContinueWatchingPublishResult.Unavailable>(missingSdk.replace(listOf(entry())))
            assertIs<ContinueWatchingPublishResult.Unavailable>(adapterMissing.replace(listOf(entry())))
        }

    @Test
    fun configuredAdapterPublishesOnlyAfterAvailabilityCheck() =
        runTest {
            var replaceCalls = 0
            val unavailable =
                EngageContinueWatchingPublisher(
                    adapter = fakeAdapter(available = false) { replaceCalls++ },
                    sdkPresent = { true },
                )
            val available =
                EngageContinueWatchingPublisher(
                    adapter = fakeAdapter(available = true) { replaceCalls++ },
                    sdkPresent = { true },
                )

            assertEquals(EngageIntegrationState.ServiceUnavailable, unavailable.integrationState())
            assertIs<ContinueWatchingPublishResult.Unavailable>(unavailable.replace(listOf(entry())))
            assertEquals(0, replaceCalls)
            val published = assertIs<ContinueWatchingPublishResult.Published>(available.replace(listOf(entry())))
            assertEquals(ContinueWatchingBackend.Engage, published.backend)
            assertEquals(1, replaceCalls)
        }

    @Test
    fun fallbackRunsOnlyForUnavailableAndNotForPublishFailure() =
        runTest {
            var fallbackCalled = false
            val fallback =
                ContinueWatchingPublisher {
                    fallbackCalled = true
                    ContinueWatchingPublishResult.Published(ContinueWatchingBackend.WatchNext, it.size)
                }
            val unavailable =
                EngageThenWatchNextPublisher(
                    engage = ContinueWatchingPublisher {
                        ContinueWatchingPublishResult.Unavailable(
                            ContinueWatchingBackend.Engage,
                            "missing",
                            terminal = true,
                        )
                    },
                    watchNext = fallback,
                )
            assertIs<ContinueWatchingPublishResult.Published>(unavailable.replace(listOf(entry())))
            assertTrue(fallbackCalled)

            fallbackCalled = false
            val failed =
                EngageThenWatchNextPublisher(
                    engage = ContinueWatchingPublisher {
                        ContinueWatchingPublishResult.Failed(
                            ContinueWatchingBackend.Engage,
                            "network",
                            retryable = true,
                        )
                    },
                    watchNext = fallback,
                )
            assertIs<ContinueWatchingPublishResult.Failed>(failed.replace(listOf(entry())))
            assertFalse(fallbackCalled)
        }

    private fun fakeAdapter(
        available: Boolean,
        onReplace: () -> Unit,
    ): EngageContinuationAdapter =
        object : EngageContinuationAdapter {
            override suspend fun isContinuationServiceAvailable(): Boolean = available

            override suspend fun replaceContinuation(entries: List<ContinueWatchingEntry>): Result<Unit> {
                onReplace()
                return Result.success(Unit)
            }
        }

    private fun entry() =
        ContinueWatchingEntry(
            identity =
                ContinueWatchingIdentity(
                    ContinueWatchingScope(TvMediaProvider.Emby, "server", "profile"),
                    "item",
                ),
            mediaType = ContinueWatchingMediaType.Movie,
            title = "Title",
            positionMs = 180_000L,
            durationMs = 3_600_000L,
            lastEngagementEpochMs = 1L,
        )
}
