package com.yfuse.tv.focus

/** Stores only stable focus anchors; view objects and Compose requesters never belong here. */
interface FocusRepository {
    fun record(anchor: FocusAnchor)

    fun last(context: FocusContext): FocusAnchor?

    fun lastInSection(
        context: FocusContext,
        sectionId: String,
    ): FocusAnchor?

    fun remove(context: FocusContext)

    fun removeServer(serverId: String)

    fun clear()

    fun snapshot(): List<FocusAnchor>
}

/**
 * Small process-local LRU store suitable for a TV root component.
 *
 * Access is synchronized because player callbacks, lifecycle callbacks, and Compose focus events
 * do not necessarily arrive on the same thread.
 */
class InMemoryFocusRepository(
    private val maxContexts: Int = DEFAULT_MAX_CONTEXTS,
    private val maxSectionsPerContext: Int = DEFAULT_MAX_SECTIONS,
) : FocusRepository {
    init {
        require(maxContexts > 0) { "maxContexts must be positive" }
        require(maxSectionsPerContext > 0) { "maxSectionsPerContext must be positive" }
    }

    private data class ContextState(
        var last: FocusAnchor,
        val sections: LinkedHashMap<String, FocusAnchor> = linkedMapOf(),
    )

    private val contexts =
        object : LinkedHashMap<FocusContext, ContextState>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<FocusContext, ContextState>?,
            ): Boolean = size > maxContexts
        }

    @Synchronized
    override fun record(anchor: FocusAnchor) {
        val context = anchor.context
        val state = contexts[context]
        if (state == null) {
            contexts[context] =
                ContextState(
                    last = anchor,
                    sections = linkedMapOf(anchor.sectionId to anchor),
                )
            return
        }

        state.last = anchor
        // Remove first so insertion order also represents section recency.
        state.sections.remove(anchor.sectionId)
        state.sections[anchor.sectionId] = anchor
        while (state.sections.size > maxSectionsPerContext) {
            val eldest = state.sections.keys.firstOrNull() ?: break
            state.sections.remove(eldest)
        }
    }

    @Synchronized
    override fun last(context: FocusContext): FocusAnchor? = contexts[context]?.last

    @Synchronized
    override fun lastInSection(
        context: FocusContext,
        sectionId: String,
    ): FocusAnchor? {
        require(sectionId.isNotBlank()) { "sectionId must not be blank" }
        return contexts[context]?.sections?.get(sectionId)
    }

    @Synchronized
    override fun remove(context: FocusContext) {
        contexts.remove(context)
    }

    @Synchronized
    override fun removeServer(serverId: String) {
        require(serverId.isNotBlank()) { "serverId must not be blank" }
        contexts.keys.filter { it.serverId == serverId }.forEach { contexts.remove(it) }
    }

    @Synchronized
    override fun clear() {
        contexts.clear()
    }

    @Synchronized
    override fun snapshot(): List<FocusAnchor> = contexts.values.map { it.last }

    private companion object {
        const val DEFAULT_MAX_CONTEXTS = 64
        const val DEFAULT_MAX_SECTIONS = 32
    }
}
