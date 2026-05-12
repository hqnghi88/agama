#!/bin/bash
# Rootfs startup.sh - runs inside PRoot Linux container
# Starts VNC server + full GAMA GUI for remote display via VNC viewer

export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-arm64
export PATH=$JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/data
export TMPDIR=/tmp
export GAMA_HOME=/opt/gama
export DISPLAY=:1
export VNC_PORT=5901

mkdir -p /tmp /data /workspace /opt/gama/logs /data/.vnc 2>/dev/null

[ -f /etc/profile.d/gama-env.sh ] && source /etc/profile.d/gama-env.sh

echo "[rootfs] GAMA Mobile starting (VNC mode)"
java -version 2>&1 | head -1 || echo "[rootfs] Java not found"

# Detect GAMA product
if [ -f "${GAMA_HOME}/Gama" ]; then
    echo "[rootfs] Full GAMA GUI product found"
elif [ -f "${GAMA_HOME}/headless/Gama" ]; then
    echo "[rootfs] GAMA product found at headless/"
    GAMA_HOME="${GAMA_HOME}/headless"
else
    echo "[rootfs] GAMA product not found at ${GAMA_HOME}"
    GAMA_AVAILABLE=false
fi

# Set blank VNC password for dev
echo -n "" | /usr/bin/vncpasswd -f > /data/.vnc/passwd 2>/dev/null
chmod 600 /data/.vnc/passwd

echo "[rootfs] Starting VNC server on display :1 (port ${VNC_PORT})..."
tightvncserver :1 -geometry 1280x720 -depth 16 -localhost no -passwd /data/.vnc/passwd 2>&1

echo "[rootfs] Starting fluxbox window manager..."
fluxbox &>/opt/gama/logs/fluxbox.log 2>&1 &

echo "[rootfs] Starting GAMA GUI..."
cd "${GAMA_HOME}"
DISPLAY=:1 ./Gama -data /workspace &>/opt/gama/logs/gama.log 2>&1 &
GAMA_PID=$!
echo "[rootfs] GAMA PID: ${GAMA_PID}"

# Keep alive and monitor
while true; do
    sleep 5
    if ! kill -0 ${GAMA_PID} 2>/dev/null; then
        echo "[rootfs] GAMA process died, restarting..."
        DISPLAY=:1 ./Gama -data /workspace &>/opt/gama/logs/gama.log 2>&1 &
        GAMA_PID=$!
        echo "[rootfs] GAMA restarted with PID ${GAMA_PID}"
    fi
done
