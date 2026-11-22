package com.yfuse.app

import com.arkivanov.decompose.router.stack.StackNavigation

/** A new selection in the left pane replaces the previous right-hand navigation branch. */
internal fun <C : Any> StackNavigation<C>.openFrom(
    source: C,
    destination: C,
) {
    navigate({ stack -> stackAfterSelection(stack, source, destination) }, { _, _ -> })
}

internal fun <C : Any> stackAfterSelection(
    stack: List<C>,
    source: C,
    destination: C,
): List<C> {
    val sourceIndex = stack.indexOf(source)
    if (sourceIndex < 0) return stack
    val branch = stack.take(sourceIndex + 1)
    if (destination == branch.first()) return branch.take(1)
    return branch.filterNot { it == destination } + destination
}
