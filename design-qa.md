# Product Design QA

final result: blocked

Status (2026-10-22): the user explicitly paused all phone operations and requested code changes first. The 1.5.6 (308) first candidate was tested before that pause and exposed an Android 28 dialog cutout-mode issue. Code now includes its API-specific repair plus the requested statistics, tracking-center and search-filter redesigns, pale glass buttons and unboxed poster ratings. Final-source phone and TV compilation and 1,277 regression tests passed, with zero failures, errors or skipped tests. The final signed full-MDK package passed artifact verification. Final native visual acceptance is pending the user's resumption of device work; no phone queries, installation, screenshots or cleanup have run during the pause.

Current code-phase scope and independent review: [non-player UI code review](audit/all-ui-20261022/code-phase-review.md). Complete source inventory: [UI coverage inventory](audit/all-ui-20261022/ui-coverage-inventory.md). The following 1.5.5 verification and screenshots are historical evidence, not acceptance of the latest code.

Reference and scope: [design brief](audit/product-design-20261021/design-brief.md).

Evidence and first-round findings: [native QA round 1](audit/product-design-20261021/native-qa-round1.md).

The current round also includes the user's follow-up requests: refine the server-operation sheet and settings appearance, add bright selection outlines throughout media details, and show Stremio playback sources directly within details. Settings/server evidence is tracked in [the coordinated QA report](audit/settings-server-design-20261021/design-qa.md).

## Current 1.5.6 code and package verification

- Actual APK package/version: `com.yfuse`, `1.5.6` / `308`, full ARM64 including pinned MDK. Configuration and release-note first line match; the previous delivered APK was `1.5.5` / `307`.
- APK: [Yfuse-1.5.6-full-arm64.apk](artifacts-local/releases/modal-polish-1.5.6-308/Yfuse-1.5.6-full-arm64.apk), 38,435,986 bytes.
- SHA-256: `d5f3505a884947808de195094de250d819fc65cf66bbb3549a76ac1a8cd37e75`.
- Phone and TV compilation and 1,277 regression tests passed. The added real-material contrast check caught and verified a fix for dark-theme primary-button text after frosted diffusion.
- Production V2 signature, 21 native libraries, actual pinned MDK binaries, 16 KB ZIP/native alignment, and DEX verified. DEX: 14,816 classes, 65,145 methods with code, zero findings and zero unanalyzable methods. Source inputs were rechecked after build.
- [Machine-readable verification](artifacts-local/releases/modal-polish-1.5.6-308/verification.json) and [regression results](artifacts-local/releases/modal-polish-1.5.6-308/regression.json).
- This final APK has **not** been installed or visually accepted. The earlier same-version candidate and its screenshots are archived separately under `audit/modal-polish-20261022/candidate-1`. No source was pushed or APK uploaded.

## Historical 1.5.5 candidate verification

- Version: 1.5.5 (307), package `com.yfuse`, full ARM64 build including the pinned MDK runtime.
- APK: [Yfuse-1.5.5-full-arm64.apk](artifacts-local/releases/product-design-1.5.5-307/Yfuse-1.5.5-full-arm64.apk).
- Size: 38,405,350 bytes. SHA-256: `46753aa4338668599dd9af25b62ed3c7e64a19eb3fcf84ae49a4db05dcfe6801`.
- Phone and TV compilation passed. 990 regression tests passed, with zero failures, errors, or skipped tests.
- Production V2 signature, actual MDK libraries, DEX (zero findings or unanalyzable methods), and ZIP/native 16 KB alignment verified. Final source snapshot rechecked after the build.
- Full machine-readable record: [verification.json](artifacts-local/releases/product-design-1.5.5-307/verification.json).

These checks establish build and artifact integrity, not final visual acceptance. The first candidate's screenshots and 798-test run do not verify the second candidate's refinements.

## Remaining native checks

1. After the user resumes device work, install the final verified 1.5.6 candidate without clearing app data. The device currently has the earlier 1.5.6 candidate; this retry retains the same version because it has not been delivered.
2. Compare full-window modal scrim/cutout behavior, Widget style-gallery text, bright detail selections, and unboxed ratings on the final APK.
3. Inspect the new statistics, tracking-center and search-filter layouts, settings/server controls, button separation, long text, enlarged fonts, light/dark/custom glass, keyboard and scrolling states.
4. Remove only temporary QA Widget/page/Stremio fixtures and reverse mapping after verification. Cleanup is also paused to honor the user's instruction; no user content is to be cleared.

The available physical device runs Android 9 / API 28. Its screenshots can verify the fallback glass appearance, but cannot establish Android 31+ real-time blur behavior. No final-candidate device result or visual pass is claimed.

## Reconnected-device findings, 2026-10-22

- Installed 1.5.5 from the verified SHA-256 `46753aa4338668599dd9af25b62ed3c7e64a19eb3fcf84ae49a4db05dcfe6801`, replacing the first candidate without clearing data.
- `audit/product-design-20261021/device/final-manager.png` shows the full-page background continuing through the status bar. The previous gray strip on the full Widget page is resolved.
- `final-install-card.png` was viewed alongside the supplied install reference: metadata and install controls are distinct, and changing the URL is a compact edit control within the metadata card.
- `final-gallery.png` was viewed alongside the supplied gallery reference: the sheet height improved, but two text containers' rounded clips cut the first character. The clips are being removed only from text containers; poster corners remain rounded.
- Opening a modal still produces a separate gray strip at the status bar. This is an explicit user-requested fix; native dimming must not compound the application's own scrim.
- `final-detail-top.png` and `final-detail-episodes.png` show the rating's heavy background frame and the selected outline darkened to gray-blue. The requested replacement is source icon plus score without a frame, and a light selection outline.
- The coordinated settings/server screenshots named `baseline155-*` belong to installed 1.5.5 and are baseline evidence for the next build, not proof that the 1.5.6 fixes passed.
