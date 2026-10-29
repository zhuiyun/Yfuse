package com.yfuse.core.sync.playback

internal fun PlaybackStateRecord.sourceBindings(): Map<String, List<String>> =
    mergePlaybackBindings(
        serverItems,
        serverId
            ?.let { server ->
                serverItemId?.let { mapOf(server to listOf(it)) }
            }.orEmpty(),
    )

internal fun mergePlaybackBindings(
    first: Map<String, List<String>>,
    second: Map<String, List<String>>,
): Map<String, List<String>> =
    (first.keys + second.keys)
        .asSequence()
        .filter(String::isNotBlank)
        .flatMap { server ->
            (first[server].orEmpty() + second[server].orEmpty())
                .asSequence()
                .filter(String::isNotBlank)
                .map { server to it }
        }.distinct()
        .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        .take(64)
        .groupBy({ it.first }, { it.second })

internal fun PlaybackStateRecord.matchesSource(
    server: String,
    item: String,
): Boolean = serverId == server && serverItemId == item || item in serverItems[server].orEmpty()
