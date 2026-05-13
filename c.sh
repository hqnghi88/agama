cd /data/data/com.termux/files/home/gama
service dbus start
export SHARED_MEMORY_DIR=/dev/shm
mkdir -p $SHARED_MEMORY_DIR
chmod 777 $SHARED_MEMORY_DIR
export DISPLAY=:0
export GDK_BACKEND=x11
termux-x11 :0 & 
sleep 3

# Run openbox if not already running
pgrep openbox | openbox &
 
export GALLIUM_DRIVER=llvmpipe
export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe
export LIBGL_ALWAYS_SOFTWARE=1

# 2. Version Overrides (Stability)
export MESA_GL_VERSION_OVERRIDE=3.3
export MESA_GLSL_VERSION_OVERRIDE=330

# 3. X11 Stability
export GDK_BACKEND=x11
export QT_X11_NO_MITSHM=1

# 4. Launch with JVM memory fixes
./Gama -vmargs -Dsun.java2d.opengl=false -XX:-UseCompressedOops