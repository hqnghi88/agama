
export DISPLAY=: 0
pkg install termux-x11-shell

termux-x11 :0 &
cd /path/to/agama/gama.product/target/products/gama.ui.application.product/linux/gtk/aarch64
 

apt update && apt install dbus-x11 -y 
service dbus start

apt instal openbox -y

export SHARED_MEMORY_DIR=/dev/shm
mkdir -p $SHARED_MEMORY_DIR
chmod 777 $SHARED_MEMORY_DIR

export DISPLAY=: 0
export LIBGL_ALWAYS_SOFTWARE=1
export GDK_ BACKEND=x11
export SHARED_MEMORY_DIR=/dev/shm
# Run openbox if not already running
pgrep openbox | openbox &

GDK_BACKEND=x11 ./Gama
