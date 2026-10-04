# Notifications and shortcuts follow the chosen icon — 1.1.1 (263)

Base: cloud `master` at `73f7370e0b4aff15d6ce37b116266104862a671b` (#216, 1.1.0). On October 4
the owner asked to merge all code, fix push notifications not using the logo chosen in the
settings ("修复推送 logo 没有使用设置的 logo 的问题"), then sign and package.

## Version

- Last APK actually delivered: 1.1.0 (262), package-only run
  [37183767245](https://github.com/zhuiyun/Yfuse/actions/runs/37183767245) of #216's merge:
  `Yfuse-262-1.1.0.apk`, 29,413,982 bytes, SHA-256 `66eb8a8f…80846d`, `PUBLISH_UPDATE=false`,
  signed with the pinned certificate and through the Android 35–37 smoke.
- This delivery is 1.1.1 (263). `version.properties` and `release-notes.txt` carry it; every
  earlier version's notes are kept.

## Merged

With the full history fetched, two branches held work that `master` did not, both records:

- `ccr-42d7b5b2-pdkr1p` at `061d3405`: the delivered 1.1.0 APK, added to
  `audit/releases/20261004-launcher-icons-1.1.0`.
- `ccr-afc1c9d3-phenca` at `0cd3e24d`: the verified 1.0.99 APK, added to
  `audit/releases/20261003-merge-all-1.0.99`; merged in `33057c83` without conflicts.

No pull request was open. Every other remote branch is already contained in `master`.

## The notification icon

- What the owner saw: on ColorOS (the owner's OPPO PLG110) and in Android 16's restyled shade,
  the icon at a notification's top left is the app icon, and it stays the default after another
  icon is chosen. The system reads it from the installed package's application icon: AOSP's
  `AppIconProviderImpl` loads `pm.getApplicationInfo(packageName, 0).loadUnbadgedIcon(pm)`, and
  OPPO documents the top-left icon as the app's icon, with the right-hand icon configurable.
  Switching icons enables a launcher `activity-alias`; the `<application>` icon cannot change at
  runtime, and a self-targeting resource overlay (API 34) is a file in the app's private storage,
  which the system UI never loads.
- Offered the parts an app controls, the owner chose all of them:
  - 追剧更新 reminders use the chosen icon's one-colour mark as the small icon (status bar, and
    the header wherever the system draws the small icon there) instead of the calendar glyph,
    and, for any icon but the default, the chosen icon as the large icon on the right.
  - The launcher shortcuts (继续播放, 搜索, 下载) show the chosen icon instead of the default.
- Downloads, the app update, casting and playback keep their own status glyphs.

## Changes

- `AppIconVariant.launcherIcon()` and `notificationIcon()` map each variant to its launcher icon
  and its 24dp status-bar icon; `setChosenAppIcon()` (`core/notification/ChosenAppIcon.kt`)
  applies both to a notification builder.
- Status-bar icons, white on transparent, the mark cropped to its bounds and centred in 24dp with
  1dp clear:
  - the five 2026-10 icons: drawn from their themed layers by `scripts/launcher_icons/generate.py`;
  - 极光深色 and 极光浅色, which have no themed layer: the Y traced from
    `yfuse_aurora_dark.webp` by `scripts/launcher_icons/trace_aurora.py`;
  - 当前 Logo, 石墨 and 旧版云朵播放器: their themed layers, cropped by hand.
- `ic_notification_calendar.xml` had no other use and is removed.
- `scripts/tests/test_launcher_icons.py` checks that each variant's shortcut icon is the icon its
  launcher entry shows and that its status-bar icon exists as a 24dp, single-colour vector.

## Verification before the pull request

This workspace cannot reach Google Maven, so the Android code was not compiled here.

- ktlint 1.3.1 on the four changed Kotlin files: 0 violations; a deliberate violation is
  reported.
- `test_launcher_icons.py`: 3 passed; mapping Prism's shortcut to another icon, or Danmaku's
  status-bar icon to a missing one, fails it.
- `generate.py` rewrites the committed launcher layers byte for byte; the eight status-bar icons
  were rendered from their XML at 18–96 px and read clearly.
- `scripts/release_metadata.py`: 1.1.1 (263) with its notes.
- At 21:07 (Asia/Shanghai) on October 4 the release owner explicitly confirmed use and
  distribution of the existing MDK SDK for this package-only 1.1.1 (263) delivery.
  `.github/mdk-distribution-approval.json` records it with the SDK checksum and scope unchanged;
  `scripts/mdk_distribution_approval.py --package-only` returns `true`, and `false` without it.

## Pending

- The pull request's quality gates, merging it into `master` as `[artifact only]`, the packaging
  run's production signing and Android 35–37 smoke, and reading the final APK's package name,
  version, size, SHA-256 and signing certificate.
- No device check of the notification or the shortcuts: the CI smoke posts no 追剧更新 reminder
  and opens no launcher menu.

Package-only delivery is intended. Do not publish an application update, create a release or
deploy a service as part of this build.
