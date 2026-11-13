#!/usr/bin/env bash
# Cross-compile the codec, subtitle and disc dependencies linked into YCore on arm64.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
workspace="$root/.native-build/ycore-source"
build="$workspace/buildscripts"
deps="$build/deps"
prefix="$build/prefix/arm64-v8a"
artifacts="$root/.native-build/artifacts"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Android SDK required}}"
ndk="$sdk/ndk/29.0.14206865"
if [[ ! -d "$ndk" ]]; then "$sdk/cmdline-tools/latest/bin/sdkmanager" 'ndk;29.0.14206865'; fi
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64"
test -x "$toolchain/bin/aarch64-linux-android26-clang"
mkdir -p "$prefix/lib/pkgconfig" "$build/sdk/android-sdk-linux/ndk" "$artifacts"
ln -sfn "$ndk" "$build/sdk/android-sdk-linux/ndk/29.0.14206865"
python3 "$root/scripts/fetch-ycore-source.py" "$deps"

export PATH="$toolchain/bin:$PATH"
export CC=aarch64-linux-android26-clang CXX=aarch64-linux-android26-clang++
export AR=llvm-ar RANLIB=llvm-ranlib NM=llvm-nm
export CFLAGS="-fPIC" CXXFLAGS="-fPIC" LDFLAGS="-Wl,-z,max-page-size=16384"
export PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" PKG_CONFIG_PATH=
jobs="${YCORE_BUILD_JOBS:-$(nproc)}"

cat > "$prefix/cross.ini" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = 'llvm-ar'
strip = 'llvm-strip'
pkg-config = 'pkg-config'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
[built-in options]
buildtype = 'release'
default_library = 'static'
wrap_mode = 'nodownload'
EOF

meson_library() (
    cd "$deps/$1"
    shift
    meson setup out --prefix="$prefix" --libdir=lib --cross-file="$prefix/cross.ini" "$@"
    meson compile -C out -j "$jobs"
    meson install -C out
)
autotools_library() (
    cd "$deps/$1"
    shift
    [[ -f configure ]] || NOCONFIGURE=1 ./autogen.sh
    mkdir -p out && cd out
    ../configure --host=aarch64-linux-android --prefix="$prefix" --with-pic --enable-static --disable-shared "$@"
    make -j"$jobs" && make install
)
(
    cd "$deps/mbedtls"
    make -j"$jobs" no_test
    make DESTDIR="$prefix" install
)
meson_library dav1d -Denable_tests=false -Db_lto=true -Dstack_alignment=16
meson_library libxml2 -Dminimum=true -D{push,reader,sax1,iso8859x,pattern}=enabled
meson_library freetype -Dharfbuzz=disabled
meson_library fribidi -Dtests=false -Ddocs=false
meson_library harfbuzz -Dtests=disabled -Ddocs=disabled
meson_library fontconfig -D{tests,doc,tools,nls}=disabled -Dxml-backend=libxml2
autotools_library libunibreak
autotools_library libass --enable-libunibreak --enable-fontconfig
meson_library libbluray -Dbdj_jar=disabled -Denable_docs=false -Denable_tools=false \
    -Denable_devtools=false -Denable_examples=false -Dfontconfig=disabled
(
    cd "$deps/ffmpeg"
    mkdir -p out && cd out
    ../configure --target-os=android --enable-cross-compile --cross-prefix=aarch64-linux-android- \
        --arch=aarch64 --cpu=armv8-a --cc="$CC" --cxx="$CXX" --ar="$AR" --ranlib="$RANLIB" --nm="$NM" \
        --strip=llvm-strip --prefix="$prefix" --pkg-config=pkg-config \
        --extra-cflags="-I$prefix/include" --extra-ldflags="-L$prefix/lib $LDFLAGS" \
        --enable-{jni,mediacodec,mbedtls,libdav1d,libxml2,gpl,version3} --disable-vulkan \
        --disable-static --enable-shared --disable-{stripping,doc,programs,muxers,encoders,devices,filters} \
        --disable-v4l2-m2m
    make -j"$jobs" && make install
)
cp "$toolchain/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$prefix/lib/"
{
    printf 'ffmpeg=%s\n' "$(git -C "$deps/ffmpeg" rev-parse HEAD)"
    printf 'libass=0.17.4\nlibbluray=1.4.1\nandroid-api=26\nndk=29.0.14206865\n'
} > "$artifacts/NATIVE-SOURCES.txt"
