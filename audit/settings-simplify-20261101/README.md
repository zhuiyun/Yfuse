# Settings simplification — 1.8.4 (336)

Scope: phone settings, appearance subpages, playback settings, player popup settings and TV settings. Existing preference keys and callbacks are retained. Account/server, sync, download, diagnostics and backup controls retain their existing integrations.

Changes:
- Reduce the phone root from eight groups to six; search shows only matched entries and anchors the selected control.
- Split appearance into theme/style, home/navigation, motion and accessibility. Explain and disable motion choices overridden by accessibility or theme.
- Group playback by purpose, remove the duplicate strategy picker, expose all four policies including power saving, and include 0.5x default speed.
- Separate player picture, gesture and sleep timer pages with parent-aware back handling. Consolidate TV playback toggles and fix sessions-to-account back navigation.
- Reject non-finite speed/background values; normalize the legacy Home startup choice consistently before and after restart.

Verification:
- Local ktlint, whitespace, function-size and release/MDK metadata preflight.
- Added preference round-trip tests, page restoration and popup/TV parent navigation tests.
- Added API 35 actual Compose accessibility-click, persistence, search-anchor and override tests, with screenshots under settings-evidence.
- CI must still complete Android/TV compilation, unit tests, Android lint, release R8/DEX validation and device regressions before signing and delivery.
- External account login, server credentials and third-party authorization require real account integration testing; no such success is claimed by these isolated tests.

settings-inventory.json records statically discovered settings-control callsites, not a claim of live-account coverage. Production delivery is full ARM64 with fixed MDK, package-only, no online update publication.
