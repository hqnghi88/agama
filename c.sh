cd /data/data/com.termux/files/home/gama

# Download and extract GAMA product if not already present
if [ ! -f ./Gama ]; then
  apt install libgl1 libglx-mesa0 libegl1 freeglut3-dev libxcursor1 libxrandr2 libxxf86vm1 x11-xserver-utils -y

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
export GALLIUM_DRIVER=llvmpipe
export LIBGL_ALWAYS_SOFTWARE=1

# Install VNC server if not available (prefer tigervnc for RANDR support)
if ! command -v tigervncserver &>/dev/null && ! command -v vncserver &>/dev/null && ! command -v tightvncserver &>/dev/null; then
  echo "[c.sh] VNC server not found, installing tigervnc..."
  apt-get update -qq 2>/dev/null
  printf '\n\n' | apt-get install -y -qq tigervnc-standalone-server 2>/dev/null || \
  printf '\n\n' | apt-get install -y -qq tightvncserver 2>/dev/null
ficur

# Determine VNC server binary name
VNC_BIN=$(command -v tigervncserver || command -v tightvncserver || command -v vncserver || echo "")
if [ -z "${VNC_BIN}" ]; then
  echo "[c.sh] ERROR: No VNC server available"
  exit 1
fi

# vncpasswd path
VNC_PASSWD=$(command -v vncpasswd || echo "")
mkdir -p /data/.vnc
if [ -n "${VNC_PASSWD}" ]; then
  echo "123456" | ${VNC_PASSWD} -f > /data/.vnc/passwd 2>/dev/null
fi
chmod 600 /data/.vnc/passwd 2>/dev/null

# Pre-create tigervnc config dir to avoid migration error
mkdir -p /data/.config/tigervnc
cp /data/.vnc/passwd /data/.config/tigervnc/passwd 2>/dev/null || true
chmod 600 /data/.config/tigervnc/passwd 2>/dev/null

# Kill any previous VNC session on :1 and clean lock files
${VNC_BIN} -kill :1 2>/dev/null || true
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null || true
mkdir -p /tmp/.X11-unix 2>/dev/null || true

echo "[c.sh] Starting VNC server (${VNC_BIN}) on display :1..."
${VNC_BIN} :1 -geometry 1280x720 -depth 24 2>&1

# Wait for X socket to appear (up to 10s)
echo "[c.sh] Waiting for X display :1..."
for i in $(seq 1 10); do
  if [ -e /tmp/.X11-unix/X1 ]; then
    echo "[c.sh] X display :1 ready (attempt ${i})"
    break
  fi
  sleep 1
done

# Run openbox if not already running
pgrep openbox || openbox &
 
echo "[c.sh] Launching GAMA..."
./Gama
