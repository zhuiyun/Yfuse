package com.yfuse.feature.search

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * "Search for this" from anywhere that is not the search tab: a genre chip or a cast member
 * on a detail page. The root component switches to the search tab and runs the query; the
 * page that asked never needs to know how the tabs are wired.
 */
class SearchRequests {
    private val _requests =
        MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val requests: SharedFlow<String> = _requests

    fun submit(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        _requests.tryEmit(trimmed)
    }
}
