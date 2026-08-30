package com.yfuse.tv.focus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FocusScopeStateMachineTest {
    @Test
    fun dialogRestoresExactOpener() {
        val machine = FocusScopeStateMachine("root")
        val opener = FocusTargetId("root", "settings")
        machine.recordFocused(opener)

        machine.activate(id = "dialog", kind = FocusScopeKind.Dialog)
        machine.recordFocused(FocusTargetId("dialog", "confirm"))
        val returned = machine.deactivate("dialog")

        assertEquals(opener, returned.targetId)
        assertEquals(FocusReturnReason.Opener, returned.reason)
        assertEquals("root", machine.activeScope.id)
        assertNull(machine.activeTrapScopeId)
    }

    @Test
    fun nestedPanelIsRemovedWhenOwningDialogCloses() {
        val machine = FocusScopeStateMachine("root")
        val opener = FocusTargetId("root", "play")
        machine.recordFocused(opener)
        machine.activate("dialog", FocusScopeKind.Dialog)
        machine.recordFocused(FocusTargetId("dialog", "subtitles"))
        machine.activate("panel", FocusScopeKind.Panel)

        val returned = machine.deactivate("dialog")

        assertEquals(opener, returned.targetId)
        assertFalse(machine.isActive("dialog"))
        assertFalse(machine.isActive("panel"))
        assertEquals(listOf("root"), machine.activeScopes().map { it.id })
    }

    @Test
    fun scopeUsesExplicitInitialTargetAndTracksActiveTrap() {
        val machine = FocusScopeStateMachine("root")
        val initial = FocusTargetId("dialog", "primary")
        val scope =
            machine.activate(
                id = "dialog",
                kind = FocusScopeKind.Dialog,
                initialTargetId = initial,
            )

        assertEquals(initial, scope.initialTargetId)
        assertEquals("dialog", machine.activeTrapScopeId)
        assertTrue(machine.isActive("dialog"))
    }

    @Test
    fun rejectsStaleTargetsAndDuplicateScopes() {
        val machine = FocusScopeStateMachine("root")

        assertFailsWith<IllegalArgumentException> {
            machine.recordFocused(FocusTargetId("missing", "item"))
        }
        machine.activate("dialog", FocusScopeKind.Dialog)
        assertFailsWith<IllegalArgumentException> {
            machine.activate("dialog", FocusScopeKind.Dialog)
        }
        assertFailsWith<IllegalArgumentException> { machine.deactivate("root") }
    }
}
