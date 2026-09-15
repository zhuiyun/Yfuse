package com.yfuse.core.personal

/** Lamport versions plus device-id tie breaking give both devices the same merge result. */
fun mergePersonalSnapshots(
    local: PersonalSnapshot,
    remote: PersonalSnapshot,
): PersonalSnapshot {
    validatePersonalSnapshot(local)
    validatePersonalSnapshot(remote)
    return PersonalSnapshot(
        profiles =
            mergeRows(
                local.profiles + remote.profiles,
                PersonalProfile::id,
                PersonalProfile::stamp,
                PersonalProfile::deleted,
            ),
        entries =
            mergeRows(
                local.entries + remote.entries,
                PersonalEntry::identity,
                PersonalEntry::stamp,
                PersonalEntry::deleted,
            ),
        follows =
            mergeRows(
                local.follows + remote.follows,
                PersonalFollow::identity,
                PersonalFollow::stamp,
                PersonalFollow::deleted,
            ),
        guardianPin =
            listOfNotNull(local.guardianPin, remote.guardianPin)
                .maxWithOrNull(compareBy<PersonalPin> { it.stamp }.thenBy { it.salt }.thenBy { it.hash }),
    ).also(::validatePersonalSnapshot)
}

private fun <T> mergeRows(
    rows: List<T>,
    key: (T) -> String,
    stamp: (T) -> PersonalStamp,
    deleted: (T) -> Boolean,
): List<T> =
    rows.groupBy(key).toSortedMap().values.map { versions ->
        versions.maxWith(compareBy<T> { stamp(it) }.thenBy { deleted(it) }.thenBy { it.toString() })
    }

fun validatePersonalSnapshot(snapshot: PersonalSnapshot) {
    require(snapshot.schemaVersion == 1) { "不支持的个人资料版本" }
    val stamps =
        snapshot.profiles.map { it.stamp } + snapshot.entries.map { it.stamp } +
            snapshot.follows.map { it.stamp } + listOfNotNull(snapshot.guardianPin?.stamp)
    require(stamps.all { it.counter >= 0 && it.counter < Long.MAX_VALUE && it.deviceId.length <= 128 }) {
        "个人数据版本无效"
    }
    require(snapshot.profiles.size <= 24 && snapshot.entries.size <= 1_000 && snapshot.follows.size <= 1_000) {
        "个人数据过多，请先整理清单"
    }
    require(snapshot.profiles.any { it.id == DEFAULT_PERSONAL_PROFILE && !it.deleted && !it.child }) { "缺少主要资料" }
    require(
        snapshot.profiles
            .map { it.id }
            .distinct()
            .size == snapshot.profiles.size,
    ) { "重复的资料" }
    snapshot.profiles.forEach {
        require(it.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && it.name.isNotBlank() && it.name.length <= 40)
        require(it.serverIds.size <= 100 && it.serverIds.all { id -> id.isNotBlank() && id.length <= 2_048 })
        require(!it.child || snapshot.guardianPin != null) { "儿童资料需要家长 PIN" }
    }
    snapshot.entries.forEach {
        require(it.profileId in snapshot.profiles.map(PersonalProfile::id))
        require(it.media.mediaKey.isNotBlank() && it.media.mediaKey.length <= 512)
        require(it.media.title.isNotBlank() && it.media.title.length <= 240 && it.media.mediaType.length <= 40)
        require(
            it.media.posterPath
                .orEmpty()
                .length <= 2_048 &&
                it.media.serverId
                    .orEmpty()
                    .length <= 2_048,
        )
        require(it.positionMs >= 0 && it.durationMs >= 0)
    }
    snapshot.follows.forEach {
        require(
            it.profileId in snapshot.profiles.map(PersonalProfile::id) && it.series.tmdbId > 0,
        )
    }
    snapshot.guardianPin?.let {
        require(
            it.iterations in 100_000..600_000 && it.salt.length in 20..128 && it.hash.length in 40..128,
        )
    }
}
