package com.yfuse.watch

import com.yfuse.watch.account.AuthenticatedAccount
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Shared by the newer watch tests; the older suites keep their own file-private helpers. */
internal fun String.wireJson(): JsonObject = Json.parseToJsonElement(this).jsonObject

internal fun JsonObject.field(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

/** The next frame of [type], skipping others (broadcasts interleave with direct replies). */
internal suspend fun WebSocketSession.awaitType(
    type: String,
    timeoutMs: Long = 4_000L,
): JsonObject =
    withTimeout(timeoutMs) {
        var found: JsonObject? = null
        while (found == null) {
            val frame = incoming.receive() as? Frame.Text ?: continue
            val payload = frame.readText().wireJson()
            if (payload.field("type") == type) found = payload
        }
        found
    }

/** The next frame of [type] that also satisfies [predicate]. */
internal suspend fun WebSocketSession.awaitWhere(
    type: String,
    timeoutMs: Long = 4_000L,
    predicate: (JsonObject) -> Boolean,
): JsonObject =
    withTimeout(timeoutMs) {
        var found: JsonObject? = null
        while (found == null) {
            val frame = incoming.receive() as? Frame.Text ?: continue
            val payload = frame.readText().wireJson()
            if (payload.field("type") == type && predicate(payload)) found = payload
        }
        found
    }

/** Every text frame that arrives within [windowMs], or until the socket closes. */
internal suspend fun WebSocketSession.drainFor(windowMs: Long): List<JsonObject> {
    val frames = mutableListOf<JsonObject>()
    withTimeoutOrNull(windowMs) {
        while (true) {
            val frame = incoming.receiveCatching().getOrNull() ?: break
            if (frame is Frame.Text) frames += frame.readText().wireJson()
        }
    }
    return frames
}

internal fun testWatchAccount(
    userId: String,
    sessionId: String = "$userId-session",
    accessExpiresAtEpochMs: Long = Long.MAX_VALUE,
): AuthenticatedAccount =
    AuthenticatedAccount(
        userId = userId,
        sessionId = sessionId,
        username = userId,
        nickname = userId,
        avatarId = 0,
        accessExpiresAtEpochMs = accessExpiresAtEpochMs,
    )
