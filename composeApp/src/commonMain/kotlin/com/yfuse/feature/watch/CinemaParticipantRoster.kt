package com.yfuse.feature.watch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.CinemaMotion
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.cinemaMotion
import com.yfuse.core.sync.WatchParticipant
import kotlinx.coroutines.delay

/** The info dialog has no member actions; departing rows are retained only for their exit. */
@Composable
internal fun CinemaParticipantRoster(
    participants: List<WatchParticipant>,
    content: @Composable (WatchParticipant) -> Unit,
) {
    val moving = cinemaMotion()
    val live = participants.associateBy { it.clientId }
    val initial = remember { live.keys.toMutableSet() }
    SideEffect {
        initial.retainAll(live.keys)
        if (!moving) initial.addAll(live.keys)
    }
    var retained by remember { mutableStateOf(participants) }
    LaunchedEffect(live.keys, moving) {
        retained = participants + retained.filter { it.clientId !in live }
        if (moving) delay(CinemaMotion.ITEM_EXIT.toLong())
        retained = participants
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!moving) {
            participants.forEach { participant -> key(participant.clientId) { content(participant) } }
        } else {
            retained.forEach { remembered ->
                key(remembered.clientId) {
                    val visibility = remember { MutableTransitionState(remembered.clientId in initial) }
                    SideEffect { visibility.targetState = remembered.clientId in live }
                    AnimatedVisibility(
                        visibleState = visibility,
                        enter =
                            fadeIn(tween(CinemaMotion.ITEM_ENTER, easing = Motion.Curve)) +
                                expandVertically(tween(CinemaMotion.ITEM_ENTER, easing = Motion.Curve)),
                        exit =
                            fadeOut(tween(CinemaMotion.ITEM_EXIT, easing = Motion.Curve)) +
                                shrinkVertically(tween(CinemaMotion.ITEM_EXIT, easing = Motion.Curve)),
                    ) { content(live[remembered.clientId] ?: remembered) }
                }
            }
        }
    }
}
