#!/bin/bash
set -euo pipefail

# Build PRoot for Android ARM64
# PRoot allows us to run Linux userspace without root on Android
# Uses PRoot source from https://github.com/proot-me/proot

OUTPUT_DIR="${1:-./proot-build}"
BUILD_DIR="${OUTPUT_DIR}/build"
PRoot_VERSION="5.4.0"
NDK_DIR="${ANDROID_NDK_HOME:-$HOME/android-ndk}"

echo "[*] Building PRoot ${PRoot_VERSION} for Android ARM64"
mkdir -p "${BUILD_DIR}"

# Clone PRoot
if [ ! -d "${BUILD_DIR}/proot" ]; then
  git clone --depth 1 --branch v${PRoot_VERSION} \
    https://github.com/proot-me/proot.git "${BUILD_DIR}/proot"
fi

echo "[*] Setting up Android NDK..."
if [ ! -d "${NDK_DIR}" ]; then
  echo "[!] Android NDK not found at ${NDK_DIR}"
  echo "    Please set ANDROID_NDK_HOME or install NDK"
  echo "    Download from: https://developer.android.com/ndk/downloads"
  exit 1
fi

TOOLCHAIN="${NDK_DIR}/toolchains/llvm/prebuilt/darwin-x86_64"
if [ "$(uname)" == "Linux" ]; then
  TOOLCHAIN="${NDK_DIR}/toolchains/llvm/prebuilt/linux-x86_64"
fi

export CC="${TOOLCHAIN}/bin/aarch64-linux-android21-clang"
export CXX="${TOOLCHAIN}/bin/aarch64-linux-android21-clang++"
export AR="${TOOLCHAIN}/bin/llvm-ar"
export RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
export STRIP="${TOOLCHAIN}/bin/llvm-strip"

echo "[*] Compiling PRoot for arm64-v8a..."
cd "${BUILD_DIR}/proot/src"

# PRoot uses GNU Make with no configure step for the core
make \
  CC="${CC}" \
  CXX="${CXX}" \
  AR="${AR}" \
  RANLIB="${RANLIB}" \
  STRIP="${STRIP}" \
  V=1 \
  -j$(nproc)

# The binary is called 'proot'
if [ -f "./proot" ]; then
  cp "./proot" "${OUTPUT_DIR}/proot-arm64"
  ${STRIP} "${OUTPUT_DIR}/proot-arm64"
  echo "[*] PRoot binary: ${OUTPUT_DIR}/proot-arm64"
  file "${OUTPUT_DIR}/proot-arm64"
  echo "[*] Size: $(du -sh ${OUTPUT_DIR}/proot-arm64 | cut -f1)"
else
  echo "[!] Build failed - proot binary not found"
  exit 1
fi

echo ""
echo "[*] Done! PRoot binary for ARM64 ready."
echo "    Copy to: mobile-app/android/app/src/main/res/raw/proot_arm64"
echo "    The app will extract it to the data directory on first launch."
