package com.yfuse.tv.focus

import android.view.KeyEvent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent

/** Lifecycle-aware registry; stale lazy-list requesters are removed when their item leaves composition. */
class TvFocusRequesterRegistry {
    private val requesters = mutableMapOf<FocusTargetId, FocusRequester>()

    @Synchronized
    fun register(
        targetId: FocusTargetId,
        requester: FocusRequester,
    ) {
        requesters[targetId] = requester
    }

    @Synchronized
    fun unregister(
        targetId: FocusTargetId,
        requester: FocusRequester,
    ) {
        if (requesters[targetId] === requester) requesters.remove(targetId)
    }

    @Synchronized
    fun contains(targetId: FocusTargetId): Boolean = targetId in requesters

    /** Returns true when the target is attached and the focus request was dispatched safely. */
    fun requestFocus(targetId: FocusTargetId): Boolean {
        val requester = synchronized(this) { requesters[targetId] } ?: return false
        return runCatching {
            requester.requestFocus()
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun size(): Int = requesters.size
}

@Composable
fun rememberTvFocusRequesterRegistry(): TvFocusRequesterRegistry =
    remember { TvFocusRequesterRegistry() }

/**
 * Registers a stable target, records its anchor when focused, and optionally joins a scope model.
 * Set [makeFocusable] to false when a following `clickable`/`pressable` already supplies the
 * focusable node; the requester and focus observer will then attach to that node without creating
 * a duplicate stop.
 */
@Composable
fun Modifier.tvFocusTarget(
    targetId: FocusTargetId,
    anchor: FocusAnchor,
    repository: FocusRepository,
    requesterRegistry: TvFocusRequesterRegistry,
    scopeStateMachine: FocusScopeStateMachine? = null,
    enabled: Boolean = true,
    makeFocusable: Boolean = true,
    onFocused: (FocusAnchor) -> Unit = {},
): Modifier {
    require(targetId.scopeId.isNotBlank()) { "target scope must not be blank" }
    val requester = remember(targetId) { FocusRequester() }
    val latestAnchor = rememberUpdatedState(anchor)
    val latestOnFocused = rememberUpdatedState(onFocused)

    DisposableEffect(targetId, requesterRegistry) {
        requesterRegistry.register(targetId, requester)
        onDispose { requesterRegistry.unregister(targetId, requester) }
    }

    val tracked =
        this
            .focusRequester(requester)
            .onFocusChanged { state ->
                if (state.isFocused) {
                    val focusedAnchor = latestAnchor.value
                    repository.record(focusedAnchor)
                    if (scopeStateMachine?.isActive(targetId.scopeId) == true) {
                        scopeStateMachine.recordFocused(targetId)
                    }
                    latestOnFocused.value(focusedAnchor)
                }
            }
    return if (makeFocusable) tracked.focusable(enabled) else tracked
}

/**
 * Applies a Compose focus group and optionally prevents directional focus from leaving it.
 * Back/dismiss remains an explicit remote intent handled by the dialog owner.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.tvFocusScope(trapFocus: Boolean): Modifier =
    then(
        if (trapFocus) {
            // Focus properties must precede the focus target they configure.
            Modifier.focusProperties { exit = { FocusRequester.Cancel } }
        } else {
            Modifier
        },
    ).focusGroup()

/**
 * Resolves an anchor, scrolls its lazy container, waits for attachment, then requests focus.
 */
@Composable
fun RestoreTvFocusEffect(
    request: FocusRestoreRequest,
    repository: FocusRepository,
    requesterRegistry: TvFocusRequesterRegistry,
    policy: FocusRestorePolicy = remember { FocusRestorePolicy() },
    scrollToAnchor: suspend (FocusAnchor) -> Unit = {},
    onDecision: (FocusRestoreDecision) -> Unit = {},
) {
    val latestScrollToAnchor = rememberUpdatedState(scrollToAnchor)
    val latestOnDecision = rememberUpdatedState(onDecision)
    LaunchedEffect(request, repository, requesterRegistry, policy) {
        val decision = policy.resolve(request, repository.last(request.context))
        latestOnDecision.value(decision)
        val target = decision.candidate?.targetId ?: return@LaunchedEffect
        val anchor = decision.anchor ?: return@LaunchedEffect
        latestScrollToAnchor.value(anchor)
        repeat(FOCUS_REQUEST_FRAME_ATTEMPTS) {
            withFrameNanos { }
            if (requesterRegistry.requestFocus(target)) return@LaunchedEffect
        }
    }
}

/**
 * Activates a dialog/panel scope, requests its initial target, and restores its opener on dispose.
 */
@Composable
fun TvFocusScopeEffect(
    scopeId: String,
    kind: FocusScopeKind,
    stateMachine: FocusScopeStateMachine,
    requesterRegistry: TvFocusRequesterRegistry,
    openerTargetId: FocusTargetId? = null,
    initialTargetId: FocusTargetId? = null,
    trapFocus: Boolean = kind == FocusScopeKind.Dialog || kind == FocusScopeKind.Panel,
) {
    DisposableEffect(
        scopeId,
        kind,
        stateMachine,
        requesterRegistry,
        openerTargetId,
        initialTargetId,
        trapFocus,
    ) {
        stateMachine.activate(
            id = scopeId,
            kind = kind,
            trapFocus = trapFocus,
            openerTargetId = openerTargetId,
            initialTargetId = initialTargetId,
        )
        onDispose {
            // A parent scope can remove transient descendants before their effects dispose.
            val returnTarget = stateMachine.deactivateIfActive(scopeId)?.targetId
            if (returnTarget != null) requesterRegistry.requestFocus(returnTarget)
        }
    }

    LaunchedEffect(scopeId, initialTargetId, requesterRegistry) {
        val target = initialTargetId ?: return@LaunchedEffect
        repeat(FOCUS_REQUEST_FRAME_ATTEMPTS) {
            withFrameNanos { }
            if (requesterRegistry.requestFocus(target)) return@LaunchedEffect
        }
    }
}

/**
 * Maps Android TV, Google TV, keyboard, and gamepad keys to semantic intents.
 *
 * A handled key-down is remembered so its matching key-up is consumed too, preventing an
 * underlying clickable from firing a second activation.
 */
@Composable
fun Modifier.tvRemoteKeyHandler(
    onIntent: (RemoteIntent) -> Boolean,
): Modifier {
    val latestHandler = rememberUpdatedState(onIntent)
    val consumedDownKeys = remember { mutableSetOf<Int>() }
    return onPreviewKeyEvent { composeEvent ->
        val event = composeEvent.nativeKeyEvent
        if (event.action == KeyEvent.ACTION_UP && consumedDownKeys.remove(event.keyCode)) {
            return@onPreviewKeyEvent true
        }
        val intent =
            AndroidRemoteKeyMapper.intent(
                keyCode = event.keyCode,
                action = event.action,
                repeatCount = event.repeatCount,
                isLongPress = event.isLongPress,
            ) ?: return@onPreviewKeyEvent false
        val consumed = latestHandler.value(intent)
        if (consumed && event.action == KeyEvent.ACTION_DOWN) {
            consumedDownKeys += event.keyCode
        }
        consumed
    }
}

private const val FOCUS_REQUEST_FRAME_ATTEMPTS = 3
