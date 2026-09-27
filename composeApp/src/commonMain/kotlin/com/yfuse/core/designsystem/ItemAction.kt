package com.yfuse.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction

/**
 * One thing that can be done to a title, wherever it is offered: a row of the 浮起菜单, a screen
 * reader's custom action on the poster, the television's long-press panel, 详情's 更多 under a
 * held finger. A screen builds the list once, from the state it has, and every surface shows the
 * same list — "标记为已看" is written once, not once per surface.
 *
 * An action either leaves the page — it opens something, and the surface showing it gets out of
 * the way first — or changes the title in place, in which case a lift settles back into its
 * poster first and the change lands where the person is looking. [leavesPage] says which.
 * [undoable] marks a change the screen will offer to take back for [TOAST_UNDO_WINDOW_MS].
 */
@Immutable
class ItemAction(
    val label: String,
    val icon: ImageVector? = null,
    /** A short second line: "剩余 42 分钟". */
    val detail: String? = null,
    val destructive: Boolean = false,
    val leavesPage: Boolean = false,
    val undoable: Boolean = false,
    /** Stable across rebuilds, for tests and for matching an action to its undo. */
    val id: String = label,
    val onSelect: () -> Unit,
)

/** [actions] as a screen reader's custom actions, in the same order and wording. */
fun List<ItemAction>.accessibilityActions(): List<CustomAccessibilityAction> =
    map { action ->
        CustomAccessibilityAction(action.label) {
            action.onSelect()
            true
        }
    }
