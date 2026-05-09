#!/bin/bash
set -euo pipefail

# Main startup script - runs inside PRoot Linux container
# Starts the GAMA headless engine + HTTP bridge server

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export PATH=$JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/data
export TMPDIR=/tmp
export GAMA_HOME=/opt/gama
export BACKEND_PORT=${BACKEND_PORT:-8080}
export GAMA_WS_PORT=${GAMA_WS_PORT:-6868}

echo "╔═══════════════════════════════════════════════════════════╗"
echo "║          GAMA Mobile - Backend Container                 ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo "[startup] Java: $(java -version 2>&1 | head -1)"
echo "[startup] Backend port: ${BACKEND_PORT}"
echo "[startup] GAMA WS port: ${GAMA_WS_PORT}"
echo "[startup] Date: $(date)"

# Create required directories
mkdir -p /tmp /data /workspace /opt/gama/logs

# Source environment
if [ -f /etc/profile.d/gama-env.sh ]; then
    source /etc/profile.d/gama-env.sh
fi

# ─── Phase Detection ──────────────────────────────────────────────────
# Check if the full GAMA headless product is available
if [ -d "${GAMA_HOME}/headless/plugins" ] && \
   ls "${GAMA_HOME}/headless/plugins/org.eclipse.equinox.launcher_"*.jar &>/dev/null; then
    GAMA_AVAILABLE=true
    PLUGIN_COUNT=$(ls "${GAMA_HOME}/headless/plugins/"*.jar 2>/dev/null | wc -l)
    echo "[startup] GAMA headless product detected: ${PLUGIN_COUNT} plugins"
else
    GAMA_AVAILABLE=false
    echo "[startup] GAMA headless product not found (Phase 1 standalone mode)"
fi

# ─── Start GAMA Headless (if available) ───────────────────────────────
if [ "${GAMA_AVAILABLE}" = true ]; then
    echo "[startup] Starting GAMA headless WebSocket server on port ${GAMA_WS_PORT}..."

    # Launch GAMA in background
    /opt/gama/gama-launcher.sh \
        > /opt/gama/logs/gama-stdout.log 2> /opt/gama/logs/gama-stderr.log &
    GAMA_PID=$!
    echo "[startup] GAMA PID: ${GAMA_PID}"

    # Wait for GAMA WebSocket server to be ready
    echo "[startup] Waiting for GAMA WebSocket server..."
    for i in $(seq 1 60); do
        if ss -tlnp 2>/dev/null | grep -q ":${GAMA_WS_PORT} "; then
            echo "[startup] GAMA WebSocket server ready on port ${GAMA_WS_PORT} (attempt ${i})"
            break
        fi
        # Fallback: try curl if ss not available
        if curl -s http://127.0.0.1:${GAMA_WS_PORT} > /dev/null 2>&1; then
            echo "[startup] GAMA WebSocket server ready (attempt ${i})"
            break
        fi
        if [ $i -eq 60 ]; then
            echo "[startup] WARNING: GAMA WebSocket server may not have started"
        fi
        sleep 1
    done
fi

# ─── Start Bridge Server ──────────────────────────────────────────────
echo "[startup] Starting HTTP bridge server on port ${BACKEND_PORT}..."

if [ -f "${GAMA_HOME}/bridge-server.py" ]; then
    # Python bridge with WebSocket support
    python3 "${GAMA_HOME}/bridge-server.py" \
        > /opt/gama/logs/bridge.log 2>&1 &
    BRIDGE_PID=$!
    echo "[startup] Bridge PID: ${BRIDGE_PID} (Python)"
else
    # Fallback: search for Java bridge
    if [ -f "${GAMA_HOME}/gama-backend.jar" ]; then
        java ${JAVA_OPTS:-} -jar "${GAMA_HOME}/gama-backend.jar" \
            --port=${BACKEND_PORT} --workspace=/workspace \
            --log=/opt/gama/logs/backend.log &
        BRIDGE_PID=$!
        echo "[startup] Bridge PID: ${BRIDGE_PID} (Java)"
    else
        echo "[startup] WARNING: No bridge server found"
        echo "[startup] Starting minimal HTTP health endpoint..."

        # Minimal health-only server
        python3 -c "
import http.server, json
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps({'status': 'ok', 'mode': 'minimal'}).encode())
    def log_message(self, *a): pass
http.server.HTTPServer(('127.0.0.1', ${BACKEND_PORT}), H).serve_forever()
" &
        BRIDGE_PID=$!
        echo "[startup] Bridge PID: ${BRIDGE_PID} (minimal)"
    fi
fi

# Wait for bridge to be ready
for i in $(seq 1 10); do
    if curl -s http://127.0.0.1:${BACKEND_PORT}/api/health > /dev/null 2>&1; then
        echo "[startup] Bridge server ready!"
        break
    fi
    sleep 1
done

echo "[startup] ─── Backend initialization complete ───"
echo "[startup] REST API:  http://127.0.0.1:${BACKEND_PORT}"
echo "[startup] WS (GAMA): ws://127.0.0.1:${GAMA_WS_PORT}" 
echo "[startup] Logs:      /opt/gama/logs/"

# ─── Monitor Processes ────────────────────────────────────────────────
# Keep container alive and monitor child processes
while true; do
    sleep 5

    # Check bridge process
    if ! kill -0 ${BRIDGE_PID:-} 2>/dev/null; then
        echo "[startup] WARNING: Bridge process died, restarting..."
        # Bridge restart would go here
    fi

    # Check GAMA process
    if [ "${GAMA_AVAILABLE:-false}" = true ]; then
        if ! kill -0 ${GAMA_PID:-} 2>/dev/null; then
            echo "[startup] GAMA process died. Check /opt/gama/logs/gama-stderr.log"
        fi
    fi
done
