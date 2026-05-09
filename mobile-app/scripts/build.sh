#!/bin/bash
set -euo pipefail

# ═══════════════════════════════════════════════════════════════════════
#  GAMA Mobile — Full Build from Scratch
#  One script to check prerequisites, build rootfs, bundle JS, and
#  produce the final APK — identical to what runs on the device.
# ═══════════════════════════════════════════════════════════════════════

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOBILE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
pass()  { echo -e "  ${GREEN}✓${NC} $1"; }
warn()  { echo -e "  ${YELLOW}⚠${NC} $1"; }
fail()  { echo -e "  ${RED}✗${NC} $1"; }
info()  { echo -e "  ${CYAN}→${NC} $1"; }
header(){ echo -e "\n${CYAN}══ $1 ══${NC}"; }

# ─── Configuration ────────────────────────────────────────────────────
# These match the current working device setup (Java 25 Temurin, ARM64)
JAVA_25_VERSION="jdk-25.0.3+9"
JAVA_25_URL="https://github.com/adoptium/temurin25-binaries/releases/download/${JAVA_25_VERSION}/OpenJDK25U-jre_aarch64_linux_hotspot_${JAVA_25_VERSION}.tar.gz"
BUILD_JDK_VERSION="21"  # JDK version needed to compile the Android app
ROOTFS_ARCHIVE="${MOBILE_ROOT}/android/app/src/main/res/raw/rootfs_tar_gz"

# ─── Check Prerequisites ──────────────────────────────────────────────

check_prereqs() {
  local ok=true

  echo ""
  echo "  GAMA Mobile — Build Environment Check"
  echo "  ====================================="
  echo ""

  # JDK 21 for Android build
  if command -v javac &>/dev/null; then
    javac_version=$(javac -version 2>&1 | grep -o '[0-9]\+' | head -1)
    if [ "$javac_version" -ge "$BUILD_JDK_VERSION" ] 2>/dev/null; then
      pass "JDK ${javac_version} found for Android build"
    else
      warn "JDK ${javac_version} found, need ≥${BUILD_JDK_VERSION}. Set JAVA_HOME."
      ok=false
    fi
  else
    fail "javac not found. Install JDK ${BUILD_JDK_VERSION}+ (Eclipse Temurin recommended)."
    ok=false
  fi

  # Node.js
  if command -v node &>/dev/null; then
    pass "Node.js $(node --version)"
  else
    fail "Node.js not found"
    ok=false
  fi

  # npm
  if command -v npm &>/dev/null; then
    pass "npm $(npm --version)"
  else
    fail "npm not found"
    ok=false
  fi

  # Docker (for rootfs rebuild — optional if rootfs is already in git)
  if command -v docker &>/dev/null; then
    pass "Docker $(docker --version 2>/dev/null | awk '{print $NF}' || echo 'found')"
  else
    warn "Docker not found — rootfs rebuild disabled (use pre-built archive from git)"
  fi

  # Android SDK
  ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
  if [ -d "$ANDROID_HOME" ]; then
    pass "Android SDK at ${ANDROID_HOME}"
  else
    warn "ANDROID_HOME not found at ${ANDROID_HOME}"
    warn "  Set ANDROID_HOME or install Android SDK (platforms;android-34, build-tools;34.0.0)"
    ok=false
  fi

  # local.properties (needed by Gradle)
  local_props="${MOBILE_ROOT}/android/local.properties"
  if [ ! -f "$local_props" ]; then
    info "Writing android/local.properties (sdk.dir=${ANDROID_HOME})"
    echo "sdk.dir=${ANDROID_HOME}" > "$local_props"
  else
    pass "android/local.properties exists"
  fi

  if [ "$ok" = false ]; then
    echo ""
    echo -e "  ${RED}Fix the issues above, then re-run this script.${NC}"
    exit 1
  fi
}

# ─── Install JS Dependencies ──────────────────────────────────────────

install_js_deps() {
  header "JavaScript Dependencies"
  cd "$MOBILE_ROOT"
  if [ -d "node_modules" ] && [ -f "node_modules/.package-lock.json" ]; then
    pass "node_modules already installed"
    return
  fi
  info "Running npm install..."
  npm install --legacy-peer-deps
  pass "npm install complete"
}

# ─── Build Rootfs (optional — git already has a pre-built archive) ────

build_rootfs() {
  header "Rootfs Archive"
  if [ -f "$ROOTFS_ARCHIVE" ]; then
    size=$(du -h "$ROOTFS_ARCHIVE" | cut -f1)
    pass "Rootfs archive exists at res/raw/ ($size)"
    info "Skip rebuild? [Y/n]: "
    read -r skip
    if [[ "$skip" =~ ^[Nn] ]]; then
      info "Rebuilding rootfs via Docker (requires Docker Desktop + QEMU)..."
      "$SCRIPT_DIR/build-rootfs.sh"
      pass "Rootfs rebuilt at ${ROOTFS_ARCHIVE}"
    fi
  else
    warn "No rootfs archive found at ${ROOTFS_ARCHIVE}"
    info "Rebuilding rootfs via Docker..."
    "$SCRIPT_DIR/build-rootfs.sh"
    pass "Rootfs built at ${ROOTFS_ARCHIVE}"
  fi
}

# ─── Build Android APK ────────────────────────────────────────────────

build_apk() {
  header "Android APK"
  cd "$MOBILE_ROOT/android"

  # Ensure clean JS bundle
  info "Cleaning stale JS bundle..."
  rm -f "app/build/generated/assets/react/debug/index.android.bundle"

  info "Running assembleDebug..."
  JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-${BUILD_JDK_VERSION}.jdk/Contents/Home}" \
  ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}" \
  ./gradlew assembleDebug

  local apk="app/build/outputs/apk/debug/app-debug.apk"
  if [ -f "$apk" ]; then
    pass "APK built: ${apk} ($(du -h "$apk" | cut -f1))"
  else
    fail "APK not found at ${apk}"
    exit 1
  fi
}

# ─── Install on Device via ADB ────────────────────────────────────────

install_on_device() {
  header "Install on Device (ADB)"
  if ! command -v adb &>/dev/null; then
    warn "adb not found — skip device install"
    return
  fi

  local apk="${MOBILE_ROOT}/android/app/build/outputs/apk/debug/app-debug.apk"
  if [ ! -f "$apk" ]; then
    warn "APK not found at ${apk} — run './scripts/build.sh build' first"
    return
  fi

  info "Checking for connected device..."
  if ! adb get-state 1>/dev/null 2>&1; then
    warn "No device connected via ADB"
    return
  fi

  info "Installing ${apk}..."
  adb install -r "$apk" 2>&1 || adb install "$apk" 2>&1
  pass "APK installed on device"
  info "Start the app manually — PRoot + bridge + health-check all auto-start"
}

# ═══════════════════════════════════════════════════════════════════════
#  Main
# ═══════════════════════════════════════════════════════════════════════

usage() {
  echo ""
  echo "  Usage: ./scripts/build.sh [command]"
  echo ""
  echo "  Commands:"
  echo "    all       Full build: check → deps → rootfs → APK (default)"
  echo "    check     Only check prerequisites"
  echo "    deps      Install JS dependencies only"
  echo "    rootfs    Build/check rootfs archive only"
  echo "    apk       Build APK only (assumes rootfs + deps ready)"
  echo "    install   Build APK + install on connected device via ADB"
  echo "    help      Show this help"
  echo ""
}

CMD="${1:-all}"

case "$CMD" in
  all)
    check_prereqs
    install_js_deps
    build_rootfs
    build_apk
    header "Done"
    pass "APK ready at android/app/build/outputs/apk/debug/app-debug.apk"
    echo ""
    info "APK install via ./scripts/build.sh install"
    info "or: adb install -r android/app/build/outputs/apk/debug/app-debug.apk"
    ;;
  check)
    check_prereqs
    ;;
  deps)
    check_prereqs
    install_js_deps
    ;;
  rootfs)
    check_prereqs
    build_rootfs
    ;;
  apk)
    build_apk
    ;;
  install)
    check_prereqs
    install_js_deps
    build_rootfs
    build_apk
    install_on_device
    ;;
  help|--help|-h)
    usage
    ;;
  *)
    echo -e "  ${RED}Unknown command: ${CMD}${NC}"
    usage
    exit 1
    ;;
esac
