"""Create public, synthetic counterparts to the failing original media profiles."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

if os.environ.get('GITHUB_ACTIONS') != 'true':
    raise SystemExit('Cloud fixture generation required')
out = Path('format-fixtures'); out.mkdir(exist_ok=True)
cases = [
    ('long-avc', 'mp4', '640x360', 2, 12000, ['-c:v', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p', '-g', '2']),
    ('avc-cropped', 'mp4', '1916x800', 24, 32, ['-c:v', 'libx264', '-preset', 'veryfast', '-profile:v', 'high', '-pix_fmt', 'yuv420p', '-g', '24']),
    ('hevc-main', 'mkv', '1920x804', 24, 32, ['-c:v', 'libx265', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p', '-x265-params', 'pools=2:frame-threads=2:keyint=24']),
    ('hevc-main10', 'mp4', '1920x1080', 24, 32, ['-c:v', 'libx265', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p10le', '-x265-params', 'pools=2:frame-threads=2:keyint=24']),
    ('prores-4444', 'mov', '2048x858', 24, 20, ['-c:v', 'prores_ks', '-profile:v', '4', '-pix_fmt', 'yuv444p10le']),
    ('avc-transport', 'ts', '1920x804', 25, 32, ['-c:v', 'libx264', '-preset', 'veryfast', '-profile:v', 'high', '-pix_fmt', 'yuv420p', '-g', '25']),
    ('hevc-4k', 'mp4', '3840x2160', 24, 20, ['-c:v', 'libx265', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p', '-x265-params', 'pools=2:frame-threads=2:keyint=24']),
]
catalog = []
for name, extension, size, rate, duration, codec in cases:
    path = out / (name + '.' + extension)
    audio = ['-c:a', 'pcm_s24le'] if extension == 'mov' else ['-c:a', 'aac', '-b:a', '64k']
    args = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-threads', '2',
            '-f', 'lavfi', '-i', f'testsrc2=size={size}:rate={rate}',
            '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000',
            '-t', str(duration), '-threads', '2'] + codec + audio
    if extension in ('mp4', 'mov'): args += ['-movflags', '+faststart']
    subprocess.run(args + [str(path)], check=True, timeout=900)
    probe = json.loads(subprocess.check_output(['ffprobe', '-v', 'quiet', '-show_entries',
        'format=duration,size,format_name:stream=codec_name,profile,width,height,pix_fmt', '-of', 'json', str(path)]))
    video = next(s for s in probe['streams'] if s.get('width'))
    expected = {'long-avc': ('h264', 'Constrained Baseline'), 'avc-cropped': ('h264', 'High'),
                'hevc-main': ('hevc', 'Main'), 'hevc-main10': ('hevc', 'Main 10'),
                'prores-4444': ('prores', '4444'), 'avc-transport': ('h264', 'High'),
                'hevc-4k': ('hevc', 'Main')}[name]
    assert (video['codec_name'], video['profile']) == expected, (name, video)
    assert (video['width'], video['height']) == tuple(map(int, size.split('x')))
    catalog.append({'id': name, 'file': path.name, 'seconds': duration,
                    'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'probe': probe})
    print('Created', name, path.stat().st_size, flush=True)
(out / 'catalog.json').write_text(json.dumps(catalog, indent=2))
