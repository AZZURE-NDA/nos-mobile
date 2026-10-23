#!/usr/bin/env bash
# Builds the Ubuntu runtime as linuxfs-ubuntu.tar.zst and prints its sha256 and size.
set -euo pipefail
cd "$(dirname "$0")"
out=${1:-../../build/ubuntu-rootfs}
mkdir -p "$out"
tag=nos-ubuntu-rootfs:latest
args=()
for v in RUNTIME_VERSION DESKTOP_SHELL; do [ -n "${!v:-}" ] && args+=(--build-arg "$v=${!v}"); done
docker build --platform linux/arm64 "${args[@]}" -t "$tag" .
cid=$(docker create --platform linux/arm64 "$tag" /bin/true)
trap 'docker rm -f "$cid" >/dev/null' EXIT
docker export "$cid" | zstd -T0 -19 -q -o "$out/linuxfs-ubuntu.tar.zst" -f
sha=$(sha256sum "$out/linuxfs-ubuntu.tar.zst" | cut -d' ' -f1)
size=$(stat -c %s "$out/linuxfs-ubuntu.tar.zst")
printf '{\n  "file": "linuxfs-ubuntu.tar.zst",\n  "sha256": "%s",\n  "size": %s\n}\n' "$sha" "$size" | tee "$out/linuxfs-ubuntu.json"
