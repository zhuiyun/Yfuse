# Dual subtitles at the bottom

The YCore subtitle overlay now measures both channels in one bottom-aligned column. Primary subtitles are above secondary subtitles, separated by 4 dp; the subtitle-position setting moves the whole stack. An inactive channel contributes no layout.

Text keeps its styling and wraps naturally. ASS and PGS bitmap rectangles are translated as one display set, with scaled bounds used for both measurement and placement. Single-track subtitles retain authored alignment and positioning.

Scope: Core2Surface.kt and Core2SurfaceTest.kt. Existing MPV/Media3 renderers are not changed in this patch.

Validation uses the preceding ambient-power source snapshot plus these two files, preserving the established TV baseline and avoiding unrelated native/TV/Harmony work in the shared checkout. No device visual verification was available.

Final validation: BUILD SUCCESSFUL; 75 tests in 11 suites, zero failures/errors/skips. Both changed files match the tested source snapshot by SHA-256.
