#!/bin/bash
set -euo pipefail

# Build a minimal Debian ARM64 rootfs for GAMA Mobile
# Packages GAMA headless product + OpenJDK + dependencies
#
# Usage: ./build-rootfs.sh [rootfs-dir]
#   rootfs-dir: Directory containing pre-staged files (opt/, etc.)
#               If not provided, builds from scratch

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOBILE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

OUTPUT_DIR="${1:-${MOBILE_ROOT}/build/rootfs}"
ROOTFS_TAR="${MOBILE_ROOT}/android/app/src/main/res/raw/rootfs_tar_gz"
APK_ASSETS_DIR="${MOBILE_ROOT}/android/app/src/main/res/raw"
DEBIAN_VERSION="bookworm"

echo "╔═══════════════════════════════════════════════════════════╗"
echo "║       GAMA Mobile - Rootfs Builder                       ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo ""
echo "[rootfs] Building Debian ${DEBIAN_VERSION} ARM64 rootfs"
echo "[rootfs] Output: ${OUTPUT_DIR}"

mkdir -p "${OUTPUT_DIR}"

# ─── Detect Docker ────────────────────────────────────────────────────
if ! command -v docker &> /dev/null; then
    echo "[rootfs] ✗ Docker not found. Install Docker Desktop or Docker Engine."
    exit 1
fi

# ─── Check for pre-staged GAMA product ────────────────────────────────
if [ -d "${OUTPUT_DIR}/opt/gama/headless/plugins" ]; then
    PLUGIN_COUNT=$(ls "${OUTPUT_DIR}/opt/gama/headless/plugins/"*.jar 2>/dev/null | wc -l)
    echo "[rootfs] Pre-staged GAMA product found: ${PLUGIN_COUNT} plugins"
    HAS_GAMA=true
else
    echo "[rootfs] No pre-staged GAMA product found."
    echo "[rootfs] Run 'make package-gama' first or copy product manually."
    echo "[rootfs] Proceeding with minimal rootfs (Phase 1 mode)..."
    HAS_GAMA=false
fi

# ─── Clean output dir to prevent stale files from previous builds ────
# The Docker export is extracted on top of OUTPUT_DIR.  If old/pinned
# binaries (e.g. Python 3.14 from an earlier manual install) exist in
# /usr/local/bin they won't be overwritten by the export and will
# break at runtime (GLIBC version mismatch).
echo "[rootfs] Cleaning output directory for fresh extraction..."
rm -rf "${OUTPUT_DIR:?}/"*
mkdir -p "${OUTPUT_DIR}"

# ─── Build rootfs via Docker ──────────────────────────────────────────
echo "[rootfs] Building rootfs (this may take a few minutes)..."

# Create a Dockerfile for the rootfs build
cat > /tmp/Dockerfile.gama-rootfs << 'DOCKEREOF'
FROM arm64v8/debian:bookworm-slim AS rootfs

# Avoid interactive debconf
ENV DEBIAN_FRONTEND=noninteractive
ENV DEBCONF_NONINTERACTIVE_SEEN=true

# Install core packages (openjdk-17-headless for system deps only, actual JRE is Temurin 25 below)
RUN apt-get update && apt-get install -y --no-install-recommends \
    # Required runtime
    ca-certificates \
    curl \
    # System utilities
    procps \
    iproute2 \
    netcat-openbsd \
    # Python for bridge server
    python3 \
    python3-pip \
    # Build tools (optional)
    gpg \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Download and install Eclipse Temurin JDK 25 JRE (ARM64)
RUN TEMURIN_URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.3+9/OpenJDK25U-jre_aarch64_linux_hotspot_25.0.3_9.tar.gz" && \
    echo "[rootfs] Downloading Temurin JDK 25 JRE..." && \
    curl -fsSL -o /tmp/jdk.tar.gz "$TEMURIN_URL" && \
    mkdir -p /usr/lib/jvm/java-25 && \
    tar xzf /tmp/jdk.tar.gz -C /usr/lib/jvm/java-25 --strip-components=1 && \
    rm /tmp/jdk.tar.gz && \
    ln -sf /usr/lib/jvm/java-25/bin/java /usr/bin/java && \
    echo "[rootfs] Temurin JDK 25 installed"

# Install Python websockets library for the bridge
RUN pip3 install --no-cache-dir --break-system-packages websockets

# Create required directories
RUN mkdir -p /tmp /data /workspace /opt/gama/logs && \
    chmod 1777 /tmp && \
    chmod 777 /data /workspace /opt/gama/logs

# Verify installations
RUN echo "=== Verification ===" && \
    echo "Java:" && java -version 2>&1 | head -1 && \
    echo "Python:" && python3 --version && \
    echo "pip:" && pip3 --version

# Set up environment
RUN echo 'export JAVA_HOME=/usr/lib/jvm/java-25' >> /etc/profile.d/gama-env.sh && \
    echo 'export PATH=$JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin' >> /etc/profile.d/gama-env.sh && \
    echo 'export _JAVA_OPTIONS="-Xms64m -Xmx512m -XX:+UseG1GC -Djava.awt.headless=true"' >> /etc/profile.d/gama-env.sh && \
    chmod +x /etc/profile.d/gama-env.sh

CMD ["/bin/bash"]
DOCKEREOF

# Build the Docker image (with fallback to pre-built archive)
if docker build \
    --platform linux/arm64 \
    -t gama-mobile-rootfs:latest \
    -f /tmp/Dockerfile.gama-rootfs \
    /tmp/; then

    # Extract the rootfs filesystem
    echo "[rootfs] Extracting filesystem..."
    ROOTFS_CONTAINER=$(docker create --platform linux/arm64 gama-mobile-rootfs:latest)
    docker export "${ROOTFS_CONTAINER}" | tar xf - -C "${OUTPUT_DIR}/" --no-same-owner --no-same-permissions 2>/dev/null || true
    docker rm "${ROOTFS_CONTAINER}" > /dev/null
    echo "[rootfs] Base filesystem extracted to ${OUTPUT_DIR}"
else
    echo "[rootfs] WARNING: Docker build failed."
    echo "[rootfs] This is often a Docker Desktop + ARM64 cross-build issue on macOS."
    echo "[rootfs] Using pre-built archive from git (already at ${ROOTFS_TAR})."
    echo "[rootfs] If you need to rebuild, ensure QEMU is properly configured:"
    echo "[rootfs]   docker run --rm --privileged tonistiigi/binfmt --install arm64"
    echo "[rootfs] Then re-run this script."
    echo ""
    echo "[rootfs] Falling back: APK will use the pre-built rootfs from git."
    echo "[rootfs] The existing archive at ${ROOTFS_TAR} will not be modified."
    # Skip remaining build steps — we keep the pre-built archive
    return 0 2>/dev/null || exit 0
fi

# ─── Copy GAMA product (if available) ─────────────────────────────────
GAMA_SOURCE_DIR="$(cd "${MOBILE_ROOT}/../gama.product/target/products/gama.headless.product/linux/gtk/aarch64" 2>/dev/null && pwd)"
GAMA_SOURCE_ARCHIVE="$(ls -t "${MOBILE_ROOT}/../gama.product/target/products/gama.headless-linux.gtk.aarch64.tar.gz" 2>/dev/null | head -1)"

if [ "${HAS_GAMA:-false}" = true ]; then
    echo "[rootfs] Re-copying GAMA product from source (Docker export overwrote the staged files)..."
    GAMA_TARGET="${OUTPUT_DIR}/opt/gama/headless"
    mkdir -p "${GAMA_TARGET}"
    if [ -d "${GAMA_SOURCE_DIR}" ]; then
        cp -r "${GAMA_SOURCE_DIR}/"* "${GAMA_TARGET}/"
        PLUGIN_COUNT=$(ls "${GAMA_TARGET}/plugins/"*.jar 2>/dev/null | wc -l)
        echo "[rootfs] Copied ${PLUGIN_COUNT} plugins from ${GAMA_SOURCE_DIR}"
    elif [ -f "${GAMA_SOURCE_ARCHIVE}" ]; then
        tar xzf "${GAMA_SOURCE_ARCHIVE}" -C "${GAMA_TARGET}/"
        PLUGIN_COUNT=$(ls "${GAMA_TARGET}/plugins/"*.jar 2>/dev/null | wc -l)
        echo "[rootfs] Extracted ${PLUGIN_COUNT} plugins from archive"
    else
        echo "[rootfs] WARNING: GAMA product source not found at expected locations"
    fi
else
    echo "[rootfs] No GAMA product to stage (standalone/Phase 1 mode)"
    # Copy starter backend JAR if it exists
    if [ -f "${MOBILE_ROOT}/backend/api-server/build/libs/api-server-uber.jar" ]; then
        echo "[rootfs] Copying standalone backend JAR..."
        mkdir -p "${OUTPUT_DIR}/opt/gama/"
        cp "${MOBILE_ROOT}/backend/api-server/build/libs/api-server-uber.jar" \
           "${OUTPUT_DIR}/opt/gama/gama-backend.jar"
    fi
fi

# ─── Copy launcher scripts ────────────────────────────────────────────
echo "[rootfs] Copying launcher scripts..."
for script in startup.sh java-env.sh; do
    if [ -f "${MOBILE_ROOT}/proot-setup/${script}" ]; then
        cp "${MOBILE_ROOT}/proot-setup/${script}" "${OUTPUT_DIR}/"
        chmod +x "${OUTPUT_DIR}/${script}"
    fi
done

# Copy GAMA-specific launcher and env
mkdir -p "${OUTPUT_DIR}/opt/gama/"
for script in gama-launcher.sh bridge-server.py java-env.sh; do
    if [ -f "${MOBILE_ROOT}/proot-setup/${script}" ]; then
        cp "${MOBILE_ROOT}/proot-setup/${script}" "${OUTPUT_DIR}/opt/gama/"
        chmod +x "${OUTPUT_DIR}/opt/gama/${script}"
    fi
done

# ─── Set up Java alternatives ─────────────────────────────────────────
# Ensure java-25 symlink and /usr/bin/java point to the Temurin JDK
if [ -d "${OUTPUT_DIR}/usr/lib/jvm/java-25" ]; then
    echo "[rootfs] Found Temurin JDK 25 at /usr/lib/jvm/java-25"
    if [ -f "${OUTPUT_DIR}/usr/lib/jvm/java-25/bin/java" ]; then
        ln -sf /usr/lib/jvm/java-25/bin/java "${OUTPUT_DIR}/usr/bin/java"
        echo "[rootfs] Java symlink: /usr/bin/java -> /usr/lib/jvm/java-25/bin/java"
    fi
fi

# ─── Clean stale/incompatible files ──────────────────────────────────
# Remove Python 3.14 from /usr/local/bin if present (requires GLIBC 2.38,
# but Debian Bookworm ships 2.36).  The system Python 3.11 at /usr/bin
# is the one we should use.
echo "[rootfs] Cleaning incompatible Python 3.14 from /usr/local..."
rm -f "${OUTPUT_DIR}/usr/local/bin/python3" \
      "${OUTPUT_DIR}/usr/local/bin/python3.14" \
      "${OUTPUT_DIR}/usr/local/bin/python3-config" \
      "${OUTPUT_DIR}/usr/local/bin/python3.14-config" \
      "${OUTPUT_DIR}/usr/local/bin/pip3" \
      "${OUTPUT_DIR}/usr/local/bin/pip3.14" \
      "${OUTPUT_DIR}/usr/local/bin/idle3" \
      "${OUTPUT_DIR}/usr/local/bin/idle3.14" \
      "${OUTPUT_DIR}/usr/local/bin/pydoc3" \
      "${OUTPUT_DIR}/usr/local/bin/pydoc3.14"
# Recreate python3 symlink pointing to system Python 3.11
ln -sf python3.11 "${OUTPUT_DIR}/usr/bin/python3"

# Remove AppleDouble metadata files from macOS
find "${OUTPUT_DIR}" -name '._*' -delete 2>/dev/null || true

# ─── Set permissions ──────────────────────────────────────────────────
echo "[rootfs] Setting permissions..."
find "${OUTPUT_DIR}/tmp" -type d -exec chmod 1777 {} + 2>/dev/null || true
find "${OUTPUT_DIR}/dev" -type d -exec chmod 755 {} + 2>/dev/null || true

# Make shell scripts executable (Android toybox tar doesn't preserve permissions)
find "${OUTPUT_DIR}" -name '*.sh' -exec chmod +x {} + 2>/dev/null || true
find "${OUTPUT_DIR}" -name '*.py' -exec chmod +x {} + 2>/dev/null || true

# ─── Verify rootfs ────────────────────────────────────────────────────
echo "[rootfs] Verifying rootfs..."
# Check essential directories exist
for dir in bin usr/bin usr/lib tmp etc; do
    if [ ! -d "${OUTPUT_DIR}/${dir}" ]; then
        echo "[rootfs] WARNING: Missing directory: ${dir}"
    fi
done

# Check Java
if [ -f "${OUTPUT_DIR}/usr/bin/java" ]; then
    echo "[rootfs] Java: OK"
elif [ -n "$(find "${OUTPUT_DIR}" -name "java" -type f 2>/dev/null | head -1)" ]; then
    echo "[rootfs] Java: found at alternative location"
else
    echo "[rootfs] WARNING: Java not found in rootfs"
fi

# Check Python
if [ -f "${OUTPUT_DIR}/usr/bin/python3" ]; then
    echo "[rootfs] Python3: OK"
else
    echo "[rootfs] WARNING: Python3 not found"
fi

# ─── Package rootfs ───────────────────────────────────────────────────
echo "[rootfs] Packaging rootfs archive..."
mkdir -p "${MOBILE_ROOT}/assets"
cd "${OUTPUT_DIR}"
tar czf "${ROOTFS_TAR}" \
    --exclude='./proc' \
    --exclude='./sys' \
    --exclude='./dev' \
    --exclude='./run' \
    --exclude='./mnt' \
    --exclude='./media' \
    --exclude='./lost+found' \
    .
cd - > /dev/null

echo ""
echo "╔═══════════════════════════════════════════════════════════╗"
echo "║              Rootfs Build Complete                       ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo ""
echo "  Archive: ${ROOTFS_TAR}"
echo "  Size:    $(du -sh "${ROOTFS_TAR}" | cut -f1)"
echo "  Content: $(tar tzf "${ROOTFS_TAR}" 2>/dev/null | wc -l) files"
echo ""
echo "To deploy:"
echo "  1. Rootfs archive is already at: ${ROOTFS_TAR}"
echo "  2. It is bundled into the APK at compile time"
echo "  3. Run 'make build-android' or './scripts/build.sh' to build the APK"
echo ""
