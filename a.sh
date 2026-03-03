
pkg update && pkg install proot-distro
proot-distro install ubuntu
proot-distro login ubuntu

apt update && apt install openjdk-21-jdk libgtk-3-0 libx11-6 libxtst6 libxrender1 libfontconfig1

curl -L https://github.com/hqnghi88/agama/releases/download/untagged-cb5ae4f6e67482042127/gama.application-linux.gtk.aarch64.tar.gz | tar -xz
 