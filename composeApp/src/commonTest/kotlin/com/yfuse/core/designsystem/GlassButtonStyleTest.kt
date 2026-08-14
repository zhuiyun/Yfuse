package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlassButtonStyleTest {
    @Test
    fun primary_and_destructive_actions_remain_translucent_glass() {
        val accent = AccentColors(Color.Blue, Color.White, Color.Blue, Color.Blue)

        val primary = resolveGlassButtonVisuals(GlassButtonEmphasis.Primary, LightPalette, accent)
        val destructive =
            resolveGlassButtonVisuals(GlassButtonEmphasis.Destructive, LightPalette, accent)

        assertTrue(primary.fill.alpha < 1f)
        assertTrue(destructive.fill.alpha < 1f)
        assertEquals(accent.accent, primary.content)
        assertEquals(LightPalette.error, destructive.content)
    }

    @Test
    fun dark_actions_use_light_ink_and_restrained_sheen() {
        val accent = resolveAccentColors(Brand.Primary, dark = true)
        val primary = resolveGlassButtonVisuals(GlassButtonEmphasis.Primary, DarkPalette, accent)
        val destructive =
            resolveGlassButtonVisuals(GlassButtonEmphasis.Destructive, DarkPalette, accent)

        assertEquals(DarkPalette.text, primary.content)
        assertEquals(DarkPalette.onErrorContainer, destructive.content)
        assertTrue(primary.sheen <= 0.5f)
        assertTrue(destructive.sheen <= 0.5f)
    }

    @Test
    fun form_and_overlay_buttons_share_one_disabled_alpha() {
        assertEquals(1f, glassButtonAlpha(enabled = true))
        assertEquals(0.44f, glassButtonAlpha(enabled = false))
    }

    @Test
    fun form_and_overlay_buttons_share_the_same_glass_material_contract() {
        val dialogs = projectSource("core/designsystem/Dialogs.kt")
        val forms = projectSource("core/designsystem/FormControls.kt")

        listOf(dialogs, forms).forEach { source ->
            assertTrue("defaultMinSize(minHeight = 48.dp)" in source)
            assertTrue("shape = AppShapes.control" in source)
            assertTrue("over = palette.background" in source)
            assertTrue("glassButtonAlpha(enabled)" in source)
            assertTrue("stateDescription = \"处理中\"" in source)
            assertTrue("focusShape = AppShapes.control" in source)
            assertTrue("onClickLabel = label" in source)
            assertTrue("Spacer(Modifier.width(8.dp))" in source)
        }
    }

    private fun projectSource(relativePath: String): String =
        sequenceOf(
            File("src/commonMain/kotlin/com/yfuse/$relativePath"),
            File("composeApp/src/commonMain/kotlin/com/yfuse/$relativePath"),
        ).first(File::isFile).readText()
}
