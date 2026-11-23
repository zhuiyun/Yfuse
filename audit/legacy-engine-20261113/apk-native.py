"""Offline ELF dependency/symbol inventory for an existing delivery APK. No extraction or execution."""
import argparse
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path


def inspect_elf(data):
    if data[:6] != b'\x7fELF\x02\x01':
        raise ValueError('Expected a 64-bit little-endian ELF')
    kind, offset = struct.unpack_from('<H', data, 18)[0], struct.unpack_from('<Q', data, 40)[0]
    if kind != 183:
        raise ValueError('Expected AArch64')
    stride, count = struct.unpack_from('<HH', data, 58)
    sections = [struct.unpack_from('<IIQQQQIIQQ', data, offset + stride * i) for i in range(count)]
    needed, markers = set(), set()
    for _, typ, _, _, start, size, link, _, _, step in sections:
        if typ not in (6, 11) or not step:
            continue
        strings = sections[link]
        for off in range(start, start + size, step):
            if typ == 6:
                tag, idx = struct.unpack_from('<qQ', data, off)
                if tag != 1:
                    continue
            else:
                idx, = struct.unpack_from('<I', data, off)
            pos = strings[4] + idx
            name = data[pos:data.index(b'\0', pos, strings[4] + strings[5])].decode('ascii', 'replace')
            if typ == 6:
                needed.add(name)
            elif re.search(r'^(?:mpv_|mdk_)|mediadevkit|Java_(?:dev_(?:jdtech|yfuse)_mpv|com_google_android_exoplayer|androidx_media3_exoplayer)', name, re.I):
                markers.add(name)
    return {'needed': sorted(needed), 'oldEngineApiSymbols': sorted(markers)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('out', type=Path)
    args = parser.parse_args()
    with zipfile.ZipFile(args.apk) as apk:
        libraries = {name: inspect_elf(apk.read(name)) for name in apk.namelist()
                     if name.startswith('lib/arm64-v8a/') and name.endswith('.so')}
    if not libraries:
        raise ValueError('No arm64 libraries in APK')
    entries = {'apkSha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(), 'libraries': libraries}
    args.out.write_text(json.dumps(entries, indent=2) + '\n', encoding='utf-8')
    old_library = re.compile(r'^(libmpv|libplayer|libmdk|libyfuse-mdk-jni)(?:\.so(?:\..*)?)$', re.I)
    old = [key for key, value in libraries.items() if old_library.match(Path(key).name)
           or any(old_library.match(n) for n in value['needed']) or value['oldEngineApiSymbols']]
    print(f'Host ARM64 libraries: {len(libraries)}; old engine names, dependencies, or exported/imported API symbols: {len(old)}')
    raise SystemExit(bool(old))
