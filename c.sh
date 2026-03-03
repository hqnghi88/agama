cd /data/data/com.termux/files/home/gama
service dbus start
export SHARED_MEMORY_DIR=/dev/shm
mkdir -p $SHARED_MEMORY_DIR
chmod 777 $SHARED_MEMORY_DIR

export GDK_BACKEND=x11
termux-x11 :0 & 
sleep 6

# Run openbox if not already running
pgrep openbox | openbox &
export DISPLAY=: 0
./Gama
