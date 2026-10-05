# The 弹幕 key says what a tap did — 1.1.3 (265)

Base: cloud `master` at `ce85150466927020094cc1902d4d35d35aefa4f0` (#219, 1.1.2). On October 5
the owner asked why tapping 弹幕 on the playback page does nothing ("播放页面弹幕按钮点击为什么没反应"),
then asked for the suggested changes and a package ("按建议改，改完打包").

## Version

- Last APK actually delivered: 1.1.2 (264), package-only run
  [37272794603](https://github.com/zhuiyun/Yfuse/actions/runs/37272794603) of #219's merge:
  `Yfuse-264-1.1.2.apk`, 29,422,566 bytes, SHA-256 `9c0f5752…392679`, `PUBLISH_UPDATE=false`,
  signed with the pinned certificate and through the Android 35–37 smoke. No packaging run has
  started since.
- This delivery is 1.1.3 (265). `version.properties` and `release-notes.txt` carry it; every
  earlier version's notes are kept.

## Merged

The branch also carries `77a36c75`, the record of the delivered 1.1.2 APK in
`audit/releases/20261005-centred-water-over-fire-1.1.2`, which `master` did not have.

## Why a tap looked like nothing

- A tap does switch comments (`DanmakuPanelActions.onToggle`); a held press opens the panel.
- All the tap changed was the key's lit state: a 12% white wash behind a 12dp glyph
  (`CircleControl`'s `selectedColor`), with no toast and no vibration (`lightFeedback = false`).
- The app ships no 弹幕来源: the list starts empty (`DanmakuPreferences.loadSources`) while 弹幕
  starts on. With no source the load returns before it starts, so nothing shows either way and
  nothing says why.
- With a source, an episode it cannot match or a load that fails is reported only in the panel
  ("未找到匹配" or the error), which a tap never shows.

## Changes

- Off is struck through: `AppIcons.DanmakuOff` is `Danmaku` with a slash and a gap cut either
  side of it, and the key cross-fades between the two.
- A tap says what it came to (`DanmakuPanelActions.onKeyToggle`), in one toast that replaces the
  last (`DanmakuKeyToastSlot`):
  - off at once: 「弹幕已关闭」;
  - on once the load it starts has settled: 「弹幕已开启 · 已匹配 N 条」, or why none show: no
    match for this episode (「按住弹幕键可手动搜索」), the load failing with the source's own
    reason, or an episode with no comments.
  - `danmakuKeyToast()` words each line. The panel's own switch stays silent: the panel already
    says all of this under it.
- With no 弹幕来源 the tap opens the 弹幕 panel, whose status now names the way to add one,
  「我的 → 弹幕设置 → 弹幕来源」, instead of a 个人中心 the app does not have.
- Tests: `PlayerControlLabelsTest` for each line; `DanmakuKeyTest` for when the key opens the
  panel, and for a host that does not report the tap still getting a switch.

## Verification before the pull request

This workspace cannot reach Google Maven, so the Android code was not compiled here.

- ktlint 1.3.1 on the nine changed Kotlin files: 0 violations.
- The struck-through glyph was drawn from the same path data as SVG and rendered at the key's
  size over a picture, on and off, before it was written.
- `python3 -m unittest discover -s scripts/tests -p 'test_*.py'`,
  `python3 -m unittest discover --start-directory scripts --pattern 'test_*.py'`, the
  function-size gate, `release_metadata.py` and `git diff --check`: passed.
- At 19:41 (Asia/Shanghai) on October 5 the release owner explicitly confirmed use and
  distribution of the existing MDK SDK for this package-only 1.1.3 (265) delivery.
  `.github/mdk-distribution-approval.json` records it with the SDK checksum and scope unchanged;
  `scripts/mdk_distribution_approval.py --package-only` returns `true`, and `false` without it.

Not covered anywhere before a device: the toast, the panel opening and the glyph on a phone.

## Packaging

Pending: the pull request's checks, its merge as `[artifact only]` and the package-only run.

Package-only delivery is intended. Do not publish an application update, create a release or
deploy a service as part of this build.
