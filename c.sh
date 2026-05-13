cd /data/data/com.termux/files/home/gama
service dbus start
export SHARED_MEMORY_DIR=/dev/shm
mkdir -p $SHARED_MEMORY_DIR
chmod 777 $SHARED_MEMORY_DIR
export DISPLAY=:0
export GDK_BACKEND=x11


# Enable Zink (OpenGL over Vulkan)
export MESA_LOADER_DRIVER_OVERRIDE=zink
export GALLIUM_DRIVER=zink

# Optimize Zink for Adreno
export ZINK_DESCRIPTORS=lazy

# Bypass strict conformance checks for better performance on mobile GPUs
export TU_DEBUG=noconform

termux-x11 :0 & 
sleep 3

# Run openbox if not already running
pgrep fluxbox | fluxbox &

./Gama