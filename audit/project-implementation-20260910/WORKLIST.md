# Implementation worklist

User authorized all recommendations from audit/project-review-20260910/REVIEW.md except native device-matrix and 8/24-hour stability evidence (user will run those).

- [x] Restore full mobile/TV compilation without fake permission APIs.
- [x] Bottom subtitle stack for Core2, Exo and MPV text; primary above secondary. MPV image/unknown formats and MDK retain engine layout and show capability limits.
- [x] Subtitle presets: language pair, swap, independent size where supported, position preview, persistence.
- [x] Download budget, charging-only auto-downloads, time windows; UI and tests.
- [x] Timestamp bookmarks: persistent isolated identity, name/note, seek UI, tests.
- [x] Isolate TV UI and shared build metadata; extract ambient-light binding from PlayerRoot incrementally.
- [x] Independent fast CI checks; preserve native and release gates. Workflow not executed remotely.
- [x] Remove media discovery from active Android/TV/Harmony sources.
- [x] Extend performance journeys and compile locally. Phone testing stopped by user; incomplete measurements are not a baseline. No Profile generated.
- [ ] Harmony HAP acceptance: real Emby/Jellyfin login/library/detail/play/progress wired in source; host tests and native syntax check pass, but matching DevEco Cangjie SDK and host stdx are absent. No HAP or runtime claim.
- [x] Final local checks and implementation report. No push performed.

Preserve all pre-existing dirty source changes. No native matrix/stress/8h/24h execution in this task.

Latest user instruction: do not test on the phone. No further device actions are authorized for this task.
See RESULTS.md for exact evidence and limitations.
