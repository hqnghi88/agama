termux:
https://github.com/termux/termux-app/releases
termux-x11:
https://github.com/termux/termux-x11/releases/tag/nightly

in termux:
curl -sL https://raw.githubusercontent.com/hqnghi88/agama/main/a.sh | bash


in ubuntu:

curl -sL https://raw.githubusercontent.com/hqnghi88/agama/main/b.sh | bash

run Gama (then go to x11 app to interact with)

curl -sL https://raw.githubusercontent.com/hqnghi88/agama/main/c.sh | bash


OR 


in termux:
apt update && apt upgrade
apt install git
git clone https://github.com/hqnghi88/agama.git 


chmod +x a.sh b.sh
./a.sh



in ubuntu:

cd /data/data/com.termux/files/home/agama
./b.sh

