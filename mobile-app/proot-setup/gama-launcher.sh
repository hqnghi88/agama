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

# Read JVM args from eclipse.ini, filtering out memory settings and non-flag lines
# Mobile memory is set via GAMA_JAVA_OPTS or java-env.sh instead
JVM_ARGS=()
while IFS= read -r line; do
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [ -z "$line" ] && continue
    # Handle --add-modules with value on next line (e.g. --add-modules\njdk.incubator.vector)
    if [[ "$line" == "--add-modules" ]]; then
        IFS= read -r next_line
        next_line="${next_line#"${next_line%%[![:space:]]*}"}"
        next_line="${next_line%"${next_line##*[![:space:]]}"}"
        line="--add-modules=${next_line}"
    fi
    [[ "$line" == -* ]] || continue
    [[ "$line" == "-server" ]] && continue
    [[ "$line" == "--launcher."* ]] && continue
    [[ "$line" == "-startup" ]] && continue
    [[ "$line" == "--launcher" ]] && continue
    [[ "$line" == "-vmargs" ]] && continue
    [[ "$line" == "-showsplash" ]] && continue
    [[ "$line" == -Xms* ]] && continue
    [[ "$line" == -Xmx* ]] && continue
    [[ "$line" == "--sun-misc-unsafe-memory-access="* ]] && continue
    JVM_ARGS+=("$line")
done < "${GAMA_HOME}/eclipse.ini"

# Source mobile-optimized Java settings if available
if [ -f /opt/gama/java-env.sh ]; then
    source /opt/gama/java-env.sh
elif [ -f /java-env.sh ]; then
    source /java-env.sh
elif [ -f /etc/profile.d/gama-env.sh ]; then
    source /etc/profile.d/gama-env.sh
fi

# Use GAMA_JAVA_OPTS env var if set, otherwise use defaults suitable for mobile
if [ -n "${GAMA_JAVA_OPTS:-}" ]; then
    read -ra MOBILE_JVM_ARGS <<< "${GAMA_JAVA_OPTS}"
elif [ -n "${JAVA_OPTS:-}" ]; then
    read -ra MOBILE_JVM_ARGS <<< "${JAVA_OPTS}"
else
    MOBILE_JVM_ARGS=(-Xms64m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+UseStringDeduplication)
fi

# Combine mobile memory settings with eclipse.ini flags
FINAL_JVM_ARGS=("${MOBILE_JVM_ARGS[@]}" "${JVM_ARGS[@]}")

echo "[gama-launcher] Final JVM args: ${FINAL_JVM_ARGS[*]}"

# Ensure workspace exists (ignore mkdir failures - seccomp ENOSYS)
mkdir -p "${GAMA_WORKSPACE}" 2>/dev/null || true
mkdir -p "${GAMA_LOG}" 2>/dev/null || true

# Build classpath from all plugins
# Use the equinox launcher as the main entry point
CLASSPATH="${GAMA_LAUNCHER}"

echo "[gama-launcher] Launching GAMA..."
echo "[gama-launcher] Working directory: $(pwd 2>&1)"
echo "[gama-launcher] Command: java -cp ${CLASSPATH} ${FINAL_JVM_ARGS[*]} org.eclipse.equinox.launcher.Main -configuration ${GAMA_HOME}/configuration -application gama.headless.product -data ${GAMA_WORKSPACE} -socket ${GAMA_WS_PORT}"

unset _JAVA_OPTIONS
exec java \
    -cp "${CLASSPATH}" \
    "${FINAL_JVM_ARGS[@]}" \
    org.eclipse.equinox.launcher.Main \
    -configuration "${GAMA_HOME}/configuration" \
    -application gama.headless.product \
    -data "${GAMA_WORKSPACE}" \
    -socket "${GAMA_WS_PORT}"
