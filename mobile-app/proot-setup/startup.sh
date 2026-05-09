#!/bin/bash
# Rootfs startup.sh - runs inside PRoot Linux container
# Started by PRootManager's generated /opt/gama/startup.sh

export JAVA_HOME=/usr/lib/jvm/java-25
export PATH=$JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/data
export TMPDIR=/tmp
export GAMA_HOME=/opt/gama
export BACKEND_PORT=${BACKEND_PORT:-8080}
export GAMA_WS_PORT=${GAMA_WS_PORT:-6868}

# Port check function (uses Python since nc may not be available)
check_port() {
  python3 -c "import socket; s=socket.socket(); s.settimeout(2); s.connect(('$1', $2)); s.close()" 2>/dev/null
}

echo "[rootfs] GAMA Mobile rootfs startup"
java -version 2>&1 | head -1 || echo "[rootfs] Java not found"
echo "[rootfs] Backend port: ${BACKEND_PORT}"
echo "[rootfs] GAMA WS port: ${GAMA_WS_PORT}"

mkdir -p /tmp /data /workspace /opt/gama/logs 2>/dev/null

[ -f /etc/profile.d/gama-env.sh ] && . /etc/profile.d/gama-env.sh

# Detect GAMA headless product
GAMA_AVAILABLE=false
if [ -d "${GAMA_HOME}/headless/plugins" ] && ls "${GAMA_HOME}/headless/plugins/org.eclipse.equinox.launcher_"*.jar >/dev/null 2>&1; then
    GAMA_AVAILABLE=true
    PLUGIN_COUNT=$(ls "${GAMA_HOME}/headless/plugins/"*.jar 2>/dev/null | wc -l)
    echo "[rootfs] GAMA headless product detected: ${PLUGIN_COUNT} plugins"
else
    echo "[rootfs] GAMA headless product not found"
fi

# Start GAMA Headless (if available)
if [ "${GAMA_AVAILABLE}" = true ]; then
    echo "[rootfs] Starting GAMA headless WebSocket server on port ${GAMA_WS_PORT}..."
    /opt/gama/gama-launcher.sh \
        > /opt/gama/logs/gama-stdout.log 2> /opt/gama/logs/gama-stderr.log &
    GAMA_PID=$!
    echo "[rootfs] GAMA PID: ${GAMA_PID}"

    echo "[rootfs] Waiting for GAMA WebSocket server..."
    for i in $(seq 1 120); do
        check_port 127.0.0.1 ${GAMA_WS_PORT} && echo "[rootfs] GAMA WebSocket ready (attempt ${i})" && break
        [ $i -eq 120 ] && echo "[rootfs] WARNING: GAMA WebSocket not detected"
        sleep 1
    done
fi

# Install websockets for bridge<->GAMA connectivity
python3 -c "import websockets" 2>/dev/null || pip3 install -q websockets 2>/dev/null || true

# Start bridge server (if port 8080 is free)
if check_port 127.0.0.1 ${BACKEND_PORT}; then
    echo "[rootfs] Port ${BACKEND_PORT} already in use (health endpoint active), skipping bridge"
else
    echo "[rootfs] Starting HTTP bridge server on port ${BACKEND_PORT}..."
    if [ -f "${GAMA_HOME}/bridge-server.py" ]; then
        python3 "${GAMA_HOME}/bridge-server.py" \
            > /opt/gama/logs/bridge.log 2>&1 &
        BRIDGE_PID=$!
        echo "[rootfs] Bridge PID: ${BRIDGE_PID} (Python)"
    elif [ -f "${GAMA_HOME}/gama-backend.jar" ]; then
        java ${JAVA_OPTS:-} -jar "${GAMA_HOME}/gama-backend.jar" \
            --port=${BACKEND_PORT} --workspace=/workspace \
            --log=/opt/gama/logs/backend.log &
        BRIDGE_PID=$!
        echo "[rootfs] Bridge PID: ${BRIDGE_PID} (Java)"
    else
        echo "[rootfs] No bridge server found"
    fi

    if [ -n "${BRIDGE_PID:-}" ]; then
        for i in $(seq 1 30); do
            check_port 127.0.0.1 ${BACKEND_PORT} && echo "[rootfs] Bridge server ready!" && break
            sleep 1
        done
    fi
fi

echo "[rootfs] Backend initialization complete"
echo "[rootfs] REST API:  http://127.0.0.1:${BACKEND_PORT}"
echo "[rootfs] WS (GAMA): ws://127.0.0.1:${GAMA_WS_PORT}"
echo "[rootfs] Logs:      /opt/gama/logs/"

# Monitor processes
while true; do
    sleep 5
    if [ -n "${BRIDGE_PID:-}" ] && ! kill -0 ${BRIDGE_PID} 2>/dev/null; then
        echo "[rootfs] Bridge process died"
    fi
    if [ "${GAMA_AVAILABLE}" = true ] && [ -n "${GAMA_PID:-}" ] && ! kill -0 ${GAMA_PID} 2>/dev/null; then
        echo "[rootfs] GAMA process died"
    fi
done
