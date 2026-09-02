#!/usr/bin/env bash
#
# Prepare Android sandbox assets:
#   1. Download Alpine Linux aarch64 minirootfs
#   2. Download PRoot aarch64 static binary from Termux packages
#   3. Place both into src/android/app/src/main/assets/
#
# Usage: ./scripts/prepare_android_sandbox.sh
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$PROJECT_ROOT/src/android/app/src/main/assets"

ALPINE_VERSION="3.21"
ALPINE_RELEASE="3.21.3"
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION}/releases/aarch64/alpine-minirootfs-${ALPINE_RELEASE}-aarch64.tar.gz"

# PRoot is compiled from the pinned deps/proot submodule by
# deps/build_proot.sh after this rootfs preparation step.

mkdir -p "$ASSETS_DIR"

ROOTFS_FILE="$ASSETS_DIR/alpine-minirootfs.tar.gz"
PROOT_FILE="$ASSETS_DIR/proot-aarch64"

# --- Alpine rootfs ---
if [ -f "$ROOTFS_FILE" ]; then
    echo "✓ Alpine rootfs already exists: $ROOTFS_FILE"
else
    echo "Downloading Alpine Linux ${ALPINE_RELEASE} aarch64 minirootfs..."
    curl -fSL -o "$ROOTFS_FILE" "$ALPINE_URL"
    echo "✓ Downloaded: $ROOTFS_FILE ($(du -h "$ROOTFS_FILE" | cut -f1))"
fi

# --- PRoot binary ---
# The former Termux package URL has been removed upstream. This project already
# pins the compatible source in deps/proot, and deps/build_proot.sh installs the
# binary and loaders into the Android app assets.
if [ -f "$PROOT_FILE" ]; then
    echo "✓ PRoot binary already exists: $PROOT_FILE"
else
    echo "PRoot binary will be built from deps/proot by deps/build_proot.sh"
fi

echo ""
echo "Assets ready in: $ASSETS_DIR"
ls -lh "$ASSETS_DIR"
