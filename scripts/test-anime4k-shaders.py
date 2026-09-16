#!/usr/bin/env python3
"""Compile the vendored Anime4K GLES passes on an actual EGL driver (Mesa works headlessly).
Run with: EGL_PLATFORM=surfaceless python3 scripts/test-anime4k-shaders.py
No Android device is emulated; MediaCodec/Surface lifecycle still needs device verification.
"""
import ctypes as C
import os
from pathlib import Path

os.environ.setdefault('EGL_PLATFORM', 'surfaceless')
egl = C.CDLL('libEGL.so.1')
def api(name, restype, *args):
    fn = getattr(egl, name)
    fn.restype, fn.argtypes = restype, args
    return fn
ptr, integer = C.c_void_p, C.c_int
get_display = api('eglGetDisplay', ptr, ptr)
initialize = api('eglInitialize', integer, ptr, ptr, ptr)
choose = api('eglChooseConfig', integer, ptr, ptr, ptr, integer, ptr)
create_context = api('eglCreateContext', ptr, ptr, ptr, ptr, ptr)
create_surface = api('eglCreatePbufferSurface', ptr, ptr, ptr, ptr)
make_current = api('eglMakeCurrent', integer, ptr, ptr, ptr, ptr)
get_proc = api('eglGetProcAddress', ptr, C.c_char_p)

def gl(name, restype, *args):
    addr = get_proc(name.encode())
    assert addr, name
    return C.CFUNCTYPE(restype, *args)(addr)

def ints(*xs): return (integer * len(xs))(*xs)
display = get_display(None)
assert initialize(display, None, None), 'EGL initialization failed'
config, count = ptr(), integer()
assert choose(display, ints(0x3040, 0x40, 0x3033, 1, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3038), C.byref(config), 1, C.byref(count))
assert count.value > 0
context = create_context(display, config, None, ints(0x3098, 3, 0x3038))
surface = create_surface(display, config, ints(0x3057, 16, 0x3056, 16, 0x3038))
assert make_current(display, surface, surface, context)
create_shader = gl('glCreateShader', C.c_uint, C.c_uint)
source_shader = gl('glShaderSource', None, C.c_uint, integer, ptr, ptr)
compile_shader = gl('glCompileShader', None, C.c_uint)
get_shader = gl('glGetShaderiv', None, C.c_uint, C.c_uint, ptr)
log_shader = gl('glGetShaderInfoLog', None, C.c_uint, integer, ptr, ptr)
create_program = gl('glCreateProgram', C.c_uint)
attach = gl('glAttachShader', None, C.c_uint, C.c_uint)
link = gl('glLinkProgram', None, C.c_uint)
get_program = gl('glGetProgramiv', None, C.c_uint, C.c_uint, ptr)

def shader(kind, text):
    result = create_shader(kind)
    source = C.c_char_p(text.encode())
    source_shader(result, 1, C.byref(source), None)
    compile_shader(result)
    ok = integer()
    get_shader(result, 0x8B81, C.byref(ok))
    if not ok.value:
        log = C.create_string_buffer(8192)
        log_shader(result, len(log), None, log)
        raise AssertionError(log.value.decode())
    return result

vertex = shader(0x8B31, '''#version 300 es
in vec2 position;
out vec2 uv;
void main() { uv = position * 0.5 + 0.5; gl_Position = vec4(position, 0., 1.); }
''')
root = Path(__file__).resolve().parents[1]
asset = root / 'composeApp/src/androidMain/assets/anime4k/Anime4K_Upscale_Original_x2.glsl'
blocks = asset.read_text().split('//!DESC ')[1:]
assert len(blocks) == 6
available = {'HOOKED'}
for index, block in enumerate(blocks):
    lines = block.splitlines()[1:]
    bindings = [line.removeprefix('//!BIND ').strip() for line in lines if line.startswith('//!BIND ')]
    assert set(bindings) <= available, (index, bindings, available)
    declarations = '\n'.join(f'uniform sampler2D {name}_sampler;\n#define {name}_tex(p) texture({name}_sampler, p)' for name in bindings)
    body = '\n'.join(line for line in lines if not line.startswith('//!'))
    fragment = shader(0x8B30, f'''#version 300 es
precision highp float;
in vec2 uv;
out vec4 color;
uniform vec2 HOOKED_pt;
#define HOOKED_pos uv
{declarations}
{body}
void main() {{ color = hook(); }}
''')
    program = create_program()
    attach(program, vertex)
    attach(program, fragment)
    link(program)
    ok = integer()
    get_program(program, 0x8B82, C.byref(ok))
    assert ok.value, f'pass {index} link failed'
    for line in lines:
        if line.startswith('//!SAVE '): available.add(line.removeprefix('//!SAVE ').strip())
# The edge kernels contain negative gradients: UNORM buffers would silently corrupt them.
gen_tex = gl('glGenTextures', None, integer, ptr)
bind_tex = gl('glBindTexture', None, C.c_uint, C.c_uint)
tex_image = gl('glTexImage2D', None, C.c_uint, integer, integer, integer, integer, integer, C.c_uint, C.c_uint, ptr)
gen_fbo = gl('glGenFramebuffers', None, integer, ptr)
bind_fbo = gl('glBindFramebuffer', None, C.c_uint, C.c_uint)
attach_tex = gl('glFramebufferTexture2D', None, C.c_uint, C.c_uint, C.c_uint, C.c_uint, integer)
fbo_status = gl('glCheckFramebufferStatus', C.c_uint, C.c_uint)
clear_color = gl('glClearColor', None, C.c_float, C.c_float, C.c_float, C.c_float)
clear = gl('glClear', None, C.c_uint)
read = gl('glReadPixels', None, integer, integer, integer, integer, C.c_uint, C.c_uint, ptr)
texture, fbo = C.c_uint(), C.c_uint()
gen_tex(1, C.byref(texture)); bind_tex(0x0DE1, texture)
tex_image(0x0DE1, 0, 0x822F, 16, 16, 0, 0x8227, 0x140B, None) # RG16F
gen_fbo(1, C.byref(fbo)); bind_fbo(0x8D40, fbo)
attach_tex(0x8D40, 0x8CE0, 0x0DE1, texture, 0)
assert fbo_status(0x8D40) == 0x8CD5
clear_color(-0.5, 2.0, 0, 1); clear(0x4000)
pixel = (C.c_float * 4)()
read(0, 0, 1, 1, 0x1908, 0x1406, pixel)
assert abs(pixel[0] + 0.5) < .001 and abs(pixel[1] - 2.0) < .001, list(pixel)
assert gl('glGetError', C.c_uint)() == 0
print('PASS: 6 Anime4K GLES shaders compile/link; pass dependencies resolve; RG16F preserves signed gradients')
api('eglMakeCurrent', integer, ptr, ptr, ptr, ptr)(display, None, None, None)
api('eglDestroySurface', integer, ptr, ptr)(display, surface)
api('eglDestroyContext', integer, ptr, ptr)(display, context)
api('eglTerminate', integer, ptr)(display)
