package com.yfuse.core.trakt

import com.yfuse.core.personal.PersonalCollection
import com.yfuse.core.security.SECURE_STORE_MAX_VALUE_BYTES
import com.yfuse.core.security.SecureStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Chunked encrypted snapshots: publish the pointer only after all chunks are durable. */
internal class TraktSyncCheckpoint(
    private val store: SecureStore,
    private val namespace: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Head(
        val generation: String,
        val chunks: Int,
    )

    fun read(collection: PersonalCollection): Map<String, TraktSyncMark> {
        val key = key(collection)
        val head = readHead(key) ?: return emptyMap()
        val result = mutableMapOf<String, TraktSyncMark>()
        repeat(head.chunks) { index ->
            val bytes = store.get("$key:${head.generation}:$index") ?: error("同步检查点不完整")
            try {
                check(bytes.size <= SECURE_STORE_MAX_VALUE_BYTES)
                val chunk = json.decodeFromString<Map<String, TraktSyncMark>>(bytes.decodeToString())
                check(chunk.size <= 50 && chunk.keys.none { it in result })
                result.putAll(chunk)
            } finally {
                bytes.fill(0)
            }
        }
        return result
    }

    fun write(
        collection: PersonalCollection,
        marks: Map<String, TraktSyncMark>,
    ) {
        require(marks.size <= 1_000)
        val key = key(collection)
        val previous = readHead(key)
        val chunks = marks.entries.chunked(50)
        val next = Head(UUID.randomUUID().toString(), chunks.size)
        try {
            chunks.forEachIndexed { index, chunk ->
                put("$key:${next.generation}:$index", json.encodeToString(chunk.associate { it.toPair() }))
            }
            put(key, json.encodeToString(next))
        } catch (failure: Exception) {
            clean(key, next)
            throw failure
        }
        previous?.let { clean(key, it) }
    }

    private fun readHead(key: String): Head? {
        val bytes = store.get(key) ?: return null
        try {
            check(bytes.size <= 1024)
            return json.decodeFromString<Head>(bytes.decodeToString()).also {
                check(it.chunks in 0..20 && it.generation.matches(Regex("[a-f0-9-]{36}")))
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun put(
        key: String,
        value: String,
    ) {
        val bytes = value.encodeToByteArray()
        try {
            check(bytes.size <= SECURE_STORE_MAX_VALUE_BYTES)
            store.put(key, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun clean(
        key: String,
        head: Head,
    ) {
        repeat(head.chunks) { runCatching { store.remove("$key:${head.generation}:$it") } }
    }

    private fun key(collection: PersonalCollection) = "$namespace:${collection.name}"
}
