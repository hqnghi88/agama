#!/bin/bash
# GAMA Mobile VNC Launcher
# Starts VNC server + window manager + full GAMA GUI inside PRoot

export HOME=/data
export USER=root
export DISPLAY=:1
export VNC_PORT=5901

mkdir -p /data/.vnc /tmp /opt/gama/logs

# VNC password (blank for development)
echo -n "" | /usr/bin/vncpasswd -f > /data/.vnc/passwd 2>/dev/null
chmod 600 /data/.vnc/passwd

echo "[vnc] Starting VNC server on display :1 (port ${VNC_PORT})..."
tightvncserver :1 -geometry 1280x720 -depth 24 -localhost no -passwd /data/.vnc/passwd 2>&1

echo "[vnc] Starting fluxbox window manager..."
fluxbox &>/opt/gama/logs/fluxbox.log 2>&1 &

echo "[vnc] Starting GAMA GUI..."
cd /opt/gama
DISPLAY=:1 ./Gama &>/opt/gama/logs/gama.log 2>&1

echo "[vnc] GAMA exited"
tightvncserver -kill :1 2>/dev/null
