set -euo pipefail
mkdir -p /tmp/yfuse-design-media
ffmpeg -loglevel error -f lavfi -i 'testsrc2=size=640x360:rate=24' -f lavfi -i 'sine=frequency=440:sample_rate=48000' -t 90 -c:v libx264 -preset ultrafast -crf 33 -c:a aac -movflags +faststart /tmp/yfuse-design-media/clip.mp4
ffmpeg -loglevel error -f lavfi -i testsrc2=size=480x720 -frames:v 1 -threads 1 /tmp/yfuse-design-media/poster.jpg
python3 audit/design358/sample.py /tmp/yfuse-design-media > /tmp/design358-media.log 2>&1 &
echo $! > /tmp/design358-media.pid
curl --retry 5 --retry-connrefused -fsS http://127.0.0.1:8096/System/Info/Public > /dev/null
