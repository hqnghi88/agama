install termux:

https://github.com/termux/termux-app/releases

install termux-x11:

https://github.com/termux/termux-x11/releases/tag/nightly

1. in termux:

curl -sL https://raw.githubusercontent.com/hqnghi88/agama/main/a.sh | bash


2. in ubuntu (already logged in after a.sh, if it is not, run this: proot-distro login ubuntu) :

curl -sL https://raw.githubusercontent.com/hqnghi88/agama/main/b.sh | bash

3. run Gama (then go to x11 app to interact with Gama)

proot-distro login ubuntu -- bash -c "curl -sL https://raw.githubusercontent.com/hqnghi88/agama/main/c.sh | bash"


