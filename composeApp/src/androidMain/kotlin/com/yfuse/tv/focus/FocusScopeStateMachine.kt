package com.yfuse.tv.focus

enum class FocusScopeKind {
    Root,
    Page,
    Dialog,
    Panel,
    Player,
}

data class ActiveFocusScope(
    val id: String,
    val parentId: String?,
    val kind: FocusScopeKind,
    val trapFocus: Boolean,
    val openerTargetId: FocusTargetId?,
    val initialTargetId: FocusTargetId?,
) {
    init {
        require(id.isNotBlank()) { "scope id must not be blank" }
        require(parentId == null || parentId.isNotBlank()) { "parentId must be null or non-blank" }
        require(openerTargetId == null || openerTargetId.scopeId == parentId) {
            "opener target must belong to the parent scope"
        }
        require(initialTargetId == null || initialTargetId.scopeId == id) {
            "initial target must belong to the activated scope"
        }
    }
}

enum class FocusReturnReason {
    Opener,
    ParentLastFocused,
    NoKnownTarget,
}

data class FocusReturnTarget(
    val targetId: FocusTargetId?,
    val reason: FocusReturnReason,
)

/**
 * Models nested page, panel, and dialog focus without retaining Compose objects.
 *
 * The active scopes form one stack. Closing a parent also closes any transient descendants and
 * restores the parent's opener, which prevents a stale child requester from stealing focus.
 */
class FocusScopeStateMachine(
    rootScopeId: String = DEFAULT_ROOT_SCOPE,
) {
    private val scopes = mutableListOf(
        ActiveFocusScope(
            id = rootScopeId,
            parentId = null,
            kind = FocusScopeKind.Root,
            trapFocus = false,
            openerTargetId = null,
            initialTargetId = null,
        ),
    )
    private val lastFocusedByScope = mutableMapOf<String, FocusTargetId>()

    val activeScope: ActiveFocusScope
        get() = synchronized(this) { scopes.last() }

    val activeTrapScopeId: String?
        get() = synchronized(this) { scopes.lastOrNull { it.trapFocus }?.id }

    @Synchronized
    fun activate(
        id: String,
        kind: FocusScopeKind,
        trapFocus: Boolean = kind == FocusScopeKind.Dialog || kind == FocusScopeKind.Panel,
        openerTargetId: FocusTargetId? = null,
        initialTargetId: FocusTargetId? = null,
    ): ActiveFocusScope {
        require(id.isNotBlank()) { "scope id must not be blank" }
        require(scopes.none { it.id == id }) { "scope '$id' is already active" }
        val parent = scopes.last()
        val opener = openerTargetId ?: lastFocusedByScope[parent.id]
        val scope =
            ActiveFocusScope(
                id = id,
                parentId = parent.id,
                kind = kind,
                trapFocus = trapFocus,
                openerTargetId = opener,
                initialTargetId = initialTargetId,
            )
        scopes += scope
        return scope
    }

    @Synchronized
    fun recordFocused(targetId: FocusTargetId) {
        require(scopes.any { it.id == targetId.scopeId }) {
            "cannot record focus for inactive scope '${targetId.scopeId}'"
        }
        lastFocusedByScope[targetId.scopeId] = targetId
    }

    @Synchronized
    fun lastFocused(scopeId: String): FocusTargetId? = lastFocusedByScope[scopeId]

    @Synchronized
    fun deactivate(scopeId: String): FocusReturnTarget {
        require(scopeId.isNotBlank()) { "scope id must not be blank" }
        val index = scopes.indexOfFirst { it.id == scopeId }
        require(index > 0) { "scope '$scopeId' is not active or is the root scope" }
        val closing = scopes[index]
        val parentId = closing.parentId ?: error("non-root scope must have a parent")

        scopes
            .subList(index, scopes.size)
            .map { it.id }
            .forEach { lastFocusedByScope.remove(it) }
        scopes.subList(index, scopes.size).clear()

        closing.openerTargetId?.let { opener ->
            return FocusReturnTarget(opener, FocusReturnReason.Opener)
        }
        lastFocusedByScope[parentId]?.let { parentLast ->
            return FocusReturnTarget(parentLast, FocusReturnReason.ParentLastFocused)
        }
        return FocusReturnTarget(null, FocusReturnReason.NoKnownTarget)
    }

    @Synchronized
    fun isActive(scopeId: String): Boolean = scopes.any { it.id == scopeId }

    @Synchronized
    fun deactivateIfActive(scopeId: String): FocusReturnTarget? =
        if (scopes.indexOfFirst { it.id == scopeId } > 0) deactivate(scopeId) else null

    @Synchronized
    fun activeScopes(): List<ActiveFocusScope> = scopes.toList()

    companion object {
        const val DEFAULT_ROOT_SCOPE = "tv-root"
    }
}
