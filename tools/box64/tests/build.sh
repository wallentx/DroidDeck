#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "${1:?output directory required}"
for test in control int80; do
  clang --target=x86_64-linux-gnu -fuse-ld=lld -nostdlib -static -Wl,-no-pie,--image-base=0x10000000 \
    "$here/$test.S" -o "$1/$test"
done

clang --target=x86_64-linux-gnu -fuse-ld=lld -shared -fPIC -nostdlib \
  "$here/module.c" -o "$1/module.so"
"${X86_CC:-gcc}" -O2 -no-pie -Wl,-Ttext-segment=0x10000000 \
  "$here/fd-load.c" -ldl -o "$1/fd-load"
