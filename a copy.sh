export LAUNCHER_JAR=$(ls plugins/org.eclipse.equinox.launcher_*.jar| head -n 1) 
java -jar $LAUNCHER_JAR \
-showsplash \
-application gama.ui.application.GamaApplication \
-product gama.ui.application.product



1. Install
'proot-distro' in Termux:
pkg update
&& pkg install proot-distro
2. Install
and log
into
a Linux
distribution
(e.g., Ubuntu):
install
ubuntu
2
proot-distro
proot-distro login ubuntu
3. Install dependencies inside the Ubuntu environment:
apt update
&& apt install openjdk-21-jdk libgtk-3-0 libx11-6 libxtst6 libxrender1 libfontconfig1
4. Launch
GAMA (ensure
server like Termux-X11
is running
and DISPLAY set):
export DISPLAY=: 0
cd/path/to/agama/gama.product/target/products/gama.ui.application.product/linux/gtk/aarch64



Follow these steps:
1. Setup in Termux (Outside Ubuntu)
First, ensure you have the termux-x11 package installed and start the server:
Chat
1 # Install
the package
if you haven't
pkg install termux-x11-shell
4 # Start the Termux-X11 server
termux-x11 :0 &
(Make sure the
Termux-X11 Android app is open
on your phone/tablet).
2. Enter Ubuntu with Shared Environment
Login to your Ubuntu distribution. proot-distro usually shares the /tmp directory by default, which is where the X11 socket lives.
1 proot-distro login ubuntu
3. Setup Inside Ubuntu
Once
inside
the Ubuntu shell, you must tell the applications use the
X11 server running
Termux: 


export DISPLAY=: 0

export SHARED_MEMORY_DIR=/dev/shm
mkdir -p $SHARED_MEMORY_DIR
chmod 777 $SHARED_MEMORY_DIR

apt update && apt install dbus-x11 -y 
service dbus start

apt instal openbox -y

export DISPLAY=: 0
export LIBGL_ALWAYS_SOFTWARE=1
export GDK_ BACKEND=x11
export SHARED_MEMORY_DIR=/dev/shm
# Run openbox if not already running
pgrep openbox | openbox &

GDK_BACKEND=x11 ./Gama
