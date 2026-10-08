package com.yfuse.core.personal

/**
 * The row limits every build since 个人内容 enforces on the whole list, tombstones included.
 * Builds that predate [boundPersonalSnapshot] refuse a document above them, so a document never
 * grows past them; what has to give is decided by [boundPersonalSnapshot].
 */
internal const val MAX_PERSONAL_PROFILES = 24
internal const val MAX_PERSONAL_ENTRIES = 1_000
internal const val MAX_PERSONAL_FOLLOWS = 1_000

/** Lamport counters grow by one per change; a counter this large can only come from a bad writer. */
private const val MAX_PERSONAL_STAMP_COUNTER = Long.MAX_VALUE / 2

/**
 * Lamport versions plus device-id tie breaking give both devices the same merge result. A merge
 * never fails on what one side holds: rows no build could have written are dropped, and a union
 * over the row limits is bounded, so one bad or oversized document cannot stop sync for good.
 */
fun mergePersonalSnapshots(
    local: PersonalSnapshot,
    remote: PersonalSnapshot,
): PersonalSnapshot {
    val left = sanitizePersonalSnapshot(local)
    val right = sanitizePersonalSnapshot(remote)
    return boundPersonalSnapshot(
        PersonalSnapshot(
            profiles =
                mergeRows(
                    left.profiles + right.profiles,
                    PersonalProfile::id,
                    PersonalProfile::stamp,
                    PersonalProfile::deleted,
                ),
            entries =
                mergeRows(
                    left.entries + right.entries,
                    PersonalEntry::identity,
                    PersonalEntry::stamp,
                    PersonalEntry::deleted,
                ),
            follows =
                mergeRows(
                    left.follows + right.follows,
                    PersonalFollow::identity,
                    PersonalFollow::stamp,
                    PersonalFollow::deleted,
                ),
            guardianPin =
                listOfNotNull(left.guardianPin, right.guardianPin)
                    .maxWithOrNull(compareBy<PersonalPin> { it.stamp }.thenBy { it.salt }.thenBy { it.hash }),
        ),
        dropLiveCurated = true,
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

/**
 * Keeps every row a valid build could have written and drops the rest, instead of rejecting the
 * whole document for one of them. Only a document of another schema is refused outright.
 */
fun sanitizePersonalSnapshot(snapshot: PersonalSnapshot): PersonalSnapshot {
    require(snapshot.schemaVersion == 1) { "不支持的个人资料版本" }
    val guardianPin = snapshot.guardianPin?.takeIf { it.isValid() && it.stamp.isSane() }
    val profiles =
        snapshot.profiles
            .filter { it.isValid() && it.stamp.isSane() && (!it.child || guardianPin != null) }
            .let { kept ->
                // The main profile is what a device falls back to; it can be renamed but never lost.
                if (kept.any { it.id == DEFAULT_PERSONAL_PROFILE && !it.deleted && !it.child }) {
                    kept
                } else {
                    kept.filterNot { it.id == DEFAULT_PERSONAL_PROFILE } +
                        PersonalProfile(DEFAULT_PERSONAL_PROFILE, "个人")
                }
            }
    val profileIds = profiles.mapTo(hashSetOf(), PersonalProfile::id)
    return snapshot.copy(
        profiles = profiles,
        entries = snapshot.entries.filter { it.profileId in profileIds && it.isValid() && it.stamp.isSane() },
        follows = snapshot.follows.filter { it.profileId in profileIds && it.series.tmdbId > 0 && it.stamp.isSane() },
        guardianPin = guardianPin,
    )
}

/**
 * Brings each list within its row limit, deterministically, so every device that bounds the same
 * union keeps the same rows. Tombstones go first, oldest first: they only stop a device that
 * missed the deletion from bringing the row back. Then the oldest 观看历史, which is a log. Rows a
 * person curated (想看, 收藏, 追剧, profiles) only go when [dropLiveCurated] is set, for a merge that
 * must not fail; a local change that would need it is refused by [validatePersonalSnapshot].
 */
fun boundPersonalSnapshot(
    snapshot: PersonalSnapshot,
    dropLiveCurated: Boolean,
): PersonalSnapshot {
    val profiles =
        snapshot.profiles.pruned(
            limit = MAX_PERSONAL_PROFILES,
            key = PersonalProfile::id,
            stamp = PersonalProfile::stamp,
            tiers =
                listOfNotNull(
                    { profile: PersonalProfile -> profile.deleted },
                    { profile: PersonalProfile -> !profile.deleted && profile.id != DEFAULT_PERSONAL_PROFILE }
                        .takeIf { dropLiveCurated },
                ),
        )
    val profileIds = profiles.mapTo(hashSetOf(), PersonalProfile::id)
    return snapshot.copy(
        profiles = profiles,
        entries =
            snapshot.entries
                .filter { it.profileId in profileIds }
                .pruned(
                    limit = MAX_PERSONAL_ENTRIES,
                    key = PersonalEntry::identity,
                    stamp = PersonalEntry::stamp,
                    tiers =
                        listOfNotNull(
                            { entry: PersonalEntry -> entry.deleted },
                            { entry: PersonalEntry ->
                                !entry.deleted && entry.collection == PersonalCollection.History
                            },
                            { entry: PersonalEntry -> !entry.deleted && entry.collection != PersonalCollection.History }
                                .takeIf { dropLiveCurated },
                        ),
                ),
        follows =
            snapshot.follows
                .filter { it.profileId in profileIds }
                .pruned(
                    limit = MAX_PERSONAL_FOLLOWS,
                    key = PersonalFollow::identity,
                    stamp = PersonalFollow::stamp,
                    tiers =
                        listOfNotNull(
                            { follow: PersonalFollow -> follow.deleted },
                            { follow: PersonalFollow -> !follow.deleted }.takeIf { dropLiveCurated },
                        ),
                ),
    )
}

/** Drops the oldest rows of each tier in turn until [limit] rows remain or the tiers run out. */
private fun <T> List<T>.pruned(
    limit: Int,
    key: (T) -> String,
    stamp: (T) -> PersonalStamp,
    tiers: List<(T) -> Boolean>,
): List<T> {
    if (size <= limit) return this
    var excess = size - limit
    val removed = hashSetOf<String>()
    for (tier in tiers) {
        if (excess == 0) break
        filter(tier)
            .sortedWith(compareBy(stamp).thenBy(key))
            .take(excess)
            .forEach {
                removed += key(it)
                excess--
            }
    }
    return filterNot { key(it) in removed }
}

fun validatePersonalSnapshot(snapshot: PersonalSnapshot) {
    require(snapshot.schemaVersion == 1) { "不支持的个人资料版本" }
    val stamps =
        snapshot.profiles.map { it.stamp } + snapshot.entries.map { it.stamp } +
            snapshot.follows.map { it.stamp } + listOfNotNull(snapshot.guardianPin?.stamp)
    require(stamps.all { it.counter >= 0 && it.counter < Long.MAX_VALUE && it.deviceId.length <= 128 }) {
        "个人数据版本无效"
    }
    require(
        snapshot.profiles.size <= MAX_PERSONAL_PROFILES &&
            snapshot.entries.size <= MAX_PERSONAL_ENTRIES &&
            snapshot.follows.size <= MAX_PERSONAL_FOLLOWS,
    ) {
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
        require(it.isValid())
        require(!it.child || snapshot.guardianPin != null) { "儿童资料需要家长 PIN" }
    }
    val profileIds = snapshot.profiles.mapTo(hashSetOf(), PersonalProfile::id)
    snapshot.entries.forEach {
        require(it.profileId in profileIds && it.isValid())
    }
    snapshot.follows.forEach {
        require(it.profileId in profileIds && it.series.tmdbId > 0)
    }
    snapshot.guardianPin?.let { require(it.isValid()) }
}

private fun PersonalStamp.isSane(): Boolean = counter in 0 until MAX_PERSONAL_STAMP_COUNTER && deviceId.length <= 128

private val PROFILE_ID = Regex("[A-Za-z0-9_-]{1,80}")

private fun PersonalProfile.isValid(): Boolean =
    id.matches(PROFILE_ID) &&
        name.isNotBlank() &&
        name.length <= 40 &&
        serverIds.size <= 100 &&
        serverIds.all { id -> id.isNotBlank() && id.length <= 2_048 }

private fun PersonalEntry.isValid(): Boolean =
    media.mediaKey.isNotBlank() &&
        media.mediaKey.length <= 512 &&
        media.title.isNotBlank() &&
        media.title.length <= 240 &&
        media.mediaType.length <= 40 &&
        media.posterPath.orEmpty().length <= 2_048 &&
        media.serverId.orEmpty().length <= 2_048 &&
        positionMs >= 0 &&
        durationMs >= 0

private fun PersonalPin.isValid(): Boolean =
    iterations in 100_000..600_000 && salt.length in 20..128 && hash.length in 40..128
