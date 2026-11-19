#!/usr/bin/env bash
# Packages the Roku channel for sideloading or the developer dashboard.
# A channel zip must carry the manifest at its root, so we zip the contents of
# app/ rather than the directory itself.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app="$root/app"
out="$root/build/moviebox-roku.zip"

if [[ ! -f "$app/manifest" ]]; then
  echo "error: $app/manifest is missing" >&2
  exit 1
fi

mkdir -p "$root/build"
rm -f "$out"
# -x keeps editor and Finder droppings out; a stray __MACOSX or .DS_Store entry
# is enough for the device to reject the package.
(cd "$app" && zip -qr "$out" . -x '.*' -x '*/.*')

printf 'packaged %s (%s)\n' "$out" "$(du -h "$out" | cut -f1)"
