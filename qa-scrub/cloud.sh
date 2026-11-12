set -eu
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell wm dismiss-keyguard
apk=$(find app/composeApp/build/outputs/apk/debug -name '*.apk' | head -1)
adb install -r -g "$apk"
pkg=com.yfuse.validation
dir=/sdcard/Android/data/$pkg/files/qa
adb shell mkdir -p "$dir"
adb push qa-media/ProbeA.mp4 "$dir/"
adb push qa-media/ProbeB.mp4 "$dir/"
open_clip() {
 adb shell am start -W -a android.intent.action.VIEW -t video/mp4 -d "file://$dir/Probe$1.mp4" -n "$pkg/com.yfuse.feature.player.ExternalPlaybackActivity"
}
trap 'adb logcat -d -t 3000 > qa-results/logcat.txt; adb shell dumpsys media_session > qa-results/media-session.txt' EXIT
mkdir -p qa-results
open_clip A
sleep 4
rc=0
python3 qa-scrub/run.py --out qa-results/a --duration 150 || rc=1
adb shell input keyevent 4
sleep 2
open_clip B
sleep 3
python3 qa-scrub/run.py --out qa-results/b --duration 105 --targets .71,.16,.8,.22 || rc=1
exit "$rc"
