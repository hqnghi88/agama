cd /data/data/com.termux/files/home/gama

# Download and extract GAMA product if not already present
if [ ! -f ./Gama ]; then
  echo "[c.sh] Downloading GAMA..."
  curl -L https://github.com/hqnghi88/agama/releases/download/draft-20260308070312/gama.application-linux.gtk.aarch64.tar.gz | tar -xz --no-same-permissions --no-same-owner
fi

service dbus start
export SHARED_MEMORY_DIR=/dev/shm
mkdir -p $SHARED_MEMORY_DIR
chmod 777 $SHARED_MEMORY_DIR
export DISPLAY=:1
export GDK_BACKEND=x11
export HOME=/data
# Zink (OpenGL over Vulkan) for Adreno GPU
export MESA_LOADER_DRIVER_OVERRIDE=zink
export GALLIUM_DRIVER=zink
export ZINK_DESCRIPTORS=lazy
export TU_DEBUG=noconform

# Install VNC server if not available (tigervnc in Termux)
if ! command -v vncserver &>/dev/null && ! command -v tightvncserver &>/dev/null; then
  echo "[c.sh] VNC server not found, installing tigervnc..."
  pkg install -y tigervnc 2>/dev/null
fi

# Determine VNC server binary name
VNC_BIN=$(command -v tightvncserver || command -v vncserver || echo "")
if [ -z "${VNC_BIN}" ]; then
  echo "[c.sh] ERROR: No VNC server available"
  exit 1
fi

# vncpasswd path (tigervnc vs tightvnc)
VNC_PASSWD=$(command -v vncpasswd || echo "")
mkdir -p /data/.vnc
if [ -n "${VNC_PASSWD}" ]; then
  echo -n "" | ${VNC_PASSWD} -f > /data/.vnc/passwd 2>/dev/null
fi
chmod 600 /data/.vnc/passwd 2>/dev/null

echo "[c.sh] Starting VNC server (${VNC_BIN}) on display :1..."
${VNC_BIN} :1 -geometry 1280x720 -depth 24 -localhost no -passwd /data/.vnc/passwd 2>&1
sleep 2

# Run openbox if not already running
pgrep openbox | openbox &

./Gama
