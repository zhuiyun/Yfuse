"""Measure Profile 5 swatches with an independent FFmpeg/libplacebo rendering path."""
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import numpy as np


def output(*args):
    return subprocess.run(args, check=True, stdout=subprocess.PIPE).stdout


def main():
    dest = Path('results/dolby-reference')
    dest.mkdir(parents=True, exist_ok=True)
    encoded = Path('composeApp/src/androidInstrumentedTest/assets/ycore/dolby-profile5-swatches.mp4.b64')
    fixture = dest / 'synthetic-profile5.mp4'
    fixture.write_bytes(base64.b64decode(b''.join(encoded.read_bytes().split()), validate=True))
    digest = hashlib.sha256(fixture.read_bytes()).hexdigest()
    assert digest == 'e4bd2d0217e799d7e1ef13e6fe6cd517152cc7c1d50ed8ecf7fb48e98d476647'
    probe = json.loads(output('ffprobe', '-v', 'error', '-select_streams', 'v:0',
                              '-show_streams', '-show_frames', '-of', 'json', str(fixture)))
    stream, = probe['streams']
    dovi, = [s for s in stream['side_data_list'] if s.get('side_data_type') == 'DOVI configuration record']
    assert dovi['dv_profile'] == 5 and not dovi['dv_bl_signal_compatibility_id']
    frames = probe['frames']
    assert len(frames) == 6 and all(any('Dolby Vision Metadata' in s.get('side_data_type', '')
                                       for s in f['side_data_list']) for f in frames)
    pts = [int(f['best_effort_timestamp']) for f in frames]
    assert pts == sorted(set(pts))
    size = stream['width'], stream['height']
    assert size == (96, 64)
    width, height = size
    locations = [(x,y) for y in (16,48) for x in (12,36,60,84)]
    result = {'fixtureSha256': digest, 'width': width, 'height': height, 'pts': pts,
              'timeBase': stream['time_base'], 'sampleCoordinates': locations,
              'ffmpeg': output('ffmpeg','-version').decode().splitlines()[0],
              'containerProfile': dovi, 'framesWithParsedRpu': len(frames),
              'pixelEncoding': 'full-range 16-bit PQ BT.2020 RGB, before SDR tone mapping'}
    raw = output('ffmpeg','-v','error','-i',str(fixture),'-pix_fmt','yuv420p10le','-f','rawvideo','pipe:1')
    size_bytes = width * height * 3
    assert len(raw) == 6 * size_bytes
    for i in (0, 2, 4):
        assert raw[i*size_bytes:(i+1)*size_bytes] == raw[(i+1)*size_bytes:(i+2)*size_bytes]
    for apply in (False, True):
        options = ('libplacebo=format=gbrp16le:colorspace=0:color_primaries=bt2020:'
                   'color_trc=smpte2084:range=pc:peak_detect=0:dithering=none:deband=0:'
                   'upscaler=nearest:downscaler=nearest:apply_dolbyvision=' + str(int(apply)))
        # Software AVFrames preserve parsed side data at the filter boundary; only
        # the colour transform runs on the host's pinned-distribution Vulkan driver.
        pixels = output('ffmpeg','-v','error','-init_hw_device','vulkan=vk:0','-filter_hw_device','vk',
                        '-i',str(fixture),'-vf',options,'-pix_fmt','gbrp16le','-f','rawvideo','pipe:1')
        planes = np.frombuffer(pixels, dtype='<u2').reshape(6,3,height,width)
        values = np.array([[planes[i, [2,0,1], y, x] for x,y in locations] for i in range(6)], dtype='int32')
        differences = [int(np.abs(values[i]-values[i+1]).max()) for i in (0,2,4)]
        result['applied' if apply else 'disabled'] = {'rgb16': values.tolist(),
            'maxPairedChannelDifferences': differences, 'rawOutputSha256': hashlib.sha256(pixels).hexdigest()}
        (dest / 'reference.json').write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
        if apply:
            assert min(differences) > 256, 'Changing only the RPU must substantially change each colour pair'
        else:
            assert max(differences) <= 2, 'Identical base codes must match when RPU processing is disabled'
    print('Six frames, three identical base pairs, 48 independent RGB reference values.')


if __name__ == '__main__':
    main()
