package com.yfuse.watch.migration

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MigrationRelayBackendTest {
    private val relayId = ByteArray(32) { (it + 1).toByte() }
    private val secret = ByteArray(32) { (it + 41).toByte() }
    private val payloadHash = ByteArray(32) { (it + 81).toByte() }

    @Test
    fun codeIsStrictlySixDigitsAndLeadingZerosSurvive() {
        val backend = backend(code = "000042")
        backend.use {
            val created = it.create(createRequest(), "198.51.100.1")
            assertEquals("000042", created.code)
            assertTrue(created.code.matches(Regex("[0-9]{6}")))
            assertEquals(
                MigrationRelayBackend.encode(secret),
                it.redeem(redeemRequest("000042"), "198.51.100.2").transferSecret,
            )
        }
    }

    @Test
    fun fiveWrongAttemptsPermanentlyConsumeAttemptBudget() {
        val backend = backend(code = "123456")
        backend.use {
            it.create(createRequest(), "198.51.100.1")
            repeat(5) { attempt ->
                val failure =
                    assertFailsWith<MigrationRelayException> {
                        it.redeem(redeemRequest("${attempt}99999".takeLast(6)), "198.51.100.2")
                    }
                assertEquals("invalid_migration_code", failure.errorCode)
            }
            assertFailsWith<MigrationRelayException> {
                it.redeem(redeemRequest("123456"), "198.51.100.2")
            }
        }
    }

    @Test
    fun expiredAndPayloadMismatchedRequestsUseSameError() {
        var now = 10_000L
        val backend = backend(code = "111111", now = { now })
        backend.use {
            it.create(createRequest(), "198.51.100.1")
            val wrongHash = ByteArray(32) { 7 }
            val mismatch =
                assertFailsWith<MigrationRelayException> {
                    it.redeem(
                        redeemRequest("111111").copy(payloadSha256 = MigrationRelayBackend.encode(wrongHash)),
                        "198.51.100.2",
                    )
                }
            now += MigrationRelayBackend.RELAY_TTL_MS + 1
            val expired =
                assertFailsWith<MigrationRelayException> {
                    it.redeem(redeemRequest("111111"), "198.51.100.2")
                }
            assertEquals(mismatch.errorCode, expired.errorCode)
            assertEquals(mismatch.message, expired.message)
        }
    }

    @Test
    fun exactlyOneConcurrentRedemptionWins() {
        val backend = backend(code = "654321")
        backend.use {
            it.create(createRequest(), "198.51.100.1")
            val ready = CountDownLatch(8)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(8)
            val results =
                (0 until 8).map { index ->
                    pool.submit<Boolean> {
                        ready.countDown()
                        start.await()
                        runCatching {
                            it.redeem(redeemRequest("654321"), "198.51.100.${index + 2}")
                        }.isSuccess
                    }
                }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
            pool.shutdownNow()
        }
    }

    @Test
    fun wrappedSecretSurvivesBackendRestartWithoutStoringPlaintext() {
        val tempDir = Files.createTempDirectory("yfuse-migration-relay-test").toFile()
        val database = File(tempDir, "relay.db")
        val masterKey = ByteArray(32) { (it + 101).toByte() }
        MigrationRelayBackend.sqlite(database, masterKey, random = codeGeneratorRandom("777777")).use {
            val created = it.create(createRequest(), "198.51.100.1")
            assertEquals("777777", created.code)
        }
        val rawDatabase = database.readBytes()
        assertEquals(-1, rawDatabase.indexOfSubsequence(relayId))
        assertEquals(-1, rawDatabase.indexOfSubsequence(secret))
        MigrationRelayBackend.sqlite(database, masterKey, random = codeGeneratorRandom("888888")).use {
            assertEquals(
                MigrationRelayBackend.encode(secret),
                it.redeem(redeemRequest("777777"), "198.51.100.2").transferSecret,
            )
        }
        tempDir.deleteRecursively()
    }

    @Test
    fun malformedCodeIsUniformlyRejected() {
        val backend = backend(code = "123456")
        backend.use {
            it.create(createRequest(), "198.51.100.1")
            listOf("12345", "1234567", "12A456", " 12345").forEach { malformed ->
                val error =
                    assertFailsWith<MigrationRelayException> {
                        it.redeem(redeemRequest(malformed), "198.51.100.2")
                    }
                assertEquals("invalid_migration_code", error.errorCode)
            }
        }
    }

    private fun backend(
        code: String,
        now: () -> Long = { 1_000L },
    ) = MigrationRelayBackend.inMemory(
        masterKey = ByteArray(32) { (it + 11).toByte() },
        nowEpochMs = now,
        codeGenerator = { code },
    )

    private fun createRequest() =
        CreateMigrationRelayRequest(
            MigrationRelayBackend.encode(relayId),
            MigrationRelayBackend.encode(secret),
            MigrationRelayBackend.encode(payloadHash),
        )

    private fun redeemRequest(code: String) =
        RedeemMigrationRelayRequest(
            MigrationRelayBackend.encode(relayId),
            code,
            MigrationRelayBackend.encode(payloadHash),
        )

    private fun codeGeneratorRandom(code: String): java.security.SecureRandom =
        object : java.security.SecureRandom() {
            override fun nextInt(bound: Int): Int = code.toInt()
        }
}

private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
    if (needle.isEmpty()) return 0
    for (index in 0..size - needle.size) {
        if (needle.indices.all { this[index + it] == needle[it] }) return index
    }
    return -1
}
