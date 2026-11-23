# Public movie/episode excerpts cover codec paths missing from the long-seek matrix.
import json
import subprocess
import sys
from pathlib import Path

# Each source is a different work. Every fixture retains the visible time marker.
cases = (
    (0, 'av1', 'mkv', 'video/x-matroska', ['-c:v', 'libaom-av1', '-cpu-used', '8', '-crf', '38', '-b:v', '0', '-row-mt', '1', '-pix_fmt', 'yuv420p'], ['-c:a', 'libopus', '-b:a', '96k']),
    (1, 'hevc-main10', 'mp4', 'video/mp4', ['-c:v', 'libx265', '-preset', 'ultrafast', '-crf', '25', '-x265-params', 'pools=2:frame-threads=1:log-level=error', '-pix_fmt', 'yuv420p10le'], ['-c:a', 'eac3', '-b:a', '192k']),
    (2, 'prores422-10', 'mov', 'video/quicktime', ['-c:v', 'prores_ks', '-profile:v', '2', '-pix_fmt', 'yuv422p10le'], ['-c:a', 'pcm_s24le']),
    (68, 'mpeg2', 'ts', 'video/mp2t', ['-c:v', 'mpeg2video', '-q:v', '3', '-pix_fmt', 'yuv420p'], ['-c:a', 'mp2', '-b:a', '192k']),
    (69, 'h264-dts', 'mkv', 'video/x-matroska', ['-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '20', '-pix_fmt', 'yuv420p'], ['-c:a', 'dca', '-strict', '-2', '-b:a', '768k']),
    (70, 'h264-eac3', 'm2ts', 'video/mp2t', ['-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '20', '-pix_fmt', 'yuv420p'], ['-c:a', 'eac3', '-b:a', '192k']),
)
root = Path('qa-media')
files = []
for index, codec, suffix, mime, video, audio in cases:
    subprocess.run([sys.executable, 'qa-title-seek-check/prepare.py', str(index), '86'], check=True)
    selected = json.loads((root / 'fixtures.json').read_text())
    if len(selected) != 1:
        raise ValueError('A codec case must retain exactly one public source')
    original = selected[0]
    name = 'codec-' + codec + '.' + suffix
    target = root / name
    command = ['ffmpeg', '-nostdin', '-v', 'error', '-y', '-threads', '2',
               '-i', str(root / original['name']), '-t', '60', '-map', '0:v:0', '-map', '0:a:0',
               '-vf', 'scale=480:270', '-r', '25' if codec == 'mpeg2' else '16', '-g', '16', '-threads', '2'] + video + audio + ['-ar', '48000']
    if suffix in ('mov', 'mp4'):
        command += ['-movflags', '+faststart']
    if suffix in ('ts', 'm2ts'):
        command += ['-f', 'mpegts', '-muxdelay', '0', '-muxpreload', '0']
    if suffix == 'm2ts':
        command += ['-mpegts_m2ts_mode', '1']
    subprocess.run(command + [str(target)], check=True, timeout=720)
    result = subprocess.run(['ffprobe', '-v', 'error', '-show_entries',
        'format=duration,size,format_name:stream=codec_type,codec_name,pix_fmt,width,height,channels',
        '-of', 'json', str(target)], capture_output=True, check=True, timeout=30)
    actual = json.loads(result.stdout)
    files.append(dict(original, name=name, duration=int(float(actual['format']['duration'])),
                      codec=codec, mime=mime, size=target.stat().st_size, width=480, testFile=actual))
    print('CODEC_PREPARED', index, codec, target.stat().st_size, flush=True)
if {row['kind'] for row in files} != {'movie', 'episode'}:
    raise ValueError('Movie and episode codec cases are required')
(root / 'fixtures.json').write_text(json.dumps(files))
Path('results/codec-sources.json').write_text(json.dumps(files))
