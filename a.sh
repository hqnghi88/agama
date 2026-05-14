
termux-setup-storage
pkg update
pkg install proot-distro -y
pkg install x11-repo -y
pkg install termux-x11-nightly -y
mkdir gama
cd gama
curl -L https://github.com/hqnghi88/agama/releases/download/draft-20260308070312/gama.application-linux.gtk.aarch64.tar.gz | tar -xz

proot-distro install ubuntu
