package com.yfuse.core.personal

import com.yfuse.core.data.FollowedSeries
import kotlinx.serialization.Serializable

const val DEFAULT_PERSONAL_PROFILE = "default"

@Serializable
data class PersonalStamp(
    val counter: Long = 0,
    val deviceId: String = "",
) : Comparable<PersonalStamp> {
    override fun compareTo(other: PersonalStamp): Int =
        compareValuesBy(this, other, PersonalStamp::counter, PersonalStamp::deviceId)
}

@Serializable
data class PersonalMediaRef(
    val mediaKey: String,
    val title: String,
    val mediaType: String,
    val tmdbId: Int? = null,
    val year: Int? = null,
    val posterPath: String? = null,
    val serverId: String? = null,
    val serverItemId: String? = null,
) {
    val identity: String
        get() =
            "${mediaType.lowercase()}:$mediaKey" +
                if (mediaKey.startsWith("emby:", ignoreCase = true)) ":server:${serverId.orEmpty()}" else ""
}

@Serializable
data class PersonalProfile(
    val id: String,
    val name: String,
    val child: Boolean = false,
    val serverIds: Set<String> = emptySet(),
    val stamp: PersonalStamp = PersonalStamp(),
    val deleted: Boolean = false,
)

@Serializable
data class PersonalPin(
    val salt: String,
    val hash: String,
    val stamp: PersonalStamp,
    val iterations: Int = 100_000,
)

@Serializable
enum class PersonalCollection { Favorite, WatchLater, History }

@Serializable
data class PersonalEntry(
    val profileId: String,
    val collection: PersonalCollection,
    val media: PersonalMediaRef,
    val stamp: PersonalStamp,
    val deleted: Boolean = false,
    val watchedAtEpochMs: Long = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val completed: Boolean = false,
) {
    val identity: String get() = "$profileId:${collection.name}:${media.identity}"
}

@Serializable
data class PersonalFollow(
    val profileId: String,
    val series: FollowedSeries,
    val stamp: PersonalStamp,
    val deleted: Boolean = false,
) {
    val identity: String get() = "$profileId:${series.tmdbId}"
}

/** Tombstones are retained so an offline device cannot resurrect a removed entry. */
@Serializable
data class PersonalSnapshot(
    val schemaVersion: Int = 1,
    val profiles: List<PersonalProfile> = listOf(PersonalProfile(DEFAULT_PERSONAL_PROFILE, "个人")),
    val entries: List<PersonalEntry> = emptyList(),
    val follows: List<PersonalFollow> = emptyList(),
    val guardianPin: PersonalPin? = null,
)

data class PersonalAccessPolicy(
    val profileId: String = DEFAULT_PERSONAL_PROFILE,
    val child: Boolean = false,
    val serverIds: Set<String> = emptySet(),
) {
    /** Linked ids include the media-server user; this never grants a server permission. */
    fun allowsServer(serverId: String): Boolean = serverId in serverIds || (!child && serverIds.isEmpty())

    val canManageServers: Boolean get() = !child
}

data class PersonalLibraryState(
    val activeProfile: PersonalProfile = PersonalProfile(DEFAULT_PERSONAL_PROFILE, "个人"),
    val profiles: List<PersonalProfile> = emptyList(),
    val favorites: List<PersonalEntry> = emptyList(),
    val watchLater: List<PersonalEntry> = emptyList(),
    val history: List<PersonalEntry> = emptyList(),
    val hasGuardianPin: Boolean = false,
    val pendingSync: Boolean = false,
    val syncing: Boolean = false,
    val lastSyncedAtEpochMs: Long? = null,
    val error: String? = null,
)
