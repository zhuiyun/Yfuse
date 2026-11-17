"""Retain original fixtures; add enough 4K tail and a long-GOP/chapter counterpart."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

if os.environ.get('GITHUB_ACTIONS') != 'true':
    raise SystemExit('Cloud fixture generation required')
root = Path('format-fixtures')
catalog = json.loads((root / 'catalog.json').read_text())


def record(name, file, seconds):
    path = root / file
    probe = json.loads(subprocess.check_output(['ffprobe', '-v', 'quiet', '-show_entries',
        'format=duration,size,format_name:stream=codec_name,profile,width,height,pix_fmt:chapter',
        '-of', 'json', str(path)]))
    assert abs(float(probe['format']['duration']) - seconds) < 1
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    catalog.append({'id': name, 'file': file, 'seconds': seconds, 'sha256': digest, 'probe': probe})


subprocess.run(['ffmpeg', '-v', 'error', '-y', '-stream_loop', '2', '-i', str(root / 'hevc-4k.mp4'),
    '-map', '0', '-c', 'copy', '-t', '60', '-movflags', '+faststart', str(root / 'hevc-4k-60s.mp4')],
    check=True, timeout=120)
# The earlier 20-second source remains in the catalogue and keeps its independent verdict.
record('hevc-4k-60s', 'hevc-4k-60s.mp4', 60)
subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=2',
    '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000', '-t', '12000',
    '-threads', '2', '-c:v', 'libx264', '-preset', 'veryfast', '-profile:v', 'high',
    '-pix_fmt', 'yuv420p', '-g', '20', '-keyint_min', '20', '-sc_threshold', '0',
    '-c:a', 'aac', '-b:a', '64k', '-movflags', '+faststart', str(root / 'long-gop.mp4')],
    check=True, timeout=900)
record('long-gop', 'long-gop.mp4', 12000)
metadata = root / 'chapters.txt'
metadata.write_text(';FFMETADATA1\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=7524000\nEND=8000000\n'
                    'title=Seek chapter marker\n')
subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(root / 'long-gop.mp4'), '-i', str(metadata),
    '-map', '0', '-map_metadata', '1', '-map_chapters', '1', '-c', 'copy', '-movflags', '+faststart',
    str(root / 'long-gop-chapter.mp4')], check=True, timeout=120)
record('long-gop-chapter', 'long-gop-chapter.mp4', 12000)
assert catalog[-1]['probe']['chapters']
(root / 'catalog.json').write_text(json.dumps(catalog, indent=2))
print('Retained seven original sources; added three independently hashed diagnostic sources', flush=True)
