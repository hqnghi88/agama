
pkg update && pkg install proot-distro

curl -L https://github.com/hqnghi88/agama/releases/download/untagged-cb5ae4f6e67482042127/gama.application-linux.gtk.aarch64.tar.gz | tar -xz

proot-distro install ubuntu
proot-distro login ubuntu
