"""Generate a public moving AVC/AAC source with a pixel-readable second counter."""
from pathlib import Path
import subprocess
import sys


def create(destination, ffmpeg='ffmpeg'):
    filters = ['drawbox=x=0:y=16:w=300:h=40:color=black:t=fill',
               'drawbox=x=4:y=20:w=24:h=32:color=white:t=fill']
    for bit in range(7):
        filters.append(f"drawbox=x={68 + 32 * bit}:y=20:w=24:h=32:color=white:t=fill:"
                       f"enable='mod(floor(t/{2 ** bit}),2)'")
    subprocess.run([ffmpeg, '-nostdin', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=s=640x360:r=24',
                    '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000', '-vf', ','.join(filters),
                    '-t', '96', '-c:v', 'libx264', '-preset', 'veryfast', '-pix_fmt', 'yuv420p', '-threads', '2',
                    '-g', '24', '-c:a', 'aac', '-b:a', '64k', '-movflags', '+faststart', str(destination)],
                   check=True, timeout=180)
    for second in (0, 5, 37, 66, 93):
        pixels = subprocess.check_output([ffmpeg, '-v', 'error', '-ss', str(second + .25), '-i', str(destination),
                                          '-frames:v', '1', '-vf', 'scale=160:90', '-pix_fmt', 'rgb24',
                                          '-f', 'rawvideo', 'pipe:1'], timeout=20)
        def red(x):
            return pixels[(9 * 160 + x) * 3]
        assert len(pixels) == 160 * 90 * 3 and red(4) >= 180 and red(12) <= 65
        assert sum((red(20 + 8 * bit) >= 180) << bit for bit in range(7)) == second


if __name__ == '__main__':
    create(Path(sys.argv[1]))
