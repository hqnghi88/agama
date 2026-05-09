#!/bin/bash
set -euo pipefail

# Extract and prepare rootfs for Android app storage
# This script is run on the Android device by the native service

ROOTFS_DIR="${1:-/data/local/tmp/gama-rootfs}"
ARCHIVE_PATH="${2:-/data/local/tmp/rootfs.tar.gz}"

echo "[*] Preparing rootfs at ${ROOTFS_DIR}"

if [ ! -f "${ARCHIVE_PATH}" ]; then
  echo "[!] Rootfs archive not found at ${ARCHIVE_PATH}"
  exit 1
fi

mkdir -p "${ROOTFS_DIR}"

echo "[*] Extracting rootfs..."
tar xzf "${ARCHIVE_PATH}" -C "${ROOTFS_DIR}"

echo "[*] Creating writable directories..."
mkdir -p "${ROOTFS_DIR}/tmp"
mkdir -p "${ROOTFS_DIR}/data"
mkdir -p "${ROOTFS_DIR}/workspace"
mkdir -p "${ROOTFS_DIR}/opt/gama"
chmod 1777 "${ROOTFS_DIR}/tmp"

echo "[*] Setting up environment..."
cat > "${ROOTFS_DIR}/etc/profile.d/gama-env.sh" << 'ENVEOF'
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export PATH=$JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export GAMA_HOME=/opt/gama
export HOME=/data
export TMPDIR=/tmp
ENVEOF

chmod +x "${ROOTFS_DIR}/etc/profile.d/gama-env.sh"

echo "[*] Rootfs prepared at ${ROOTFS_DIR}"
echo "[*] Java version: $(${ROOTFS_DIR}/usr/lib/jvm/java-17-openjdk-arm64/bin/java -version 2>&1 | head -1)"

# Verify Java works
echo "[*] Verification:"
ls -la "${ROOTFS_DIR}/usr/lib/jvm/" 2>/dev/null || echo "  No JDK found"
ls -la "${ROOTFS_DIR}/opt/gama/" 2>/dev/null || echo "  GAMA home empty"
