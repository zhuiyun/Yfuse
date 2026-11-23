"""Add three generated A/V files to the normal player and gesture matrix."""
from dataclasses import dataclass
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import subprocess


@dataclass(frozen=True)
class Fixture:
    name: str
    extension: str
    video: str
    audio: str
    size: tuple[int, int]
    input_fps: int
    output_fps: int
    layout: str
    seconds: int
    options: tuple[str, ...]


CASES = [
    Fixture('vp9-opus', 'webm', 'vp9', 'opus', (854, 480), input_fps=30, output_fps=30,
            layout='stereo', seconds=40, options=(
                '-c:v', 'libvpx-vp9', '-deadline', 'realtime', '-cpu-used', '6', '-row-mt', '1',
                '-crf', '36', '-b:v', '0', '-g', '60', '-pix_fmt', 'yuv420p', '-c:a', 'libopus', '-b:a', '96k')),
    Fixture('av1-main10-flac', 'mkv', 'av1', 'flac', (640, 360), input_fps=12, output_fps=12,
            layout='stereo', seconds=48, options=(
                '-c:v', 'libaom-av1', '-cpu-used', '8', '-row-mt', '1', '-crf', '38', '-b:v', '0',
                '-g', '24', '-pix_fmt', 'yuv420p10le', '-c:a', 'flac')),
    Fixture('mpeg2-interlaced-ac3', 'ts', 'mpeg2video', 'ac3', (720, 576), input_fps=50, output_fps=25,
            layout='5.1', seconds=44, options=(
                '-vf', 'tinterlace=mode=interleave_top', '-c:v', 'mpeg2video', '-flags', '+ilme+ildct',
                '-top', '1', '-g', '12', '-q:v', '4', '-pix_fmt', 'yuv420p', '-c:a', 'ac3', '-b:a', '384k')),
]


def encode(path, case, ffmpeg='ffmpeg'):
    width, height = case.size
    tones = [220, 330, 440, 55, 550, 660] if case.layout == '5.1' else [220, 330]
    audio = '|'.join(f'0.12*sin(2*PI*{tone}*t)' for tone in tones)
    subprocess.run([ffmpeg, '-nostdin', '-hide_banner', '-v', 'error', '-y', '-filter_threads', '1',
                    '-f', 'lavfi', '-i', f'testsrc2=size={width}x{height}:rate={case.input_fps}',
                    '-f', 'lavfi', '-i', f'aevalsrc={audio}:s=48000:c={case.layout}',
                    '-map', '0:v:0', '-map', '1:a:0', '-t', str(case.seconds),
                    '-threads:v', '2', *case.options, str(path)], check=True, timeout=600)


def main():
    root = Path('format-fixtures')
    catalog_path = root / 'catalog.json'
    catalog = json.loads(catalog_path.read_text())
    for case in CASES:
        assert all(item['id'] != case.name for item in catalog)
        path = root / f'{case.name}.{case.extension}'
        encode(path, case)
        probe = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_streams', '-show_format',
                                                   '-of', 'json', str(path)]))
        video, audio = probe['streams']
        assert video['codec_name'] == case.video and (video['width'], video['height']) == case.size
        assert Fraction(video['avg_frame_rate']) == case.output_fps
        assert (audio['codec_name'], audio['channels']) == (case.audio, 6 if case.layout == '5.1' else 2)
        assert abs(float(probe['format']['duration']) - case.seconds) < 0.25
        if case.video == 'av1':
            assert video['profile'] == 'Main' and video['pix_fmt'] == 'yuv420p10le'
        if case.video == 'mpeg2video':
            assert video['field_order'] in ('tt', 'tb') and video['pix_fmt'] == 'yuv420p'
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        catalog.append({'id': case.name, 'file': path.name, 'seconds': case.seconds, 'sha256': digest, 'probe': probe})
        print(case.name, path.stat().st_size, video['codec_name'], audio['codec_name'], flush=True)
    catalog_path.write_text(json.dumps(catalog, indent=2))


if __name__ == '__main__':
    main()
