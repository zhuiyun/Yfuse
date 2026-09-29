package com.yfuse.feature.search

import com.yfuse.core.model.Person
import com.yfuse.feature.person.PersonPageRequest
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * "Search for this" from anywhere that is not the search tab: a genre chip or a cast member
 * on a detail page. The root component switches to the search tab and runs the query; the
 * page that asked never needs to know how the tabs are wired. 演员页 travels the same way, and
 * Back from it returns to the tab that asked.
 */
class SearchRequests {
    private val _requests =
        MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val requests: SharedFlow<String> = _requests

    private val playlistRequests =
        MutableSharedFlow<com.yfuse.core.data.SmartPlaylist>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val playlists: SharedFlow<com.yfuse.core.data.SmartPlaylist> = playlistRequests

    fun openPlaylist(rule: com.yfuse.core.data.SmartPlaylist) {
        playlistRequests.tryEmit(rule)
    }

    private val personRequests =
        MutableSharedFlow<PersonPageRequest>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 演员页 asked for from a detail page's cast; the root opens it on this tab's stack. */
    val people: SharedFlow<PersonPageRequest> = personRequests

    /** [person] as [serverId]'s library credits them; null means the default server. */
    fun openPerson(
        serverId: String?,
        person: Person,
    ) {
        if (person.id.isBlank()) return
        personRequests.tryEmit(PersonPageRequest.of(serverId, person))
    }

    fun submit(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        _requests.tryEmit(trimmed)
    }
}
