#!/bin/bash
set -euo pipefail

# Package the existing GAMA headless product into the mobile app's rootfs
# This script copies the built GAMA product and creates the launcher scripts

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOBILE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
GAMA_ROOT="$(cd "${MOBILE_ROOT}/.." && pwd)"

OUTPUT_DIR="${MOBILE_ROOT}/build/rootfs"
GAMA_HOME="${OUTPUT_DIR}/opt/gama"
GAMA_PRODUCT_SRC="${GAMA_ROOT}/gama.product/target/products/gama.headless.product/linux/gtk/aarch64"
GAMA_ARCHIVE="${GAMA_ROOT}/gama.product/target/products/gama.headless-linux.gtk.aarch64.tar.gz"

echo "[gama] Packaging GAMA headless product for mobile app"
echo "[gama] Source: ${GAMA_PRODUCT_SRC}"
echo "[gama] Output: ${GAMA_HOME}"

# Create directory structure
mkdir -p "${GAMA_HOME}/headless"
mkdir -p "${GAMA_HOME}/logs"
mkdir -p "${OUTPUT_DIR}/workspace"
mkdir -p "${OUTPUT_DIR}/data"
mkdir -p "${OUTPUT_DIR}/tmp"

# Copy GAMA headless product
if [ -d "${GAMA_PRODUCT_SRC}" ]; then
    echo "[gama] Copying from product directory..."
    cp -r "${GAMA_PRODUCT_SRC}/"* "${GAMA_HOME}/headless/"
elif [ -f "${GAMA_ARCHIVE}" ]; then
    echo "[gama] Extracting from archive..."
    tar xzf "${GAMA_ARCHIVE}" -C "${GAMA_HOME}/headless/"
else
    echo "[gama] ✗ GAMA headless product not found!"
    echo "    Build it first: cd ${GAMA_ROOT}/gama.parent && mvn clean install"
    exit 1
fi

# Verify product structure
PLUGIN_COUNT=$(ls "${GAMA_HOME}/headless/plugins/"*.jar 2>/dev/null | wc -l)
echo "[gama] Plugins copied: ${PLUGIN_COUNT}"

if [ "${PLUGIN_COUNT}" -lt 100 ]; then
    echo "[gama] ✗ Too few plugins (${PLUGIN_COUNT}). Product may be incomplete."
    exit 1
fi

# Copy launcher scripts
cp "${MOBILE_ROOT}/proot-setup/gama-launcher.sh" "${GAMA_HOME}/"
cp "${MOBILE_ROOT}/proot-setup/bridge-server.py" "${GAMA_HOME}/"
cp "${MOBILE_ROOT}/proot-setup/java-env.sh" "${GAMA_HOME}/"
cp "${MOBILE_ROOT}/proot-setup/java-env.sh" "${OUTPUT_DIR}/"
cp "${MOBILE_ROOT}/proot-setup/startup.sh" "${OUTPUT_DIR}/"

chmod +x "${GAMA_HOME}/gama-launcher.sh" "${GAMA_HOME}/java-env.sh" "${OUTPUT_DIR}/startup.sh" "${OUTPUT_DIR}/java-env.sh"

# Create GAMA configuration override for mobile (reduced memory)
cat > "${GAMA_HOME}/headless/eclipse.ini" << 'INIEOF'
-server
-Xms256m
-Xmx1024m
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:+UseStringDeduplication
-Dosgi.locking=none
-Dosgi.checkConfiguration=false
-Djava.awt.headless=true
-Declipse.log.level=ERROR
-Denable_logging=false
-Denable_debug=false
--add-exports=java.base/java.lang=ALL-UNNAMED
--add-exports=java.desktop/sun.awt=ALL-UNNAMED
--add-exports=java.desktop/sun.java2d=ALL-UNNAMED
--add-opens=java.base/java.lang=ALL-UNNAMED
--add-opens=java.base/java.math=ALL-UNNAMED
--add-opens=java.base/java.util=ALL-UNNAMED
--add-opens=java.base/java.io=ALL-UNNAMED
--add-opens=java.desktop/java.awt=ALL-UNNAMED
--add-opens=java.desktop/java.awt.image=ALL-UNNAMED
--enable-preview
INIEOF

echo "[gama] ✓ Product packaged successfully"
echo "[gama] Total size: $(du -sh "${GAMA_HOME}" | cut -f1)"
echo ""
echo "Next step: Run make build-rootfs to create the complete rootfs"
