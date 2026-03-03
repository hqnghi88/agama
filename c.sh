#pkg install termux-x11-shell

service dbus start
export DISPLAY=: 0
termux-x11 :0 & 
 
 

export SHARED_MEMORY_DIR=/dev/shm
mkdir -p $SHARED_MEMORY_DIR
chmod 777 $SHARED_MEMORY_DIR

export LIBGL_ALWAYS_SOFTWARE=1
export GDK_ BACKEND=x11
export SHARED_MEMORY_DIR=/dev/shm
# Run openbox if not already running
pgrep openbox | openbox &

GDK_BACKEND=x11 ./Gama
