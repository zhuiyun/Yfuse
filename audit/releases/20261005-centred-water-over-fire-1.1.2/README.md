# 水火既济 centred — 1.1.2 (264)

Base: cloud `master` at `95674f1c826f9c6218a4aae6cc9f58c46c27841d` (#218). On October 5 the owner
reported that icon 2 leans right ("2 号图标内容偏右了"), then asked for a package once it was fixed
("改完打包").

## Version

- Last APK actually delivered: 1.1.1 (263), package-only run
  [37210109423](https://github.com/zhuiyun/Yfuse/actions/runs/37210109423) of #217's merge:
  `Yfuse-263-1.1.1.apk`, 29,422,566 bytes, SHA-256 `060dba6f…fb1d44`, `PUBLISH_UPDATE=false`,
  signed with the pinned certificate and through the Android 35–37 smoke. No packaging run has
  started since.
- This delivery is 1.1.2 (264). `version.properties` and `release-notes.txt` carry it; every
  earlier version's notes are kept.

## Merged

The branch also carries `0b065288`, the record of the delivered 1.1.1 APK in
`audit/releases/20261004-notification-icon-1.1.1`, which `master` did not have.

## The icon

- What the owner saw: 水火既济's play triangle sits to the right of its icon.
- Cause: `scripts/launcher_icons/generate.py` put the triangle's centroid 18.8 units right of the
  centre of the 1024 canvas. A play triangle's point reaches far past its weight, so its box
  centre sat 84.7 right, with 83 units between the point and the edge against 252 on the flat
  side. The other four marks are centred.
- Now the triangle sits halfway between its box centre and its centroid, box +33 and centroid
  −33. A play triangle centred by its box leans left, and one centred by its centroid has its
  point near the edge; the eye puts its middle between the two. The scale (1.85x) and the reach
  (429 from the centre, inside the 469 of the 66dp safe zone) are unchanged.
- Changed: `ic_water_over_fire_foreground.xml`, `ic_water_over_fire_mono.xml` and the two SVG
  masters. The other four icons and 水火既济's status-bar icon, which is cropped to its bounds,
  regenerate byte for byte. No Kotlin changed.
- `scripts/tests/test_launcher_icons.py` measures each generated mark's themed layer and fails
  when the point halfway between its box centre and its centroid is more than 16 units from the
  middle. The old 水火既济 layer is 51.6 off and fails; 汇光 is 10.4 and the three Y marks 0.1.
- `docs/LOGO_CONCEPTS_20261004.md` explains the placement.

## Verification before the pull request

This workspace cannot reach Google Maven, so nothing was built here.

- The drawable XML, rendered under circle and rounded-square masks beside 叠印, shows the
  triangle centred, and the themed layer with it.
- Rendered back to SVG at 256 px, 水火既济's layers match the masters within 0.61% RMSE (colour)
  and 1.00% (themed); the largest of the five stays 1.06%.
- `python3 -m unittest discover -s scripts/tests -p 'test_*.py'`: 67 passed;
  `python3 -m unittest discover --start-directory scripts --pattern 'test_*.py'`: 52 passed.
- `scripts/release_metadata.py`: 1.1.2 (264) with its notes.
- At 14:03 (Asia/Shanghai) on October 5 the release owner explicitly confirmed use and
  distribution of the existing MDK SDK for this package-only 1.1.2 (264) delivery.
  `.github/mdk-distribution-approval.json` records it with the SDK checksum and scope unchanged;
  `scripts/mdk_distribution_approval.py --package-only` returns `true`, and `false` without it.

## Packaging

Pending: the pull request's checks, its merge as `[artifact only]` and the package-only run.

Package-only delivery is intended. Do not publish an application update, create a release or
deploy a service as part of this build.
