#!/bin/bash
set -euo pipefail

# Master setup script for GAMA Mobile development
# Run this once to set up the entire development environment

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GAMA_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "╔═══════════════════════════════════════════════════════════╗"
echo "║        GAMA Mobile - Development Environment Setup       ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo ""

# ─── Check prerequisites ───────────────────────────────────────────────
echo "=== Checking Prerequisites ==="

check_command() {
    if command -v "$1" &> /dev/null; then
        echo "  ✓ $1: $($1 --version 2>&1 | head -1)"
    else
        echo "  ✗ $1: NOT FOUND"
        return 1
    fi
}

check_command node
check_command npm
check_command java || echo "  (OpenJDK 17+ recommended for backend development)"
check_command docker || echo "  (Docker needed for rootfs cross-build)"

if ! command -v npx &> /dev/null; then
  echo "  ✗ npx: NOT FOUND"
  echo "  Please install Node.js 18+"
  exit 1
fi

echo ""

# ─── Step 1: Check GAMA headless product ──────────────────────────────
echo "=== Step 1: GAMA Headless Product ==="
GAMA_PRODUCT_DIR="${GAMA_ROOT}/gama.product/target/products/gama.headless.product/linux/gtk/aarch64"
GAMA_ARCHIVE="${GAMA_ROOT}/gama.product/target/products/gama.headless-linux.gtk.aarch64.tar.gz"

if [ -d "$GAMA_PRODUCT_DIR" ]; then
    PLUGIN_COUNT=$(ls "$GAMA_PRODUCT_DIR"/plugins/*.jar 2>/dev/null | wc -l)
    echo "  ✓ GAMA headless product found at: ${GAMA_PRODUCT_DIR}"
    echo "    Plugins: ${PLUGIN_COUNT}"
elif [ -f "$GAMA_ARCHIVE" ]; then
    echo "  ✓ GAMA headless archive found: ${GAMA_ARCHIVE}"
else
    echo "  ! GAMA headless product not built yet."
    echo "  Building GAMA headless product (this may take a while)..."
    echo ""
    cd "${GAMA_ROOT}/gama.annotations" && mvn clean install -q 2>/dev/null || true
    cd "${GAMA_ROOT}/gama.processor" && mvn clean install -q 2>/dev/null || true
    cd "${GAMA_ROOT}/gama.parent" && mvn clean install -q 2>&1 | tail -5
    
    if [ -d "$GAMA_PRODUCT_DIR" ]; then
        echo "  ✓ GAMA headless product built successfully"
    else
        echo "  ✗ Failed to build GAMA headless product"
        echo "    See gama.parent build output for details"
        exit 1
    fi
fi
echo ""

# ─── Step 2: Install JS dependencies ────────────────────────────────────
echo "=== Step 2: JavaScript Dependencies ==="
cd "${SCRIPT_DIR}"
npm install
echo "  ✓ npm install complete"
echo ""

# ─── Step 3: Package GAMA into app ─────────────────────────────────────
echo "=== Step 3: Package GAMA into Mobile App ==="
mkdir -p build/rootfs/opt/gama/headless

if [ -d "$GAMA_PRODUCT_DIR" ]; then
    echo "  Copying GAMA headless product..."
    cp -r "${GAMA_PRODUCT_DIR}/"* build/rootfs/opt/gama/headless/
elif [ -f "$GAMA_ARCHIVE" ]; then
    echo "  Extracting GAMA headless archive..."
    tar xzf "$GAMA_ARCHIVE" -C build/rootfs/opt/gama/headless/
fi

# Copy launcher scripts
cp proot-setup/gama-launcher.sh build/rootfs/opt/gama/
cp proot-setup/bridge-server.py build/rootfs/opt/gama/
cp proot-setup/startup.sh build/rootfs/
cp proot-setup/java-env.sh build/rootfs/
chmod +x build/rootfs/opt/gama/*.sh build/rootfs/*.sh

echo "  ✓ GAMA packaged into build/rootfs/"
echo ""

# ─── Step 4: Configure Android local.properties ───────────────────────
echo "=== Step 4: Android SDK Configuration ==="
if [ ! -f android/local.properties ]; then
    if [ -n "${ANDROID_HOME:-}" ]; then
        echo "sdk.dir=${ANDROID_HOME}" > android/local.properties
        echo "  ✓ Created android/local.properties from ANDROID_HOME"
    elif [ -d "$HOME/Library/Android/sdk" ]; then
        echo "sdk.dir=$HOME/Library/Android/sdk" > android/local.properties
        echo "  ✓ Created android/local.properties (macOS default)"
    elif [ -d "$HOME/Android/Sdk" ]; then
        echo "sdk.dir=$HOME/Android/Sdk" > android/local.properties
        echo "  ✓ Created android/local.properties (Linux default)"
    else
        echo "  ! Could not find Android SDK."
        echo "    Please create android/local.properties manually:"
        echo "    echo \"sdk.dir=/path/to/android/sdk\" > android/local.properties"
    fi
else
    echo "  ✓ android/local.properties already exists"
fi
echo ""

# ─── Step 5: Summary ──────────────────────────────────────────────────
echo "╔═══════════════════════════════════════════════════════════╗"
echo "║              Setup Complete                              ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo ""
echo "Quick start:"
echo "  make build-backend     # Build Java API server JAR"
echo "  make build-rootfs      # Build rootfs (requires Docker)"
echo "  npx react-native run-android  # Build & run on device"
echo ""
echo "Testing:"
echo "  make test-backend      # Test backend locally"
echo "  make test              # Run JS unit tests"
echo ""
echo "Key paths:"
echo "  Android app:   android/"
echo "  Backend:       backend/api-server/"
echo "  GAMA product:  build/rootfs/opt/gama/headless/"
echo "  Rootfs:        build/rootfs/"
echo ""
echo "For first-time build:"
echo "  make all"
