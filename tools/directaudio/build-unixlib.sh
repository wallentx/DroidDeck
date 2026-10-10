#!/usr/bin/env bash
# Build one glibc DirectAudio unixlib set against a pinned Wine mmdevapi ABI.
# The caller supplies separately checked-out source and Wine trees so each ABI
# leg is isolated and this helper can verify the source revision before edits.
set -euo pipefail

if [[ $# -ne 9 ]]; then
  echo "usage: $0 SOURCE WINE OUT LABEL ABI WINE_REPO WINE_REF UNIXLIB_SHA EXTRADEFS" >&2
  exit 64
fi

source_tree=$1
wine_tree=$2
out=$3
label=$4
abi=$5
wine_repo=$6
wine_ref=$7
unixlib_sha=$8
extradefs=$9
root=$(cd "$(dirname "$0")/../.." && pwd)
# shellcheck source=source.env
# shellcheck disable=SC1091
. "$root/tools/directaudio/source.env"

test "$(git -C "$source_tree" rev-parse HEAD)" = "$DIRECTAUDIO_SOURCE_REVISION"
test "$(git -C "$wine_tree" rev-parse HEAD)" = "$wine_ref"
case "$abi" in wine11|systhread) ;; *) echo "unknown DirectAudio ABI: $abi" >&2; exit 64 ;; esac
case "$label" in linux-wine11|linux-wine11-systhread) ;; *) echo "unknown output label: $label" >&2; exit 64 ;; esac

driver="$wine_tree/dlls/winedirectaudio.drv"
mkdir -p "$driver"
cp -v "$source_tree/directaudio.c" "$source_tree/da_mix.h" \
  "$source_tree/da_relay_proto.h" "$source_tree/da_aaudio_compat.h" "$driver/"
cp -v "$source_tree/Makefile.relay.in" "$driver/Makefile.in"
if [[ -n "$extradefs" ]]; then
  sed -i "s|^EXTRADEFS = -DDA_RELAY$|EXTRADEFS = -DDA_RELAY $extradefs|" "$driver/Makefile.in"
fi
grep -q '^EXTRADEFS' "$driver/Makefile.in"

# These private call tables are not versioned. A wrong table can compile but
# produces silence, so reject it before configuring the driver.
grep -q 'WINE_CONFIG_MAKEFILE(dlls/winedirectaudio.drv)' "$wine_tree/configure.ac" || \
  sed -i 's|^WINE_CONFIG_MAKEFILE(dlls/winepulse.drv)$|WINE_CONFIG_MAKEFILE(dlls/winedirectaudio.drv)\nWINE_CONFIG_MAKEFILE(dlls/winepulse.drv)|' "$wine_tree/configure.ac"
actual_sha=$(sha256sum "$wine_tree/dlls/mmdevapi/unixlib.h" | cut -d' ' -f1)
case "$abi" in
  wine11)
    test "$actual_sha" = "$unixlib_sha"
    grep -q 'struct timer_loop_params' "$wine_tree/dlls/mmdevapi/unixlib.h" ;;
  systhread)
    test "${actual_sha:0:16}" = "$unixlib_sha"
    grep -q 'create_unix_thread' "$wine_tree/dlls/mmdevapi/unixlib.h"
    ! grep -q 'struct timer_loop_params' "$wine_tree/dlls/mmdevapi/unixlib.h" ;;
esac

# Valve-derived Wine trees omit generated Vulkan and protocol headers. Generate
# them before autoreconf, as Proton's own build does.
(
  cd "$wine_tree"
  python3 dlls/winevulkan/make_vulkan -x vk.xml -X video.xml
  tools/make_specfiles || true
  tools/make_requests
  test -f include/wine/vulkan.h
  autoreconf -f

  # Wine's host tools must be made only after the generated headers exist.
  # A x86_64 configure with --enable-win64 avoids an unnecessary i386 SDK.
  if [[ ! -x wine-tools/tools/winebuild ]]; then
    mkdir -p wine-tools
    (
      cd wine-tools
      ../configure --enable-win64 --without-x --without-freetype --without-gstreamer \
        --without-vulkan --without-wayland --without-mingw --disable-tests
      make -j"$(nproc)" __tooldeps__ nls/all
    )
  fi

  export PATH="${LLVM_MINGW_BIN:?LLVM_MINGW_BIN is required}:$PATH"
  export CCACHE_DIR="${CCACHE_DIR:-$HOME/.ccache}"
  export CC="ccache aarch64-linux-gnu-gcc"
  export CFLAGS='-g0 -O2'
  export CROSSCFLAGS='-g0 -O2'
  ./configure \
    --host=aarch64-linux-gnu --build=x86_64-linux-gnu \
    --with-wine-tools=wine-tools --enable-archs=arm64ec,aarch64,i386 \
    --with-mingw=clang --enable-win64 --disable-win16 --disable-tests \
    --without-alsa --without-capi --without-coreaudio --without-cups \
    --without-dbus --without-ffmpeg --without-fontconfig --without-freetype \
    --without-gcrypt --without-gettext --with-gettextpo=no --without-gphoto \
    --without-gnutls --without-gssapi --without-gstreamer --without-inotify \
    --without-krb5 --without-netapi --without-opencl --without-opengl \
    --without-oss --without-pcap --without-pcsclite --without-pulse \
    --without-sane --without-sdl --without-udev --without-unwind \
    --without-usb --without-v4l2 --without-vulkan --without-wayland \
    --without-xcomposite --without-xcursor --without-xfixes --without-xinerama \
    --without-xinput --without-xinput2 --without-xrandr --without-xrender \
    --without-xshape --without-xshm --without-xxf86vm --without-x

  mapfile -t targets < <(grep -oE 'dlls/winedirectaudio\.drv/([A-Za-z0-9_-]+-windows/winedirectaudio\.drv|winedirectaudio\.so)' Makefile | sort -u)
  printf 'building %s targets:\n%s\n' "$label" "${targets[*]}"
  test "${#targets[@]}" -ge 2
  make -j"$(nproc)" "${targets[@]}"
)

mkdir -p "$out/aarch64-windows" "$out/i386-windows" "$out/aarch64-unix"
pe64=$(find "$wine_tree" -type f -path '*/aarch64-windows/winedirectaudio.drv' -print -quit)
pe32=$(find "$wine_tree" -type f -path '*/i386-windows/winedirectaudio.drv' -print -quit)
so=$(find "$wine_tree" -type f -name winedirectaudio.so -print -quit)
test -n "$pe64" && test -n "$pe32" && test -n "$so"
cp "$pe64" "$out/aarch64-windows/winedirectaudio.drv"
cp "$pe32" "$out/i386-windows/winedirectaudio.drv"
cp "$so" "$out/aarch64-unix/winedirectaudio.so"
"${LLVM_MINGW_BIN}/llvm-strip" --strip-all "$out/aarch64-windows/winedirectaudio.drv"
"${LLVM_MINGW_BIN}/llvm-strip" --strip-all "$out/i386-windows/winedirectaudio.drv"
aarch64-linux-gnu-strip --strip-unneeded "$out/aarch64-unix/winedirectaudio.so"

aarch64-linux-gnu-readelf -d "$out/aarch64-unix/winedirectaudio.so" | grep -q 'libc\.so\.6'
if aarch64-linux-gnu-readelf -d "$out/aarch64-unix/winedirectaudio.so" | grep -qi aaudio; then
  echo "game unixlib must not link AAudio" >&2
  exit 1
fi
if aarch64-linux-gnu-nm -D -u "$out/aarch64-unix/winedirectaudio.so" | grep -qi aaudio; then
  echo "game unixlib must not import AAudio" >&2
  exit 1
fi
if grep -aq libaaudio "$out/aarch64-unix/winedirectaudio.so"; then
  echo "game unixlib embeds an AAudio library reference" >&2
  exit 1
fi
if [[ "$abi" = systhread ]]; then
  aarch64-linux-gnu-nm -D -u "$out/aarch64-unix/winedirectaudio.so" | grep -q PsCreateSystemThread
else
  if aarch64-linux-gnu-nm -D -u "$out/aarch64-unix/winedirectaudio.so" | grep -q PsCreateSystemThread; then
    echo "classic Wine ABI must not import PsCreateSystemThread" >&2
    exit 1
  fi
fi

cp "$source_tree/COPYING" "$out/LICENSE"
[[ -f "$source_tree/NOTICE" ]] && cp "$source_tree/NOTICE" "$out/NOTICE"
[[ -f "$source_tree/docs/linux-relay/INSTALL.md" ]] && cp "$source_tree/docs/linux-relay/INSTALL.md" "$out/README.md"
printf 'directaudio %s downstream game unixlib build\nabi %s\nsource %s\nwine %s@%s\nunixlib.h sha256 %s\n' \
  "$label" "$abi" "$DIRECTAUDIO_SOURCE_REVISION" "$wine_repo" "$wine_ref" "$actual_sha" > "$out/version.txt"
downstream_commit=${GITHUB_SHA:-$(git -C "$root" rev-parse HEAD)}
patch_sha=$(cd "$root" && sha256sum tools/directaudio/patches/*.patch | sha256sum | cut -d' ' -f1)
printf 'downstream commit %s\npatch-series sha256 %s\n' "$downstream_commit" "$patch_sha" >> "$out/version.txt"
printf 'source repository: %s\nsource revision: %s\ndownstream commit: %s\npatch-series sha256: %s\npatch series: tools/directaudio/patches/\n' \
  "$DIRECTAUDIO_SOURCE_URL" "$DIRECTAUDIO_SOURCE_REVISION" "$downstream_commit" "$patch_sha" > "$out/source-provenance.txt"
