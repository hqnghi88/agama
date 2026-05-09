#!/bin/bash
set -euo pipefail

# Install GAMA Mobile app on connected Android device
# Usage: ./scripts/install.sh [--reinstall]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOBILE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "╔═══════════════════════════════════════════════════════════╗"
echo "║        GAMA Mobile - APK Installer                      ║"
echo "╚═══════════════════════════════════════════════════════════╝"

# ─── Check ADB ────────────────────────────────────────────────────────
if ! command -v adb &> /dev/null; then
    echo "[install] ✗ ADB not found. Install Android platform tools."
    echo "  macOS: brew install android-platform-tools"
    echo "  Linux: apt install adb"
    exit 1
fi

# ─── Check device ─────────────────────────────────────────────────────
DEVICES=$(adb devices 2>/dev/null | tail -n +2 | grep -v "^$" | grep -v "offline$" || true)
if [ -z "${DEVICES}" ]; then
    echo "[install] ✗ No Android device connected."
    echo "  Connect your phone via USB and enable USB debugging."
    echo ""
    echo "  Quick setup:"
    echo "    1. Enable Developer Options (Settings → About → Tap Build Number 7x)"
    echo "    2. Enable USB Debugging (Settings → Developer Options)"
    echo "    3. Connect phone via USB"
    echo "    4. Accept the RSA fingerprint prompt on your phone"
    echo "    5. Run this script again"
    echo ""
    echo "  To verify: adb devices"
    exit 1
fi

echo "[install] Device found:"
adb devices -l | grep -v "^List"

# ─── Build APK if needed ──────────────────────────────────────────────
APK_PATH="${MOBILE_ROOT}/android/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "${APK_PATH}" ] || [ "${1:-}" = "--rebuild" ]; then
    echo "[install] Building debug APK..."
    cd "${MOBILE_ROOT}"
    JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
        ./android/gradlew -p android assembleDebug 2>&1 | tail -5
    echo "[install] Build complete"
fi

if [ ! -f "${APK_PATH}" ]; then
    echo "[install] ✗ APK not found at ${APK_PATH}"
    exit 1
fi

echo "[install] APK: ${APK_PATH} ($(du -h "${APK_PATH}" | cut -f1))"

# ─── Uninstall old version if requested ──────────────────────────────
if [ "${1:-}" = "--reinstall" ]; then
    echo "[install] Uninstalling previous version..."
    adb uninstall com.simulation.mobile || true
fi

# ─── Install APK ──────────────────────────────────────────────────────
echo "[install] Installing APK on device..."
if adb install -r "${APK_PATH}" 2>&1; then
    echo "[install] ✓ App installed successfully!"
else
    echo "[install] ✗ Installation failed"
    echo "  Try: adb uninstall com.simulation.mobile"
    echo "  Then run this script again"
    exit 1
fi

# ─── Launch app ───────────────────────────────────────────────────────
echo "[install] Launching app..."
adb shell am start -n com.simulation.mobile/.MainActivity 2>&1 || true

echo ""
echo "╔═══════════════════════════════════════════════════════════╗"
echo "║        App launched on your device!                      ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo ""
echo "  What to expect:"
echo "  - Dashboard screen with system status"
echo "  - Backend status shows 'Offline' (no rootfs yet)"
echo "  - Use 'START' button to test the mock simulation"
echo ""
echo "  To view logs: adb logcat | grep -E '(SimulationService|PRootManager|ReactNative)'"
echo "  To check app: adb shell dumpsys package com.simulation.mobile"
