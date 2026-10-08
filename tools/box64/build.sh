#!/usr/bin/env bash
# Experimental standalone component; does not change APK contents or game selection.
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
. "$root/tools/box64/source.env"
mkdir -p "${1:?output directory required}"
out=$(cd "$1" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
git init -q "$work/source"
git -C "$work/source" fetch --depth=1 https://github.com/ptitSeb/box64.git "$BOX64_REVISION"
git -C "$work/source" checkout -q --detach FETCH_HEAD
test "$(git -C "$work/source" rev-parse HEAD)" = "$BOX64_REVISION"
git -C "$work/source" apply --check "$root/tools/box64/patches/0001-support-basic-int80-file-syscalls.patch"
git -C "$work/source" apply "$root/tools/box64/patches/0001-support-basic-int80-file-syscalls.patch"
cmake -S "$work/source" -B "$work/build" -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DARM64=1 -DARM_DYNAREC=1 -DWINLATOR_GLIBC=1 -DBAD_SIGNAL=1 -DBOX32=0 -DHAVE_TRACE=0 -DCI=1
cmake --build "$work/build" --parallel 4
cp "$work/build/box64" "$out/box64"
cp "$work/source/LICENSE" "$out/LICENSE"
printf '%s\n' "$BOX64_REVISION" > "$out/source-commit"
cp "$root/tools/box64/patches/0001-support-basic-int80-file-syscalls.patch" "$out/downstream.patch"
tar --exclude-vcs --zstd -cf "$out/source.tar.zst" -C "$work/source" .
(cd "$out" && sha256sum box64 source.tar.zst downstream.patch > SHA256SUMS)
