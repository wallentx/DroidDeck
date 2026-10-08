#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "${1:?output directory required}"
for test in control int80; do
  clang --target=x86_64-linux-gnu -fuse-ld=lld -nostdlib -static -Wl,-no-pie,--image-base=0x10000000 \
    "$here/$test.S" -o "$1/$test"
done
