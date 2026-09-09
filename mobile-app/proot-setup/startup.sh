#!/bin/bash

# GAMA Mobile startup — Ubuntu PRoot
# Commands mirror c.sh + u.sh pattern, with VNC fallback

unset LD_PRELOAD
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-arm64
export PATH=$JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/data
export USER=shell
export TMPDIR=/tmp
export VNC_PORT=5901
export VNC_WIDTH=960
export VNC_HEIGHT=540

# From u.sh / c.sh
export DISPLAY=:0
export GDK_BACKEND=x11

# Software OpenGL via Mesa llvmpipe (only option inside PRoot on Android)
# Zink/Vulkan not viable: Turnip needs MSM DRM, Android HAL needs hwservicemanager
# OSMesa is also installed as a JOGL fallback (no X server needed).
export GALLIUM_DRIVER=llvmpipe
export LIBGL_ALWAYS_SOFTWARE=1
export MESA_GL_VERSION_OVERRIDE=3.3
export MESA_GLSL_VERSION_OVERRIDE=330

# Tell Mesa DRI loader where to find drivers and that /dev/dri may be missing
export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe
mkdir -p /opt/gama/logs /tmp /data /workspace 2>/dev/null

# Bridge server (REST API on 8081) for the RN app; the in-app proxy forwards
# 8080 -> 8081. Unset the X-related LD_PRELOAD so Python's loader stays clean.
( unset LD_PRELOAD
  nohup env BACKEND_PORT=8081 python3 /opt/gama/bridge-server.py \
      >/opt/gama/logs/bridge.log 2>&1 &
  echo "[startup] Bridge server starting on 8081 (pid $!)"
)

# LD_PRELOAD shim for X servers and GAMA (hard link fix under PRoot)
if [ -f /opt/gama/override_link.so ]; then
  export LD_PRELOAD=/opt/gama/override_link.so
fi

echo "[startup] Ubuntu PRoot starting"
java -version 2>&1 | head -1 || echo "[startup] Java not found"

# From c.sh: dbus + /dev/shm (with fallbacks for PRoot)
service dbus start 2>/dev/null || dbus-daemon --system --fork 2>/dev/null || true
mkdir -p /dev/shm 2>/dev/null || true
chmod 777 /dev/shm 2>/dev/null || true

# Ensure DNS works inside PRoot for apt-get fallback
if ! grep -qs nameserver /etc/resolv.conf 2>/dev/null; then
  echo "nameserver 8.8.8.8" > /etc/resolv.conf 2>/dev/null || true
  echo "nameserver 8.8.4.4" >> /etc/resolv.conf 2>/dev/null || true
fi

# X server + VNC: Xvfb (patched, preplaced keymap) + x11vnc -noshm.
# Single-instance guard: kill any stale Xvfb/x11vnc left by a previous boot
# (keep-alive relaunch loops can otherwise pile up servers).
pkill -f 'Xvfb :0' 2>/dev/null || true
pkill -x x11vnc 2>/dev/null || true
rm -f /tmp/.X0-lock /tmp/.X11-unix/X0 2>/dev/null || true
mkdir -p /tmp/.X11-unix 2>/dev/null || true

# Create /dev/dri for Mesa DRI loader (llvmpipe checks this path)
mkdir -p /dev/dri 2>/dev/null || true

X_SERVER_RUNNING=false

X_SERVER_LOG=/opt/gama/logs/xserver.log

# Pre-place compiled keymap so the server's in-process xkbcomp is a no-op
# (server loads /var/lib/xkb/server-<display>.xkm and unlinks it, so re-do every boot)
mkdir -p /usr/share/X11/xkb/keymap /usr/share/X11/xkb/compiled /var/lib/xkb/compiled /var/lib/xkb /tmp/compiled 2>/dev/null
if [ ! -s /opt/gama/default.xkm ]; then
  cat > /tmp/kb.xkb <<'END'
xkb_keymap {
  xkb_keycodes { include "evdev+aliases(qwerty)" };
  xkb_types    { include "complete" };
  xkb_compat   { include "complete" };
  xkb_symbols  { include "pc+us+inet(evdev)" };
};
END
  xkbcomp -w 2 -I/usr/share/X11/xkb -R/usr/share/X11/xkb -xkm -o /usr/share/X11/xkb/compiled/xfree86 /tmp/kb.xkb >>$X_SERVER_LOG 2>&1
  cp /usr/share/X11/xkb/compiled/xfree86 /opt/gama/default.xkm 2>/dev/null
fi
cp /opt/gama/default.xkm /var/lib/xkb/server-0.xkm 2>/dev/null
cp /opt/gama/default.xkm /var/lib/xkb/compiled/server-0.xkm 2>/dev/null
cp /opt/gama/default.xkm /tmp/compiled/server-0.xkm 2>/dev/null
chmod 666 /var/lib/xkb/server-0.xkm 2>/dev/null
echo "[startup] preplaced /var/lib/xkb/server-0.xkm: $(ls -la /var/lib/xkb/server-0.xkm 2>&1)" >>$X_SERVER_LOG

# Xvfb is the sole X server: its in-process xkbcomp is bypassed by the
# pre-placed /var/lib/xkb/server-0.xkm + NOP'd checks in the binary, and
# override_link.so shims the hard-link failure it hits under PRoot.
start_xfb() {
  echo "[startup] Starting Xvfb on display $DISPLAY..."
  : > $X_SERVER_LOG
  LD_PRELOAD=/opt/gama/override_link.so \
  XKB_CONFIG_ROOT=/usr/share/X11/xkb \
  Xvfb $DISPLAY -screen 0 ${VNC_WIDTH}x${VNC_HEIGHT}x24 -pixdepths 8 16 24 32 \
    -noreset +extension GLX +extension RENDER +extension COMPOSITE \
    >>$X_SERVER_LOG 2>&1 &
  XVFB_PID=$!
  for i in $(seq 1 30); do
    kill -0 $XVFB_PID 2>/dev/null || break
    sleep 1
  done
  if kill -0 $XVFB_PID 2>/dev/null; then
    echo "[startup] Xvfb running (PID $XVFB_PID)"
    X_SERVER_RUNNING=true
  else
    echo "[startup] Xvfb failed, tail of log:"
    tail -20 $X_SERVER_LOG 2>/dev/null || true
  fi
}

start_xfb

if [ "$X_SERVER_RUNNING" = true ]; then
  echo "[startup] Starting x11vnc on port $VNC_PORT (with relaunch loop)..."
  (
    while [ -d /tmp/.X11-unix ]; do
      if ! pgrep -x x11vnc >/dev/null 2>&1; then
        echo "[x11vnc] launching $(date +%H:%M:%S)" >>/opt/gama/logs/x11vnc.log
        x11vnc -display $DISPLAY -forever -shared -nopw -noxdamage \
          -noshm -localhost -rfbport $VNC_PORT >/opt/gama/logs/x11vnc.err 2>&1
        echo "[x11vnc] exited rc=$? $(date +%H:%M:%S)" >>/opt/gama/logs/x11vnc.log
      fi
      sleep 5
    done
  ) &
fi


# Wait for VNC port (up to 60s)
echo "[startup] Waiting for VNC on port $VNC_PORT..."
for i in $(seq 1 60); do
  python3 -c "import socket; s=socket.socket(); s.settimeout(2); s.connect(('127.0.0.1', $VNC_PORT)); s.close()" 2>/dev/null \
    && echo "[startup] VNC ready! (attempt $i)" && break
  [ $i -eq 60 ] && echo "[startup] VNC not ready after 60s"
  sleep 1
done

# From c.sh: openbox
echo "[startup] Starting openbox..."
pgrep openbox | openbox &

# GLX diagnostic: log available OpenGL info before launching GAMA
echo "[startup] === OpenGL/GLX diagnostic ==="
if command -v glxinfo &>/dev/null; then
  LIBGL_DEBUG=verbose glxinfo -B 2>&1 | head -30 || echo "[startup] glxinfo failed"
else
  echo "[startup] glxinfo not available"
fi
if command -v eglinfo &>/dev/null; then
  eglinfo -B 2>&1 | head -20 || echo "[startup] eglinfo failed"
fi
echo "[startup] LIBGL_ALWAYS_SOFTWARE=$LIBGL_ALWAYS_SOFTWARE"
echo "[startup] GALLIUM_DRIVER=$GALLIUM_DRIVER"
echo "[startup] === end diagnostic ==="

# From c.sh: cd gama && ./Gama
GAMA_HOME=/opt/gama
# JOGL common args to force software rendering / avoid GLX issues.
# NOTE: -Dnativewindow.ws.name=x11 must NOT be set: it makes NEWT resolve the
# x11.DisplayDriver class from the gama.ui.display.opengl bundle classloader,
# which fails (ClassNotFoundException) and breaks the 3D display.
JOGL_ARGS="-Djogamp.gluegen.UseTempJarCache=false \
  -Djogamp.opengl.GLContext.nativeGL2=1 \
  -Djava.awt.headless=false"

# JOGL resolves native libs from GAMA_HOME/natives/<os>-<arch>, but the aarch64
# natives jars only materialize in the OSGi cache at first run. Extract the .so
# files so the 3D display can initialize under software GL (llvmpipe).
extract_jogl_natives() {
  local dst=/opt/gama/natives/linux-aarch64
  [ -f "$dst/libgluegen_rt.so" ] && [ -f "$dst/libjogl_desktop.so" ] && return 0
  mkdir -p "$dst"
  # The JOGL natives jars are EMBEDDED inside the shipped OpenGL plugin jar
  # (present from the very first boot, unlike the OSGi cache which only
  # materializes after GAMA has run). Read them straight out of the plugin and
  # flatten their natives/linux-aarch64/*.so into $dst. unzip is not guaranteed
  # inside the guest, so use python3 (shipped with the rootfs).
  local plugin
  plugin=$(ls /opt/gama/plugins/gama.ui.display.opengl_*.jar 2>/dev/null | head -1)
  if [ -n "$plugin" ]; then
    python3 - "$plugin" "$dst" <<'PY'
import sys, zipfile, os, io
plugin, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(plugin) as pz:
    nested = [n for n in pz.namelist()
              if n.startswith('lib/') and n.endswith('-natives-linux-aarch64.jar')]
    for nj in nested:
        nz = zipfile.ZipFile(io.BytesIO(pz.read(nj)))
        for n in nz.namelist():
            if n.endswith('.so') and '/' in n:
                out = os.path.join(dst, os.path.basename(n))
                with nz.open(n) as src, open(out, 'wb') as f:
                    f.write(src.read())
                os.chmod(out, 0o755)
                sys.stderr.write(f"[natives] {out}\n")
PY
  fi
  chmod 755 "$dst"/*.so 2>/dev/null
  if [ -f "$dst/libgluegen_rt.so" ] && [ -f "$dst/libjogl_desktop.so" ]; then
    echo "[startup] JOGL natives ready in $dst"
  else
    echo "[startup] WARN: JOGL natives still missing in $dst"
  fi
  return 0
}
extract_jogl_natives

# Helper: launch GAMA with LD_PRELOAD for hard link fix
run_gama() {
  cd "$GAMA_HOME"
  if [ -f /opt/gama/override_link.so ]; then
    LD_PRELOAD=/opt/gama/override_link.so \
    DISPLAY=:0 ./Gama -vmargs \
      -Dosgi.locking=none \
      -Dorg.eclipse.core.resources.disable.workspace.locking=true \
      $JOGL_ARGS &>/opt/gama/logs/gama.log 2>&1
  else
    DISPLAY=:0 ./Gama -vmargs \
      -Dosgi.locking=none \
      -Dorg.eclipse.core.resources.disable.workspace.locking=true \
      $JOGL_ARGS &>/opt/gama/logs/gama.log 2>&1
  fi
}

if [ -f "$GAMA_HOME/Gama" ]; then
  echo "[startup] Launching GAMA..."
  run_gama &
  GAMA_PID=$!
  echo "[startup] GAMA PID: $GAMA_PID"
else
  echo "[startup] GAMA binary not found at $GAMA_HOME"
fi

# Keep alive
while true; do
  sleep 5
  if [ -n "$GAMA_PID" ] && ! kill -0 $GAMA_PID 2>/dev/null; then
    echo "[startup] GAMA died, restarting..."
    run_gama &
    GAMA_PID=$!
    echo "[startup] GAMA restarted with PID $GAMA_PID"
  fi
done
