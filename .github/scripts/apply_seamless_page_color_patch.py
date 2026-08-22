from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)

# -----------------------------------------------------------------------------
# Library: remove the last independent accent wash below the hero. The hero image
# must alpha-dissolve directly to the exact page background instead.
library_path = Path("composeApp/src/commonMain/kotlin/com/yfuse/feature/library/LibraryHomeScreen.kt")
library = library_path.read_text()
library = library.replace("import androidx.compose.ui.graphics.Brush\n", "")
library = library.replace(
    "/** `transparent 320px` — how far the artwork's tint reaches into the content. */\n"
    "private val ContentWashHeight = 320.dp\n\n",
    "",
)
wash_pattern = re.compile(
    r"\s*// Artwork tint only\. This used to carry a `palette\.background` stop.*?"
    r"val wash =\n\s*remember\(accent, density\) \{.*?\n\s*\}\n\s*Column\(",
    re.S,
)
match = wash_pattern.search(library)
if not match:
    raise SystemExit("library content wash block not found")
replacement = (
    "\n                                // One page colour only: the hero removes its own alpha and "
    "reveals the exact background below.\n"
    "                                Column("
)
library = library[: match.start()] + replacement + library[match.end() :]
library = replace_once(
    library,
    "                                        }.background(wash)\n                                        .padding(top = 78.dp),",
    "                                        }.padding(top = 78.dp),",
    "library wash modifier",
)
if "ContentWashHeight" in library or ".background(wash)" in library:
    raise SystemExit("library wash residue remains")
library_path.write_text(library)

# -----------------------------------------------------------------------------
# TMDB detail: retainedPageColor.value already applies artworkPageSurface. A second
# pass was flattening the page colour towards the brightness threshold again.
tmdb_path = Path("composeApp/src/commonMain/kotlin/com/yfuse/feature/home/TmdbInfoScreen.kt")
tmdb = tmdb_path.read_text()
tmdb = tmdb.replace("import com.yfuse.core.designsystem.artworkPageSurface\n", "")
tmdb = replace_once(
    tmdb,
    '''        val pageColor =
            remember(retainedPageColor.value, inheritedPalette.isDark) {
                retainedPageColor.value?.let { artworkPageSurface(it, inheritedPalette.isDark) }
            }
''',
    '''        // RetainedArtworkPageColor already applies the appearance-aware safety envelope.
        // Do not protect it a second time or different posters collapse towards the same grey.
        val pageColor = retainedPageColor.value
''',
    "tmdb double page protection",
)
tmdb = replace_once(
    tmdb,
    "            LazyColumn(\n                Modifier.fillMaxSize(),",
    "            LazyColumn(\n                Modifier.fillMaxSize().background(pageColor ?: palette.background),",
    "tmdb list background",
)
tmdb_path.write_text(tmdb)

# -----------------------------------------------------------------------------
# Main Emby detail: remove heroSurface/ambient/panel colour layers. Sample the
# actually resolved artwork lower crop just like Home/Library/TMDB, retain it for
# navigation return, and let ArtworkPageTheme derive readable semantics from it.
detail_path = Path("composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailScreen.kt")
detail = detail_path.read_text()
detail = detail.replace("import androidx.compose.ui.graphics.Brush\n", "")
detail = detail.replace(
    "import com.yfuse.core.designsystem.ArtworkAccent\n",
    "import com.yfuse.core.designsystem.ArtworkAccent\nimport com.yfuse.core.designsystem.ArtworkPageTheme\n",
)
detail = detail.replace(
    "import com.yfuse.core.designsystem.GlassDialog\n",
    "import com.yfuse.core.designsystem.GlassDialog\nimport com.yfuse.core.designsystem.HeroPageFade\n",
)
detail = detail.replace("import com.yfuse.core.designsystem.heroPanelBrush\n", "")
detail = detail.replace("import com.yfuse.core.designsystem.heroSurface\n", "")
detail = detail.replace(
    "import com.yfuse.core.designsystem.rememberAnimatedArtworkAccent\n",
    "import com.yfuse.core.designsystem.rememberAnimatedArtworkAccent\n"
    "import com.yfuse.core.designsystem.rememberArtworkPageColor\n"
    "import com.yfuse.core.designsystem.rememberRetainedArtworkPageColor\n",
)
old_surface = '''            val detailSurface =
                remember(detailAccent, palette.isDark) {
                    heroSurface(detailAccent, palette.isDark)
                }
            val ambientBrush =
                remember(
                    detailAccent,
                    detailSurface,
                    heroHeightPx,
                    palette.isDark,
                ) {
                    Brush.verticalGradient(
                        colors =
                            listOf(
                                detailAccent.copy(alpha = if (palette.isDark) 0.18f else 0.10f),
                                detailSurface.copy(alpha = 0f),
                            ),
                        startY = 0f,
                        // The hero scrim reaches pure [detailSurface] at this exact edge. Ambient
                        // colour must also be fully transparent here or the two sides cannot match.
                        endY = heroHeightPx,
                    )
                }
            // Blend band between the artwork and the page. It starts where the artwork ends,
            // leaving the title block on clean artwork — see [heroPanelBrush].
            val panelBrush =
                remember(detailSurface, density, captionLift) {
                    heroPanelBrush(detailSurface, density, start = captionLift)
                }

            // A different detail route must always start at its hero. Keying the state by the
'''
new_surface = '''            val artworkAspectRatio = maxWidth.value / heroHeight.value.coerceAtLeast(1f)
            val artworkFadeFraction =
                (HeroPageFade.value / heroHeight.value.coerceAtLeast(1f)).coerceIn(0.02f, 1f)
            val sampledPageColor =
                rememberArtworkPageColor(
                    url = resolvedHeroUrl,
                    targetAspectRatio = artworkAspectRatio,
                    fadeFraction = artworkFadeFraction,
                )
            val retainedPageColor =
                rememberRetainedArtworkPageColor(
                    "detail:${state.server?.id ?: component.serverId}:${detail?.id ?: component.itemId}",
                )
            LaunchedEffect(sampledPageColor) {
                sampledPageColor?.let(retainedPageColor::update)
            }
            val detailSurface = retainedPageColor.value ?: palette.background

            ArtworkPageTheme(
                background = detailSurface,
                artworkAccent = detailAccent,
            ) {
                val pagePalette = LocalPalette.current

            // A different detail route must always start at its hero. Keying the state by the
'''
detail = replace_once(detail, old_surface, new_surface, "detail old surface stack")
detail = replace_once(
    detail,
    "            StatusBarIconStyle(darkIcons = !palette.isDark && (detail == null || barSolid))",
    "            StatusBarIconStyle(darkIcons = !pagePalette.isDark && (detail == null || barSolid))",
    "detail status palette",
)
detail = replace_once(
    detail,
    '''            Box(
                Modifier
                    .fillMaxSize()
                    .background(detailSurface)
                    .background(ambientBrush),
            )
''',
    '''            // The only opaque ground on the page. Hero, sheet and tail all reveal this exact colour.
            Box(Modifier.fillMaxSize().background(detailSurface))
''',
    "detail ambient background",
)
detail = replace_once(
    detail,
    "                                    .liftOverHero(captionLift)\n                                    .background(panelBrush)\n                                    .padding(horizontal = Dimens.pageHorizontal)",
    "                                    .liftOverHero(captionLift)\n                                    .padding(horizontal = Dimens.pageHorizontal)",
    "detail panel wash",
)
old_tail = '''            ActionToast(
                message = state.actionMessage ?: state.sourceFailure?.toDetailMessage(),
                onDismiss = { component.store.accept(DetailIntent.DismissMessage) },
                accent = detailAccent,
                modifier = Modifier.padding(bottom = 28.dp),
            )
        }
    }
}
'''
new_tail = '''            ActionToast(
                message = state.actionMessage ?: state.sourceFailure?.toDetailMessage(),
                onDismiss = { component.store.accept(DetailIntent.DismissMessage) },
                accent = detailAccent,
                modifier = Modifier.padding(bottom = 28.dp),
            )
            }
        }
    }
}
'''
detail = replace_once(detail, old_tail, new_tail, "detail ArtworkPageTheme close")
for residue in ("heroSurface(", "ambientBrush", "panelBrush", "heroPanelBrush"):
    if residue in detail:
        raise SystemExit(f"detail residue remains: {residue}")
detail_path.write_text(detail)

# -----------------------------------------------------------------------------
# Detail hero: the image itself disappears at the bottom; no page-coloured scrim is
# allowed there. Top-only scrim remains for status/header contrast.
hero_path = Path("composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailHero.kt")
hero = hero_path.read_text()
hero = hero.replace("import com.yfuse.core.designsystem.heroPanelBrush\n", "")
hero = hero.replace("import com.yfuse.core.designsystem.heroScrim\n", "")
hero = hero.replace(
    "import com.yfuse.core.designsystem.cssLinearGradient\n",
    "import com.yfuse.core.designsystem.cssLinearGradient\nimport com.yfuse.core.designsystem.fadeIntoPage\nimport com.yfuse.core.designsystem.heroTopScrim\n",
)
hero = replace_once(
    hero,
    '''                        .sharedMediaArtwork(sharedKey)
                        .fillMaxSize()
                        .graphicsLayer {
''',
    '''                        .sharedMediaArtwork(sharedKey)
                        .fillMaxSize()
                        .fadeIntoPage()
                        .graphicsLayer {
''',
    "detail hero alpha fade",
)
hero = replace_once(
    hero,
    ".background(heroScrim(surfaceColor)),",
    ".background(heroTopScrim()),",
    "detail hero top-only scrim",
)
hero_path.write_text(hero)

# -----------------------------------------------------------------------------
# Resume clock: colour remains artwork-derived but gains a small dark glass bed and
# minimum luminance so it cannot disappear into the coloured primary key.
actions_path = Path("composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailActions.kt")
actions = actions_path.read_text()
actions = actions.replace(
    "import androidx.compose.ui.graphics.Color\n",
    "import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.lerp\nimport androidx.compose.ui.graphics.luminance\n",
)
actions = replace_once(
    actions,
    "private const val EmbyTicksPerSecond = 10_000_000L\n\n",
    '''private const val EmbyTicksPerSecond = 10_000_000L

internal fun resumeTimeColor(accent: Color): Color {
    val opaque = accent.copy(alpha = 1f)
    return if (opaque.luminance() < 0.34f) {
        lerp(opaque, Color.White, 0.58f)
    } else {
        opaque
    }
}

''',
    "resume colour helper",
)
actions = replace_once(
    actions,
    '''                resumeTimeLabel?.let { resumeTime ->
                    Text(
                        resumeTime,
                        style = AppTypography.caption.strong,
                        color = Color.White.copy(alpha = 0.94f),
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp, end = 5.dp),
                    )
                }
''',
    '''                resumeTimeLabel?.let { resumeTime ->
                    Text(
                        resumeTime,
                        style = AppTypography.caption.strong,
                        color = resumeTimeColor(accent),
                        maxLines = 1,
                        modifier =
                            Modifier
                                .padding(start = 8.dp, end = 5.dp)
                                .clip(GlassShapes.thumb)
                                .background(Color.Black.copy(alpha = 0.22f))
                                .border(
                                    Dimens.hairline,
                                    Color.White.copy(alpha = 0.14f),
                                    GlassShapes.thumb,
                                ).padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
''',
    "resume clock presentation",
)
actions_path.write_text(actions)

print("seamless page colour patch applied")
