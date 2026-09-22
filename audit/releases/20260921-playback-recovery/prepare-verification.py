"""Reuse the established APK verifier with this delivery's actual baseline and markers."""
from pathlib import Path

audit = Path(__file__).resolve().parent
previous = audit.parent / '20260920-update-status'
script = (previous / 'verify-release.py').read_text(encoding='utf-8')
script = script.replace('update-status-1.0.76-238/Yfuse-1.0.76-238-full-arm64-signed.apk',
                        'playback-recovery-1.0.77-239/Yfuse-1.0.77-239-full-arm64-signed.apk')
script = script.replace('update-check-1.0.75-237/Yfuse-1.0.75-237-full-arm64-signed.apk',
                        'update-status-1.0.76-238/Yfuse-1.0.76-238-full-arm64-signed.apk')
script = script.replace('previous.get("versionName") == "1.0.75" and previous.get("versionCode") == "237"',
                        'previous.get("versionName") == "1.0.76" and previous.get("versionCode") == "238"')
script = script.replace('baseline 1.0.75 (237)', 'baseline 1.0.76 (238)')
script = script.replace('properties.get("VERSION_NAME") == "1.0.76"', 'properties.get("VERSION_NAME") == "1.0.77"')
script = script.replace('properties.get("VERSION_CODE") == "238"', 'properties.get("VERSION_CODE") == "239"')
script = script.replace('require("更新源版本落后于当前安装版本，请稍后重试".encode("utf-8") in old_dex, "Previous APK does not reproduce the older-feed error")',
'''require(b"enhanced_buffer_progress" not in old_dex, "Unexpected baseline playback diagnostic markers")
        for marker in ("enhanced_buffer_progress", "transport_range_progress", "enhanced_source_adoption",
                       "enhanced_source_open", "enhanced_select_tracks_first_packet", "trackBufferedUs",
                       "Enhanced buffer made no packet progress for 35 seconds", "片源访问被拒绝"):
            require(marker.encode("utf-8") in dex, f"New playback fix/diagnostic marker absent: {marker}")''')
(audit / 'verify-release.py').write_text(script, encoding='utf-8')
print('Prepared release verification for 1.0.77 (239), baseline 1.0.76 (238).')
