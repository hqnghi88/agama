#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(dirname "$SCRIPT_DIR")"
ANDROID_DIR="$APP_DIR/android"
SDK_DIR="$HOME/Library/Android/sdk"
ADB="$SDK_DIR/platform-tools/adb"
PKG="com.simulation.mobile"

export JAVA_HOME="$HOME/tools/jdk-21.0.7+6/Contents/Home"

echo "=== GAMA Mobile Dev Script ==="

# Check emulator
$ADB devices | grep -q "emulator" || { echo "ERROR: No emulator running. Start one first."; exit 1; }

# Kill metro if running
pkill -f "react-native start" 2>/dev/null || true

# Build
echo "[1/4] Building..."
cd "$ANDROID_DIR"
./gradlew assembleDebug 2>&1 | tail -1

# Install
echo "[2/4] Installing..."
$ADB uninstall $PKG 2>/dev/null || true
$ADB install "$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"

# Launch
echo "[3/4] Launching..."
$ADB shell am start -n "$PKG/.MainActivity"

# Wait and show status
sleep 5
echo "[4/4] Status:"
PID=$($ADB shell pidof $PKG 2>/dev/null)
if [ -n "$PID" ]; then
  echo "  App running (PID: $PID)"
  $ADB logcat -d --pid=$PID -t 20 | grep -E "SimulationService|VncView|Progress|PRoot|rootfs|CONNECTED" | tail -8
else
  echo "  App not running!"
fi

echo "=== Done ==="
