
pkg update && pkg install proot-distro
pkg install x11-repo
pkg install termux-x11-nightly
curl -L https://github.com/hqnghi88/agama/releases/download/draft-20260303080535/gama.application-linux.gtk.aarch64.tar.gz | tar -xz

proot-distro install ubuntu
proot-distro login ubuntu
