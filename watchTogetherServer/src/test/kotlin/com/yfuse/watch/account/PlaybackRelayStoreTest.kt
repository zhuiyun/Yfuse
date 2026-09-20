package com.yfuse.watch.account

import com.yfuse.watch.protocol.PlaybackMissingEntity
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackRelayStoreTest {
    @Test
    fun anEvictedEntityCanBeRecreatedWithoutBypassingCas() {
        PlaybackRelayStore.inMemoryForTests(maxEntitiesPerUser = 1).use { store ->
            val first = entity('A', "mutation-first", 1)
            val original = store.push("user-a", PlaybackPushRequest(listOf(PlaybackPutItem(0L, first))), 1_000L)
            store.push(
                "user-a",
                PlaybackPushRequest(listOf(PlaybackPutItem(0L, entity('B', "mutation-other", 2)))),
                2_000L,
            )
            val updated = first.copy(mutationId = "mutation-updated")

            val missing = store.push("user-a", PlaybackPushRequest(listOf(PlaybackPutItem(1L, updated))), 3_000L)

            assertTrue(missing.accepted.isEmpty())
            assertTrue(missing.conflicts.isEmpty())
            assertEquals(listOf(PlaybackMissingEntity(first.entityKey, updated.mutationId, 1L)), missing.missing)
            assertEquals(2L, missing.cursor, "A missing response must not advance the relay cursor")
            val recreated = store.push("user-a", PlaybackPushRequest(listOf(PlaybackPutItem(0L, updated))), 4_000L)
            assertEquals(3L, recreated.accepted.single().cursor)
            assertTrue(recreated.missing.isEmpty())
            assertTrue(recreated.accepted.single().cursor > original.accepted.single().cursor)
            assertEquals(
                updated.mutationId,
                store
                    .pull("user-a", 0L, 10)
                    .changes
                    .single()
                    .mutationId,
            )
        }
    }

    @Test
    fun aConcurrentRecreationWinsOverTheMissingEntityRetry() {
        PlaybackRelayStore.inMemoryForTests(maxEntitiesPerUser = 1).use { store ->
            val old = entity('A', "mutation-old", 1)
            store.push("user-a", PlaybackPushRequest(listOf(PlaybackPutItem(0L, old))), 1_000L)
            store.push(
                "user-a",
                PlaybackPushRequest(listOf(PlaybackPutItem(0L, entity('B', "mutation-other", 2)))),
                2_000L,
            )
            val retry = old.copy(mutationId = "mutation-retry")
            assertEquals(
                1,
                store.push("user-a", PlaybackPushRequest(listOf(PlaybackPutItem(1L, retry))), 3_000L).missing.size,
            )
            val concurrent = entity('A', "mutation-concurrent", 3)
            store.push("user-a", PlaybackPushRequest(listOf(PlaybackPutItem(0L, concurrent))), 4_000L)

            val response = store.push("user-a", PlaybackPushRequest(listOf(PlaybackPutItem(0L, retry))), 5_000L)

            assertTrue(response.accepted.isEmpty())
            assertTrue(response.missing.isEmpty())
            assertEquals(concurrent.mutationId, response.conflicts.single().mutationId)
            assertEquals(
                concurrent.ciphertext,
                store
                    .pull("user-a", 0L, 10)
                    .changes
                    .single()
                    .ciphertext,
            )
        }
    }

    @Test
    fun accountQuotaEvictsOldestEntityWithoutAffectingOtherUsers() {
        PlaybackRelayStore.inMemoryForTests(maxEntitiesPerUser = 2).use { store ->
            listOf('A', 'B', 'C').forEachIndexed { index, key ->
                store.push(
                    userId = "user-a",
                    request =
                        PlaybackPushRequest(
                            listOf(PlaybackPutItem(0L, entity(key, "mutation-$key", index + 1))),
                        ),
                    nowEpochMs = 1_000L + index,
                )
            }
            store.push(
                userId = "user-b",
                request = PlaybackPushRequest(listOf(PlaybackPutItem(0L, entity('Z', "mutation-z", 9)))),
                nowEpochMs = 2_000L,
            )

            assertEquals(2, store.entityCountForTests("user-a"))
            assertEquals(
                setOf("B", "C"),
                store
                    .pull("user-a", 0L, 10)
                    .changes
                    .map { it.entityKey.first().toString() }
                    .toSet(),
            )
            assertEquals(1, store.entityCountForTests("user-b"))
        }
    }

    @Test
    fun mutationIsIdempotentAndStaleBaseCursorReturnsConflict() {
        PlaybackRelayStore.inMemoryForTests().use { store ->
            val first = entity(keyChar = 'A', mutationId = "mutation-1", fill = 1)
            val accepted =
                store.push(
                    userId = "user-a",
                    request = PlaybackPushRequest(listOf(PlaybackPutItem(0L, first))),
                    nowEpochMs = 1_000L,
                )
            assertEquals(1L, accepted.cursor)
            assertEquals(1L, accepted.accepted.single().cursor)
            assertTrue(accepted.conflicts.isEmpty())

            val retry =
                store.push(
                    userId = "user-a",
                    request = PlaybackPushRequest(listOf(PlaybackPutItem(0L, first))),
                    nowEpochMs = 2_000L,
                )
            assertEquals(1L, retry.cursor)
            assertEquals(1L, retry.accepted.single().cursor)
            assertTrue(retry.conflicts.isEmpty())

            val replacement = entity(keyChar = 'A', mutationId = "mutation-2", fill = 2)
            val stale =
                store.push(
                    userId = "user-a",
                    request = PlaybackPushRequest(listOf(PlaybackPutItem(0L, replacement))),
                    nowEpochMs = 3_000L,
                )
            assertTrue(stale.accepted.isEmpty())
            assertEquals(1L, stale.conflicts.single().cursor)
            assertEquals("mutation-1", stale.conflicts.single().mutationId)

            val updated =
                store.push(
                    userId = "user-a",
                    request = PlaybackPushRequest(listOf(PlaybackPutItem(1L, replacement))),
                    nowEpochMs = 4_000L,
                )
            assertEquals(2L, updated.cursor)
            assertEquals(2L, updated.accepted.single().cursor)
        }
    }

    @Test
    fun pullUsesMonotonicPageCursorWithoutSkippingEntities() {
        PlaybackRelayStore.inMemoryForTests().use { store ->
            store.push(
                userId = "user-a",
                request =
                    PlaybackPushRequest(
                        listOf(
                            PlaybackPutItem(0L, entity('A', "mutation-a", 1)),
                            PlaybackPutItem(0L, entity('B', "mutation-b", 2)),
                        ),
                    ),
                nowEpochMs = 1_000L,
            )

            val firstPage = store.pull("user-a", afterCursor = 0L, limit = 1)
            assertEquals(1, firstPage.changes.size)
            assertEquals(1L, firstPage.cursor)
            assertTrue(firstPage.hasMore)

            val secondPage = store.pull("user-a", afterCursor = firstPage.cursor, limit = 1)
            assertEquals(1, secondPage.changes.size)
            assertEquals(2L, secondPage.cursor)
            assertFalse(secondPage.hasMore)
            assertEquals(
                setOf("A", "B"),
                (firstPage.changes + secondPage.changes).map { it.entityKey.first().toString() }.toSet(),
            )
        }
    }

    @Test
    fun pushHighWaterMarkCannotReplaceTheClientsPullCheckpoint() {
        PlaybackRelayStore.inMemoryForTests().use { store ->
            val initial =
                store.push(
                    userId = "user-a",
                    request = PlaybackPushRequest(listOf(PlaybackPutItem(0L, entity('A', "mutation-a", 1)))),
                    nowEpochMs = 1_000L,
                )
            val pullCheckpoint = store.pull("user-a", afterCursor = 0L, limit = 10).cursor
            assertEquals(initial.cursor, pullCheckpoint)

            store.push(
                userId = "user-a",
                request = PlaybackPushRequest(listOf(PlaybackPutItem(0L, entity('B', "mutation-b", 2)))),
                nowEpochMs = 2_000L,
            )
            val ownPush =
                store.push(
                    userId = "user-a",
                    request = PlaybackPushRequest(listOf(PlaybackPutItem(0L, entity('C', "mutation-c", 3)))),
                    nowEpochMs = 3_000L,
                )

            assertEquals(3L, ownPush.cursor)
            assertTrue(store.pull("user-a", afterCursor = ownPush.cursor, limit = 10).changes.isEmpty())
            assertEquals(
                setOf("B", "C"),
                store
                    .pull("user-a", afterCursor = pullCheckpoint, limit = 10)
                    .changes
                    .map { it.entityKey.first().toString() }
                    .toSet(),
            )
        }
    }

    @Test
    fun userNamespacesAreIndependent() {
        PlaybackRelayStore.inMemoryForTests().use { store ->
            val sameOpaqueKey = entity('A', "mutation-a", 1)
            val userA =
                store.push(
                    userId = "user-a",
                    request = PlaybackPushRequest(listOf(PlaybackPutItem(0L, sameOpaqueKey))),
                    nowEpochMs = 1_000L,
                )
            val userB =
                store.push(
                    userId = "user-b",
                    request =
                        PlaybackPushRequest(
                            listOf(
                                PlaybackPutItem(
                                    0L,
                                    sameOpaqueKey.copy(mutationId = "mutation-b"),
                                ),
                            ),
                        ),
                    nowEpochMs = 2_000L,
                )

            assertEquals(1L, userA.cursor)
            assertEquals(1L, userB.cursor)
            assertEquals(
                "mutation-a",
                store
                    .pull("user-a", 0L, 10)
                    .changes
                    .single()
                    .mutationId,
            )
            assertEquals(
                "mutation-b",
                store
                    .pull("user-b", 0L, 10)
                    .changes
                    .single()
                    .mutationId,
            )
        }
    }

    private fun entity(
        keyChar: Char,
        mutationId: String,
        fill: Int,
    ): EncryptedPlaybackEntity =
        EncryptedPlaybackEntity(
            entityKey = keyChar.toString().repeat(43),
            mutationId = mutationId,
            nonce = encode(ByteArray(12) { fill.toByte() }),
            ciphertext = encode(ByteArray(32) { (fill + 10).toByte() }),
        )

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
