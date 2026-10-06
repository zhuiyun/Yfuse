Small self-generated FFmpeg 6.1.1 testsrc2 fixtures, encoded as Base64 to keep reviewable text artifacts.
Each Matroska file has three MPEG-2 frames at 32x32; all frames carry field-order flags.

ffmpeg -f lavfi -i testsrc2=size=32x32:rate=50 -vf tinterlace=interleave_top -frames:v 3 -c:v mpeg2video -flags +ildct+ilme -top 1 -g 1 top25.mkv
ffmpeg -f lavfi -i testsrc2=size=32x32:rate=60000/1001 -vf tinterlace=interleave_bottom -frames:v 3 -c:v mpeg2video -flags +ildct+ilme -top 0 -g 1 bottom30.mkv
