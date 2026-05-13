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
export DISPLAY=:0
export GDK_BACKEND=x11
# Zink (OpenGL over Vulkan) for Adreno GPU
export MESA_LOADER_DRIVER_OVERRIDE=zink
export GALLIUM_DRIVER=zink
export ZINK_DESCRIPTORS=lazy
export TU_DEBUG=noconform

termux-x11 :0 & 
sleep 3

# Run openbox if not already running
pgrep openbox | openbox &

./Gama
