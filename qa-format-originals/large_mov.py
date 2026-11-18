"""Verify the publisher's full-resolution Tears of Steel MOV and its real 64-bit packet offsets."""
import hashlib
from http.client import IncompleteRead
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import zlib


NAME = 'tearsofsteel_4k.mov'
SIZE = 6_737_592_810
CRC = 3_343_219_934
ARCHIVE_SIZE = 6_737_593_114
MIRRORS = (
    'https://mirror.clarkson.edu/blender/demo/movies/ToS/tearsofsteel_4k.mov.zip',
    'https://ftp.nluug.nl/pub/graphics/blender/demo/movies/ToS/tearsofsteel_4k.mov.zip',
)
RANGES = {0: '4a8c7af5523be6ac11d0af5b46940acdff826fbcbf4c585dc7a3aad256d9a771',
          2**32: '53713d5fa5787ab5de83fb86db40491d16faecd5aaf2d7d20761dbaa84b65384',
          6_000_000_000: 'dbf632d1d88d2e440c89b0df6f420d2d879727e380d977908b38b54b20a1fd47'}


def request_range(url, start, end):
    response = urlopen(Request(url, headers={'Range': f'bytes={start}-{end}', 'Accept-Encoding': 'identity'}), timeout=50)
    if response.status != 206 or response.headers.get('Content-Range') != f'bytes {start}-{end}/{ARCHIVE_SIZE}':
        response.close()
        raise OSError('Publisher mirror did not serve the exact requested byte interval')
    return response


def member_offset(url):
    with request_range(url, 0, 255) as response:
        header = response.read()
    signature, _, flags, method, _, _, crc, packed, plain, n, extra = struct.unpack_from('<4s5H3I2H', header)
    assert (signature, flags, method, crc, packed, plain) == (b'PK\x03\x04', 0, 0, CRC, 0xffffffff, 0xffffffff)
    assert header[30:30+n].decode() == NAME
    end = 30+n+extra
    pos = 30+n
    size = None
    while pos < end:
        kind, length = struct.unpack_from('<HH', header, pos)
        if kind == 1:
            size = struct.unpack_from('<QQ', header, pos+4)
        pos += 4+length
    assert pos == end and size == (SIZE, SIZE)
    return end


def fetch_movie(path):
    if shutil.disk_usage(path.parent).free < SIZE + 1024**3:
        raise OSError('Insufficient disk for the single complete MOV and 1 GiB of remaining space')
    manifests = {}
    for url in MIRRORS:
        try:
            offset = member_offset(url)
            for pos, expected in RANGES.items():
                with request_range(url, offset+pos, offset+pos+1023) as response:
                    assert hashlib.sha256(response.read()).hexdigest() == expected
            manifests[url] = offset
        except (HTTPError, OSError) as error:
            print('Mirror preflight unavailable:', url, type(error).__name__, flush=True)
    if not manifests:
        raise OSError('No publisher mirror passed the three offset checks')
    urls = list(manifests)
    index = failures = position = crc = 0
    digest = hashlib.sha256()
    with path.open('wb') as movie:
        while position < SIZE:
            url = urls[index % len(urls)]
            end = min(position + 64 * 1024**2, SIZE) - 1
            try:
                offset = manifests[url]
                with request_range(url, offset+position, offset+end) as response:
                    while position <= end:
                        data = response.read(min(1024**2, end-position+1))
                        if not data:
                            raise OSError('Short mirror response')
                        movie.write(data)
                        digest.update(data)
                        crc = zlib.crc32(data, crc)
                        position += len(data)
                failures = 0
            except (OSError, IncompleteRead):
                index += 1
                failures += 1
                if failures >= 4:
                    raise
                time.sleep(failures)
    assert position == path.stat().st_size == SIZE and crc == CRC
    return digest.hexdigest(), list(manifests)


def prepare():
    p = Path('format-fixtures')
    p.mkdir(exist_ok=True)
    movie = p / NAME
    digest, mirrors = fetch_movie(movie)
    def probe(*args):
        return json.loads(subprocess.check_output(['ffprobe', '-v', 'error', *args, '-of', 'json', str(movie)], timeout=90))
    info = probe('-show_entries', 'format=size,duration,format_name:stream=codec_type,codec_name,profile,width,height,pix_fmt')
    video = next(s for s in info['streams'] if s.get('codec_type') == 'video')
    assert int(info['format']['size']) == SIZE and (video['codec_name'], video['width']) == ('h264', 3840)
    seconds = float(info['format']['duration'])
    assert 700 < seconds < 800
    fractions = (.82, .21)
    offsets = {}
    for fraction in fractions:
        packets = probe('-read_intervals', f'{seconds*fraction:.3f}%+#8', '-select_streams', 'v:0',
                        '-show_entries', 'packet=pts_time,pos,size')['packets']
        assert packets and abs(float(packets[0]['pts_time']) - seconds*fraction) < 20
        offsets[str(fraction)] = int(packets[0]['pos'])
    assert offsets['0.21'] < 2**31 and offsets['0.82'] > 2**32
    item = {'id': 'tears-of-steel-4k-original', 'file': NAME, 'seconds': round(seconds), 'sha256': digest, 'probe': info,
            'seekFractions': fractions, 'packetOffsets': offsets, 'contentBytes': SIZE, 'zipCrc32': CRC,
            'licence': 'CC BY 3.0 · Tears of Steel, Blender Foundation / Mango Open Movie Project',
            'publisher': 'https://mango.blender.org/download/', 'publisherMirrorsChecked': mirrors, 'sampleSha256': RANGES}
    (p / 'catalog.json').write_text(json.dumps([item], ensure_ascii=False, indent=2))
    print('MOV verified', digest, SIZE, offsets, flush=True)


def verify(results, core):
    rows = json.loads((results / 'format-results.json').read_text())
    assert len(rows) == 1
    row = rows[0]
    fixture = row['fixture']
    requests = [json.loads(s) for s in (results / f"{core}-{fixture['id']}-http.jsonl").read_text().splitlines()]
    high = [r for r in requests if r['status'] == 206 and r['rangeStart'] > 2**32 and r['bytes'] >= 65536]
    target = fixture['packetOffsets']['0.82']
    nearby = [r for r in high if abs(r['rangeStart']-target) <= 64*1024**2]
    checks = {'opened': row['openedWithin60Seconds'], 'activeCore': row.get('identity', {}).get('active') and row['identity'].get('engine') == core,
              'highRangeReadsNearTarget': len(nearby), 'over4GiBReads': len(high), 'originalBytes': fixture['contentBytes'],
              'sha256': fixture['sha256'], 'packetOffsets': fixture['packetOffsets'], 'allFourGestures':
              {(s['method'], s['fraction']) for s in row['seeks'] if s.get('rawTargetConfirmed') and s['output']['laterPos'] > s['output']['firstPos']}
              == {(m, f) for m in ('adb-swipe', 'held-preview') for f in (.82, .21)}}
    (results / 'large-offset-result.json').write_text(json.dumps(checks, indent=2))
    assert checks['opened'] and checks['activeCore'] and checks['allFourGestures'] and nearby


if __name__ == '__main__':
    if len(sys.argv) == 1:
        prepare()
    else:
        verify(Path(sys.argv[1]), sys.argv[2])
