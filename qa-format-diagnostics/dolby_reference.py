"""Measure Profile 5 swatches with an independent FFmpeg/libplacebo rendering path."""
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import numpy as np


def output(*args):
    return subprocess.run(args, check=True, stdout=subprocess.PIPE).stdout


def pq_to_linear(pq):
    p = np.clip(pq, 0, 1) ** (32 / 2523)
    return (np.maximum(p - 3424 / 4096, 0) / (2413 / 128 - (2392 / 128) * p)) ** (16384 / 2610)


def sdr_reference(pq16, peak_pq):
    rgb = pq_to_linear(pq16 / 65535) * (10000 / 203)
    peak = np.maximum(1, np.clip(pq_to_linear(peak_pq / 4095) * 10000, 100, 10000) / 203)
    luma = np.maximum(rgb @ [.2627, .6780, .0593], 0)
    rgb *= ((1 + luma / peak**2) / (1 + luma))[:, None]
    matrix = np.array([[1.6605, -.5876, -.0728], [-.1246, 1.1329, -.0083], [-.0182, -.1006, 1.1187]])
    linear = np.maximum(rgb @ matrix.T, 0)
    encoded = np.where(linear <= .0031308, 12.92 * linear, 1.055 * linear**(1/2.4) - .055)
    return np.rint(255 * np.clip(encoded, 0, 1)).astype(int)


def compare_native(dest, fixture, result):
    text = output('results/profile5-colour-contract', str(fixture)).decode('ascii')
    values = np.array([[int(v) for v in line.split()] for line in text.splitlines()])
    assert values.shape == (48, 11)
    expected = [(i, pts, x, y) for i, pts in enumerate(result['pts']) for x, y in result['sampleCoordinates']]
    assert np.array_equal(values[:, :4], expected)
    target = np.array(result['applied']['rgb16']).reshape(-1, 3)
    peaks = values[:, 10]
    assert np.all(peaks == 3696), 'This fixture has the same source mastering peak in both RPUs'
    sdr = sdr_reference(target, peaks)
    pq_distance = int(np.abs(values[:, 4:7] - target).max())
    sdr_distance = int(np.abs(values[:, 7:10] - sdr).max())
    # Two 10-bit input codes before tone mapping and two 8-bit display codes after it.
    result['nativeComparison'] = {'observations': len(expected), 'peakPq': sorted(set(peaks.tolist())),
        'maxPq16ChannelError': pq_distance, 'maxSdr8ChannelError': sdr_distance,
        'sdr8Reference': sdr.reshape(6, 8, 3).tolist()}
    (dest / 'reference.json').write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    assert pq_distance <= 128 and sdr_distance <= 2, result['nativeComparison']


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
    compare_native(dest, fixture, result)
    print('48 native colours and SDR swatches compared with independent Vulkan rendering.')


if __name__ == '__main__':
    main()
