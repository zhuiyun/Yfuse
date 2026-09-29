package com.yfuse.tv.ui

import com.yfuse.core.designsystem.ItemAction
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.tv.focus.RemoteIntent
import com.yfuse.tv.focus.RemoteIntentPolicy
import com.yfuse.tv.focus.RemoteKeyInput
import com.yfuse.tv.focus.RemoteKeyPhase
import com.yfuse.tv.focus.RemotePhysicalKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvQuickActionsTest {
    @Test
    fun `holding 确定 and pressing 菜单 both open a card's panel`() {
        assertTrue(RemoteIntent.OpenContextMenu.opensTvQuickActions())
        assertTrue(RemoteIntent.Menu.opensTvQuickActions())
        val menuPress = RemoteIntentPolicy.map(RemoteKeyInput(RemotePhysicalKey.Menu, RemoteKeyPhase.Down))
        assertTrue(menuPress?.opensTvQuickActions() == true)
        // A held 菜单 opens it once: its repeats are no intent at all.
        assertNull(RemoteIntentPolicy.map(RemoteKeyInput(RemotePhysicalKey.Menu, RemoteKeyPhase.Down, repeatCount = 1)))
        assertFalse(RemoteIntent.Activate.opensTvQuickActions())
        assertFalse(RemoteIntent.Back.opensTvQuickActions())
    }

    @Test
    fun `an episode's panel keeps its rows and leaves out 查看详情`() {
        var picked = 0
        val play = ItemAction(label = "播放", leavesPage = true, onSelect = {})
        val watched = ItemAction(label = "标记为已看", onSelect = {})
        val menu =
            LiftMenu(
                title = "第1集 · 雾港",
                meta = "45分钟",
                progress = 0.5f,
                onOpen = { picked++ },
                sections = listOf(listOf(play), listOf(watched)),
            )

        val television = menu.withoutOpening()

        assertNull(television.onOpen)
        assertEquals("第1集 · 雾港", television.title)
        assertEquals("45分钟", television.meta)
        assertEquals(0.5f, television.progress)
        assertEquals(
            listOf(listOf("播放"), listOf("标记为已看")),
            tvQuickActionSections(television).map { section -> section.map(ItemAction::label) },
        )
        assertEquals(0, picked)
    }

    @Test
    fun `a 追剧 card offers the library copy and 追剧, and nothing it cannot do here`() {
        var opened = 0
        var followed = 0
        val both = tvCalendarQuickActions("雾港", "第 3 集", onOpenInLibrary = { opened++ }, onFollow = { followed++ })

        assertEquals(listOf(listOf("在媒体库打开"), listOf("追剧")), both.sections.map { it.map(ItemAction::label) })
        val open = both.actions.first()
        assertTrue(open.leavesPage)
        // No 查看详情 either: opening the title is the first row.
        assertNull(both.onOpen)
        both.actions.forEach { it.onSelect() }
        assertEquals(1, opened)
        assertEquals(1, followed)

        val notYetArrived = tvCalendarQuickActions("雾港", null, onOpenInLibrary = null, onFollow = {})
        assertEquals(listOf("追剧"), notYetArrived.actions.map(ItemAction::label))
        val alreadyFollowed = tvCalendarQuickActions("雾港", null, onOpenInLibrary = {}, onFollow = null)
        assertEquals(listOf("在媒体库打开"), alreadyFollowed.actions.map(ItemAction::label))
    }
}
