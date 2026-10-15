package com.yfuse.feature.player

import com.arkivanov.mvikotlin.core.store.Reducer

internal object PlayerReducer : Reducer<PlayerState, PlayerMsg> {
    override fun PlayerState.reduce(msg: PlayerMsg): PlayerState =
        when (msg) {
            PlayerMsg.Loading ->
                copy(
                    loading = true,
                    items = emptyList(),
                    error = null,
                    enrichmentPending = false,
                )
            is PlayerMsg.Ready ->
                copy(
                    loading = false,
                    items = msg.items,
                    startIndex = msg.startIndex,
                    startPositionMs = msg.startMs,
                    error = null,
                    enrichmentPending = true,
                )
            is PlayerMsg.QueueEnriched -> {
                val selected = items.getOrNull(startIndex)
                val enriched =
                    msg.items.map { next ->
                        val current = items.firstOrNull { it.id == next.id && it.serverId == next.serverId }
                        current?.withQueueMetadata(next) ?: next
                    }
                copy(
                    items = enriched,
                    startIndex =
                        enriched
                            .indexOfFirst { it.id == selected?.id && it.serverId == selected?.serverId }
                            .coerceAtLeast(
                                0,
                            ),
                )
            }
            is PlayerMsg.ServerFallbacks ->
                copy(
                    items =
                        items.map { item ->
                            if (item.id == msg.itemId &&
                                item.serverId == msg.serverId
                            ) {
                                item.copy(serverFallbacks = msg.items)
                            } else {
                                item
                            }
                        },
                )
            PlayerMsg.EnrichmentFinished -> copy(enrichmentPending = false)
            is PlayerMsg.Failed ->
                copy(
                    loading = false,
                    items = emptyList(),
                    error = msg.message,
                    enrichmentPending = false,
                )
        }
}
