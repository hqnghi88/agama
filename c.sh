cd /data/data/com.termux/files/home/gama

if [ ! -f ./Gama ]; then
  apt install libgl1 libglx-mesa0 libegl1 freeglut3-dev libxcursor1 libxrandr2 libxxf86vm1 x11-xserver-utils tightvncserver -y

  echo "[c.sh] Downloading GAMA..."
  curl -L https://github.com/hqnghi88/agama/releases/download/draft-20260308070312/gama.application-linux.gtk.aarch64.tar.gz | tar -xz --no-same-permissions --no-same-owner
fi

service dbus start
export DISPLAY=:1
export HOME=/data

tightvncserver -kill :1 2>/dev/null || true
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null
mkdir -p /data/.vnc
echo "123456" | vncpasswd -f > /data/.vnc/passwd 2>/dev/null
chmod 600 /data/.vnc/passwd 2>/dev/null
tightvncserver :1 -geometry 1280x720 -depth 24 2>&1

for i in $(seq 1 10); do
  [ -e /tmp/.X11-unix/X1 ] && break
  sleep 1
done

export XLIB_SKIP_ARGB_VISUALS=1
xrandr --newmode "1280x720_60.00" 74.48 1280 1336 1472 1664 720 721 724 746 -hsync +vsync 2>/dev/null || true
xrandr --addmode screen 1280x720_60.00 2>/dev/null || true
xrandr --output screen --mode 1280x720_60.00 2>/dev/null || true

pgrep fluxbox || fluxbox &

echo "[c.sh] Launching GAMA..."
./Gama
