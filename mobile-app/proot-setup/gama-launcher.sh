#!/bin/bash
set -euo pipefail

# GAMA headless launcher for Android/PRoot environment
# Starts the GAMA headless Equinox application in WebSocket server mode

GAMA_HOME="/opt/gama/headless"
GAMA_LAUNCHER=$(ls "${GAMA_HOME}/plugins/org.eclipse.equinox.launcher_"*.jar 2>/dev/null | head -1)
GAMA_WS_PORT="${GAMA_WS_PORT:-6868}"
GAMA_WORKSPACE="${GAMA_WORKSPACE:-/workspace}"
GAMA_LOG="${GAMA_LOG:-/opt/gama/logs}"

echo "[gama-launcher] Starting GAMA headless"
echo "[gama-launcher] Home: ${GAMA_HOME}"
echo "[gama-launcher] Port: ${GAMA_WS_PORT}"
echo "[gama-launcher] Workspace: ${GAMA_WORKSPACE}"

if [ -z "${GAMA_LAUNCHER}" ]; then
    echo "[gama-launcher] ERROR: Equinox launcher not found in ${GAMA_HOME}/plugins/"
    exit 1
fi

echo "[gama-launcher] Equinox launcher: $(basename ${GAMA_LAUNCHER})"

# Read JVM args from eclipse.ini (skip first line if it's -server)
# Filter out -server, --launcher.* lines, and take only valid JVM flags
JVM_ARGS=()
while IFS= read -r line; do
    line=$(echo "$line" | xargs)
    [ -z "$line" ] && continue
    [[ "$line" == -* ]] || continue
    [[ "$line" == "-server" ]] && continue
    [[ "$line" == "--launcher."* ]] && continue
    [[ "$line" == "-startup" ]] && continue
    [[ "$line" == "--launcher" ]] && continue
    [[ "$line" == "-vmargs" ]] && continue
    [[ "$line" == "-showsplash" ]] && continue
    JVM_ARGS+=("$line")
done < "${GAMA_HOME}/eclipse.ini"

echo "[gama-launcher] JVM args: ${JVM_ARGS[*]}"

# Ensure workspace exists
mkdir -p "${GAMA_WORKSPACE}"
mkdir -p "${GAMA_LOG}"

# Build classpath from all plugins
# Use the equinox launcher as the main entry point
CLASSPATH="${GAMA_LAUNCHER}"

echo "[gama-launcher] Launching GAMA..."
echo "[gama-launcher] Command: java -cp ${CLASSPATH} ${JVM_ARGS[*]} org.eclipse.equinox.launcher.Main -configuration ${GAMA_HOME}/configuration -application gama.headless.product -data ${GAMA_WORKSPACE} -socket ${GAMA_WS_PORT}"

exec java \
    -cp "${CLASSPATH}" \
    "${JVM_ARGS[@]}" \
    org.eclipse.equinox.launcher.Main \
    -configuration "${GAMA_HOME}/configuration" \
    -application gama.headless.product \
    -data "${GAMA_WORKSPACE}" \
    -socket "${GAMA_WS_PORT}"
