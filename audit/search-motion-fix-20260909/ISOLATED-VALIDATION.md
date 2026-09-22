# Search handoff validation without native AARs

The local Kotlin 2.2.21 CLI compiler and matching Compose compiler plugin compiled these actual workspace sources together:

- `SearchResultsHandoff.kt`
- `SearchScreen.kt`
- `PulseSweep.kt`
- `PageStates.kt`
- `OrbProgress.kt`
- `ArrivalReveal.kt` (needed by the concurrently updated `PageStates.kt` in the final run)
- `SearchResultsHandoffTest.kt`

The script does not invoke Gradle, resolve dependencies, download files, or change global settings. It reads the dependency classpath from an existing successful compiler invocation, excludes the MPV/YCore/MDK player jars, and uses existing application classes only for other dependencies. The fresh classes precede all cached classes in the JUnit runtime classpath. Each run records its source SHA-256 values and compiler/JUnit logs in a new subdirectory.

## Positive result

Command from `D:\Demo\Yfuse`:

```powershell
& ./audit/search-motion-fix-20260909/run-isolated-handoff-tests.ps1
```

Initial run: `isolated-20260909-162724-498`

Final successful run after including the updated PageStates dependency: `isolated-20260909-163101-159`

- Actual source compilation with Kotlin and Compose compiler: passed.
- JUnit: `OK (11 tests)`.
- Source hashes remained unchanged while the test was running.

This includes compilation of both `SearchScreen.kt` result-list integration points.

`PageStates.kt` was concurrently edited by other work between runs. An intermediate rerun passed compilation and all 11 tests but correctly rejected its final hash check. The final run listed above passed compilation, all 11 tests, and its complete source hash check. This establishes the tested snapshot; it does not promise that other ongoing work will leave the entire workspace unchanged afterward.

## Negative control

An audit-only copy, `negative-control/SearchResultsHandoff.kt`, changes exactly one expression from `indices.getOrPut(key) { index }` to `index`. No business source file was changed for this experiment. This simulates applying the current row index again after incremental results reorder existing content.

```powershell
& ./audit/search-motion-fix-20260909/run-isolated-handoff-tests.ps1 `
  -HandoffSource 'D:\Demo\Yfuse\audit\search-motion-fix-20260909\negative-control\SearchResultsHandoff.kt'
```

Run: `isolated-20260909-162832-211`

Compilation passed, and the same 11 tests produced exactly two failures:

- `incremental_server_results_do_not_fade_an_existing_card_back_out`: expected `0.5074596`, actual `0.030763302`.
- `server_groups_keep_their_delay_when_earlier_servers_finish`: expected `0.5074596`, actual `0.24929944`.

The negative command is expected to exit unsuccessfully; this confirms that the regression tests distinguish the fix from the previous behavior.

## Limits

These are freshly compiled helper regression tests on the JVM. Compilation validates the actual Compose source and API integration, but no Android window, recomposition-driven drawing, gesture injection, or pixel rendering runs here. Other application dependencies come from an existing cached app classes jar. This result is not a full clean application build, Android instrumentation run, APK packaging check, or native playback validation.
