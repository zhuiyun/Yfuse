#!/usr/bin/env python3
"""Exercise the actual Kotlin ASS patterns with ICU, not the host JVM regex engine."""
import ctypes as c
import ctypes.util
import json
import pathlib
import re
import sys

library = ctypes.util.find_library('icui18n')
if not library:
    raise SystemExit('ICU runtime is required')
icu = c.CDLL(library)
version = re.search(r'\.so\.(\d+)', library)
suffix = '_' + version.group(1) if version else ''

def bind(name, restype, argtypes):
    function = getattr(icu, name + suffix)
    function.restype = restype
    function.argtypes = argtypes
    return function

uchar = c.POINTER(c.c_uint16)
error = c.POINTER(c.c_int32)
open_regex = bind('uregex_open', c.c_void_p, [uchar, c.c_int32, c.c_uint32, c.c_void_p, error])
set_text = bind('uregex_setText', None, [c.c_void_p, uchar, c.c_int32, error])
replace_all = bind('uregex_replaceAll', c.c_int32, [c.c_void_p, uchar, c.c_int32, uchar, c.c_int32, error])
close_regex = bind('uregex_close', None, [c.c_void_p])

def utf16(text):
    data = text.encode('utf-16-le')
    return (c.c_uint16 * max(1, len(data) // 2)).from_buffer_copy(data or b'\x00\x00'), len(data) // 2

bad_pattern, bad_length = utf16(r'\{[^}]*}')
status = c.c_int32(0)
bad = open_regex(bad_pattern, bad_length, 0, None, c.byref(status))
if bad:
    close_regex(bad)
assert status.value > 0, 'ICU must reject the original unescaped closing brace'
print('Original crash reproduced with', library, 'status', status.value)

cases = [
    (r'{\b1}Hello{\b0}', 'Hello'),
    (r'{\i1}中文{\i0}', '中文'),
    ('plain text', 'plain text'),
    ('first\nsecond', 'first\nsecond'),
    (r'{\b1}text}', 'text}'),
    (r'{\b1unclosed', r'{\b1unclosed'),
    ('{}text', 'text'),
    (r'{\an8\fs24}top{\i1}italic', 'topitalic'),
]
root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else '.')
folder = root / 'composeApp/src/commonMain/kotlin/com/yfuse/core2/subtitle'
for filename in ('YSubtitle.kt', 'YTextSubtitleParser.kt'):
    source = (folder / filename).read_text()
    match = re.search(r'private val ASS_OVERRIDE = Regex\(("(?:\\.|[^"\\])*")\)', source)
    assert match, filename + ': missing production ASS_OVERRIDE'
    pattern = json.loads(match.group(1))
    pattern_buffer, pattern_length = utf16(pattern)
    status = c.c_int32(0)
    compiled = open_regex(pattern_buffer, pattern_length, 0, None, c.byref(status))
    assert compiled and status.value <= 0, (filename, pattern, status.value)
    try:
        for text, expected in cases:
            input_buffer, input_length = utf16(text)
            empty, _ = utf16('')
            output = (c.c_uint16 * 4096)()
            status = c.c_int32(0)
            set_text(compiled, input_buffer, input_length, c.byref(status))
            assert status.value <= 0, status.value
            length = replace_all(compiled, empty, 0, output, len(output), c.byref(status))
            assert status.value <= 0, status.value
            actual = c.string_at(output, length * 2).decode('utf-16-le')
            assert actual == expected, (filename, text, expected, actual)
    finally:
        close_regex(compiled)
    print(filename + ': ICU compilation and 8/8 replacement cases passed')
print('PASS: 2 production patterns, 16 replacement checks; no physical-device claim')
