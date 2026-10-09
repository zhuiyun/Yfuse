package com.yfuse.core.account

import com.yfuse.core.personal.DEFAULT_PERSONAL_PROFILE
import com.yfuse.core.personal.PersonalCollection
import com.yfuse.core.personal.PersonalEntry
import com.yfuse.core.personal.PersonalMediaRef
import com.yfuse.core.personal.PersonalSnapshot
import com.yfuse.core.personal.PersonalStamp
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncDocumentCodecTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun documentThatFitsIsStoredAsThePlainJsonEarlierBuildsRead() {
        val document = json.encodeToString(watchedEpisodes(10))

        val plaintext = assertNotNull(encodeSyncDocument(document))

        assertContentEquals(document.encodeToByteArray(), plaintext)
        assertEquals(document, decodeSyncDocument(plaintext))
    }

    @Test
    fun documentOverTheLimitIsCompressedToFitAndRestoredIntact() {
        val document = json.encodeToString(watchedEpisodes(1_000))
        assertTrue(document.encodeToByteArray().size > MAX_SYNC_PLAINTEXT_BYTES)

        val plaintext = assertNotNull(encodeSyncDocument(document))

        assertTrue(plaintext.size <= MAX_SYNC_PLAINTEXT_BYTES)
        assertEquals(document, decodeSyncDocument(plaintext))
    }

    @Test
    fun documentThatDoesNotFitEvenCompressedIsRefused() {
        val noise = Base64.getEncoder().encodeToString(Random(1).nextBytes(2 * MAX_SYNC_PLAINTEXT_BYTES))

        assertNull(encodeSyncDocument("""{"noise":"$noise"}"""))
    }

    @Test
    fun documentNoRestoreWouldAcceptIsRefusedHoweverWellItCompresses() {
        // A few kilobytes once compressed, but restoring it would cross the inflation bound.
        assertNull(encodeSyncDocument("""{"padding":"${" ".repeat(MAX_SYNC_DOCUMENT_BYTES)}"}"""))
    }

    @Test
    fun compressedDocumentThatInflatesPastTheBoundIsRejected() {
        val bomb = gzip(ByteArray(MAX_SYNC_DOCUMENT_BYTES + 1) { ' '.code.toByte() })
        assertTrue(bomb.size <= MAX_SYNC_PLAINTEXT_BYTES)

        val error = assertFailsWith<IllegalArgumentException> { decodeSyncDocument(bomb) }

        assertEquals("云端同步数据过大", error.message)
    }

    @Test
    fun truncatedCompressedDocumentIsReportedAsDamaged() {
        val plaintext = assertNotNull(encodeSyncDocument(json.encodeToString(watchedEpisodes(1_000))))
        val truncated = plaintext.copyOf(plaintext.size / 2)

        val error = assertFailsWith<IllegalArgumentException> { decodeSyncDocument(truncated) }

        assertEquals("云端同步数据已损坏", error.message)
    }

    @Test
    fun plaintextLargerThanTheServerKeepsIsRejected() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                decodeSyncDocument(ByteArray(MAX_SYNC_PLAINTEXT_BYTES + 1) { '{'.code.toByte() })
            }

        assertEquals("云端同步数据过大", error.message)
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(bytes) }
            output.toByteArray()
        }
}

/** [count] episodes watched on one media server, recorded the way the player records them. */
internal fun watchedEpisodes(count: Int): PersonalSnapshot {
    val random = Random(count)

    fun hex(bytes: Int) = random.nextBytes(bytes).joinToString("") { "%02x".format(it) }
    val server = "https://media.example.home:8920#${hex(16)}"
    val device = "p${hex(8)}"
    return PersonalSnapshot(
        entries =
            List(count) { index ->
                val episode = index % 40 + 1
                PersonalEntry(
                    profileId = DEFAULT_PERSONAL_PROFILE,
                    collection = PersonalCollection.History,
                    media =
                        PersonalMediaRef(
                            mediaKey = "tmdb:${100_000 + index / 40}/s1e$episode",
                            title = "第 $episode 集",
                            mediaType = "Episode",
                            tmdbId = random.nextInt(1_000, 1_200_000),
                            year = 2024,
                            serverId = server,
                            serverItemId = hex(16),
                        ),
                    stamp = PersonalStamp(index + 1L, device),
                    watchedAtEpochMs = 1_700_000_000_000 + index * 60_000L,
                    positionMs = random.nextLong(3_000_000),
                    durationMs = 2_700_000,
                )
            },
    )
}
