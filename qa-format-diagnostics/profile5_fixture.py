"""Loop the measured public Profile 5 colour swatches into a seekable A/V fixture."""
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile


def probe(path, *options):
    data = subprocess.check_output(['ffprobe', '-v', 'error', *options, '-of', 'json', str(path)])
    return json.loads(data)


def video_packet_hashes(path):
    packets = probe(path, '-select_streams', 'v:0', '-show_packets', '-show_data_hash', 'sha256',
                    '-show_entries', 'packet=data_hash')['packets']
    return [packet['data_hash'] for packet in packets]


def main():
    root = Path('format-fixtures')
    catalog_path = root / 'catalog.json'
    catalog = json.loads(catalog_path.read_text())
    name = 'dolby-profile5-sdr'
    path = root / (name + '.mp4')
    encoded = Path('composeApp/src/androidInstrumentedTest/assets/ycore/dolby-profile5-swatches.mp4.b64')
    sample = base64.b64decode(b''.join(encoded.read_bytes().split()), validate=True)
    assert hashlib.sha256(sample).hexdigest() == 'e4bd2d0217e799d7e1ef13e6fe6cd517152cc7c1d50ed8ecf7fb48e98d476647'
    with tempfile.TemporaryDirectory() as tmp:
        original = Path(tmp) / 'swatches.mp4'
        original.write_bytes(sample)
        reference = video_packet_hashes(original)
        assert len(reference) == 6
        # The non-integer loop period also exposes colour changes to whole-second screen samples.
        subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-stream_loop', '-1', '-itsscale', '1.1',
                        '-i', str(original), '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000',
                        '-map', '0:v:0', '-map', '1:a:0', '-t', '64', '-c:v', 'copy', '-tag:v', 'hvc1',
                        '-strict', 'unofficial', '-c:a', 'aac', '-b:a', '64k', '-movflags', '+faststart',
                        str(path)], check=True, timeout=90)
    info = probe(path, '-show_streams', '-show_format')
    video, audio = info['streams']
    assert (video['codec_name'], video['width'], video['height'], audio['codec_name']) == ('hevc', 96, 64, 'aac')
    assert abs(float(info['format']['duration']) - 64) < .25
    conf = next(s for s in video['side_data_list'] if s['side_data_type'] == 'DOVI configuration record')
    assert [conf[k] for k in ('dv_profile', 'dv_bl_signal_compatibility_id', 'rpu_present_flag',
                               'el_present_flag', 'bl_present_flag')] == [5, 0, 1, 0, 1]
    hashes = video_packet_hashes(path)
    assert len(hashes) > 320 and all(value == reference[i % 6] for i, value in enumerate(hashes))
    frames = probe(path, '-select_streams', 'v:0', '-show_frames', '-show_entries',
                   'frame=best_effort_timestamp:frame_side_data=side_data_type')['frames']
    pts = [int(f['best_effort_timestamp']) for f in frames]
    assert len(frames) == len(hashes) and pts == sorted(set(pts))
    assert all(any('Dolby Vision Metadata' in s.get('side_data_type', '') for s in frame.get('side_data_list', []))
               for frame in frames)
    item = {'id': name, 'file': path.name, 'seconds': 64, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'probe': info, 'sourceSwatchesSha256': hashlib.sha256(sample).hexdigest(),
            'matchedVideoPackets': len(hashes), 'decodedFramesWithRpu': len(frames)}
    assert not any(row['id'] == name for row in catalog)
    catalog_path.write_text(json.dumps([*catalog, item], indent=2))
    print('Prepared public Profile 5 playback fixture with', len(frames), 'verified pictures.')


if __name__ == '__main__':
    main()
