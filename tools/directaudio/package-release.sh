#!/usr/bin/env bash
# Assemble the four asset names fetch.sh expects. Only game unixlib zips are
# rebuilt; the Android relay and PulseAudio sink stay byte-identical upstream.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 BUILT_UNIXLIBS RELEASE_DIR" >&2
  exit 64
fi

built=$(cd "$1" && pwd)
mkdir -p "$2"
release=$(cd "$2" && pwd)
root=$(cd "$(dirname "$0")/../.." && pwd)
# shellcheck source=source.env
# shellcheck disable=SC1091
. "$root/tools/directaudio/source.env"

for label in linux-wine11 linux-wine11-systhread; do
  input="$built/$label"
  for file in aarch64-unix/winedirectaudio.so aarch64-windows/winedirectaudio.drv \
              i386-windows/winedirectaudio.drv version.txt LICENSE source-provenance.txt; do
    test -f "$input/$file" || { echo "$label: missing $file" >&2; exit 1; }
  done
  (cd "$input" && zip -X -qr "$release/directaudio-${label}.zip" .)
done

fetch_upstream() {
  local asset=$1 expected=$2
  curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
    --output "$release/$asset" \
    "https://github.com/${DIRECTAUDIO_UPSTREAM_RELEASE_REPOSITORY}/releases/download/${DIRECTAUDIO_UPSTREAM_RELEASE_TAG}/$asset"
  printf '%s  %s\n' "$expected" "$release/$asset" | sha256sum --check --status - || {
    echo "$asset: upstream checksum mismatch" >&2
    exit 1
  }
}
fetch_upstream directaudio-linux-relay.zip "$DIRECTAUDIO_UPSTREAM_RELAY_SHA256"
fetch_upstream directaudio-linux-sink.zip "$DIRECTAUDIO_UPSTREAM_SINK_SHA256"

(
  cd "$release"
  sha256sum directaudio-linux-wine11.zip directaudio-linux-wine11-systhread.zip \
    directaudio-linux-relay.zip directaudio-linux-sink.zip > SHA256SUMS.txt
)
