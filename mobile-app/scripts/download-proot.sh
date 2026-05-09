#!/bin/bash
set -euo pipefail

# Download or build PRoot for Android ARM64
# Provides a pre-compiled PRoot binary for ARM64 Android

OUTPUT_DIR="${1:-./build/proot}"
mkdir -p "${OUTPUT_DIR}"

echo "[proot] Setting up PRoot ARM64 binary for Android"

# Check if user has a pre-built binary
if [ -f "${OUTPUT_DIR}/proot_arm64" ]; then
    echo "[proot] Binary already exists at ${OUTPUT_DIR}/proot_arm64"
    file "${OUTPUT_DIR}/proot_arm64"
    exit 0
fi

# Attempt to download a pre-built PRoot binary for Android ARM64
# Multiple fallback sources

PRoot_URLS=(
    "https://github.com/termux/proot/releases/latest/download/proot_arm64"
    "https://raw.githubusercontent.com/termux/proot/master/proot_arm64"
)

for url in "${PRoot_URLS[@]}"; do
    echo "[proot] Trying: $url"
    if curl -fSL -o "${OUTPUT_DIR}/proot_arm64" "$url" 2>/dev/null; then
        chmod +x "${OUTPUT_DIR}/proot_arm64"
        echo "[proot] Downloaded successfully"
        file "${OUTPUT_DIR}/proot_arm64"
        echo "[proot] Binary at: ${OUTPUT_DIR}/proot_arm64"
        exit 0
    fi
done

echo "[proot] Could not download pre-built PRoot binary."
echo "[proot] Building from source with Android NDK..."

# Fall back to building from source
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"${SCRIPT_DIR}/build-proot.sh" "${OUTPUT_DIR}"

echo ""
echo "[proot] PRoot binary ready. Copy to Android app:"
echo "  cp ${OUTPUT_DIR}/proot_arm64 android/app/src/main/res/raw/"
