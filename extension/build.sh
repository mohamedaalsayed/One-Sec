#!/usr/bin/env bash
# Builds the extension for Chromium-based browsers and for Firefox:
#   dist/chromium/  dist/firefox/      unpacked, for loading in developer mode
#   dist/one-sec-chromium.zip  dist/one-sec-firefox.zip   for the stores
set -euo pipefail
cd "$(dirname "$0")"

rm -rf dist
for target in chromium firefox; do
  mkdir -p "dist/$target"
  cp -r src/. "dist/$target/"
  cp "manifests/$target.json" "dist/$target/manifest.json"
  (cd "dist/$target" && zip -qr "../one-sec-$target.zip" .)
done
echo "Built dist/chromium, dist/firefox and their zips"
