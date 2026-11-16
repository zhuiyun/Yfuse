# Cloud power activity and native UI audit

This suite is a measurement tool, not an automatic declaration of low energy usage or complete UI acceptance.

## Evidence and limits

- Only disposable GitHub ARM64 Android guests are allowed. No physical phone is selected.
- Preserve the APK hash, product commit, cloud image digest, configuration and test-script commit for every comparison.
- Run a short instrument pilot first, then 240 minutes per playback engine on the same APK. Repeat the same schedule after fixes and compare equal phases, separating interventions and warm-up.
- The guest battery is simulated. Report its exposed values as simulated; physical discharge, mW, physical mAh and feature-level physical energy remain unavailable. Do not infer zero consumption from a fixed battery percentage or an absent counter.
- Retain Batterystats model estimates only when present, distinctly labelled. Feature phases are whole-app scenarios, not proof of additive per-module energy attribution.
- CPU main-process ticks, UI frame counts, UID Batterystats records, alarms, jobs, wake locks, location records and controlled HTTP requests are retained. ADB collection overhead and induced wakeups limit suspend conclusions.
- A generated 640×360 H.264/AAC source provides repeatable workload. It does not replace the broader original-movie/episode codec qualification or represent physical hardware decoding energy.

## Review and iteration

1. Baseline: collect raw samples/CSV and current-run full-resolution page screenshots with semantic trees. Manually inspect every accepted image. Mark inaccessible routes and unsupported counters explicitly.
2. Classify each observed anomaly: unintended background work, wake-lock retention, unexpected location access, recurring network requests, idle rendering, playback/decoder work, or collection/environment effects. Correlate multiple counters and code before assigning causality.
3. Record each issue with ID, screenshot or sample interval, reproduction, severity, cause, change commit and verification result. A capture error is a coverage gap, not automatically a product bug.
4. Correct confirmed issues using existing design tokens and navigation patterns; prioritize clear hierarchy, readable contrast, spacing, touch targets, text scaling, reduced motion and unnecessary visual complexity.
5. Repeat the same cloud scenarios, inspect before/after images, and rerun affected interactions plus the existing player qualification. Reopened failures stay open; do not weaken thresholds to call them fixed.
6. Final report includes raw data, activity/model curves, unsupported physical metrics, abnormal scenarios, page/state coverage, UI changes, iteration ledger and unresolved limits. Signing remains blocked by unresolved required functional failures.

## References

- Android emulator battery is simulated: https://developer.android.com/studio/run/emulator-extended-controls
- Batterystats diagnostics and approximate UID power: https://developer.android.com/tools/dumpsys
- Physical power measurement limitations: https://developer.android.com/studio/profile/power-profiler

Current captures initially cover 34 unpopulated native routes plus seven populated-library routes in light and dark/130% font configurations. The library uses original bundled artwork and synthetic metadata through a loopback API. The 240-minute measurement schedule includes 13 phases and records fixture API polling separately from media bytes. These do not claim every screen: authenticated account/session, player menus, widgets, casting and secondary dialogs are added to the coverage ledger as their current-run evidence is collected.
