#!/bin/bash
# Script to bundle GAMA ARM product and build APK

set -e

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_PROJECT="$REPO_ROOT/gama.android"
GAMA_ARM_PRODUCT="$REPO_ROOT/gama.product/target/products/gama.ui.application.product/linux/gtk/aarch64"
ASSETS_DIR="$ANDROID_PROJECT/app/src/main/assets"

mkdir -p "$ASSETS_DIR"
# Clean up old extension versions
rm -f "$ASSETS_DIR"/*.tar
rm -f "$ASSETS_DIR"/*.tar.gz

if [ ! -f "$GAMA_ARM_PRODUCT/Gama" ]; then
    echo "Error: GAMA ARM binary not found at $GAMA_ARM_PRODUCT/Gama"
    echo "Please run 'bash travis/build.sh' first (with aarch64 target)."
    exit 1
fi

echo "Packaging GAMA for Android..."
# We use .bundle to prevent Android AAPT from uncompressing the file
tar --exclude='p2' -czf "$ASSETS_DIR/gama_aarch64.bundle" -C "$GAMA_ARM_PRODUCT" .

# 1. Try UserLAnd Patched Static binary (Specialized for modern Android)
echo "Downloading Patched PRoot for aarch64..."
# This is the verified path for the patched binary
STATIC_URL="https://github.com/CypherpunkArmory/UserLAnd-Assets-PRoot/raw/master/all/proot"
if ! curl -Lf "$STATIC_URL" -o "$ASSETS_DIR/proot"; then
    echo "  Primary URL failed. Trying mirror..."
    curl -Lf "https://github.com/proot-me/proot-static-build/raw/master/static/proot-arm64" -o "$ASSETS_DIR/proot"
fi
echo "Downloading minimal Ubuntu Rootfs (aarch64)..."
if [ ! -s "$ASSETS_DIR/ubuntu_rootfs.bundle" ]; then
    curl -Lf "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz" \
        -o "$ASSETS_DIR/ubuntu_rootfs.bundle"
fi

# Basic validation
PROOT_SIZE=$(wc -c < "$ASSETS_DIR/proot" 2>/dev/null || echo 0)
if [ "$PROOT_SIZE" -lt 100000 ]; then
    echo "--------------------------------------------------------"
    echo "CRITICAL ERROR: PRoot binary is missing or too small ($PROOT_SIZE bytes)."
    echo "Please manually download a Termux-compatible proot for aarch64."
    echo "From Termux app: 'pkg install proot' then copy the binary."
    echo "--------------------------------------------------------"
    exit 1
fi

echo "PRoot size: $(wc -c < "$ASSETS_DIR/proot") bytes"
echo "GAMA, PRoot, and Ubuntu Rootfs packaged."
echo "Ready to build the APK."
echo "Use 'cd gama.android && ./gradlew assembleDebug' if you have Android SDK installed."
