#!/bin/bash
set -e

echo "====================================="
echo "   GAMA Cloud SaaS Deploy Script     "
echo "====================================="

# Check if running as root
if [ "$EUID" -ne 0 ]
  then echo "Please run as root (e.g. sudo ./deploy.sh)"
  exit
fi

echo "[1/4] Installing Docker and dependencies..."
apt-get update
apt-get install -y ca-certificates curl gnupg
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
chmod a+r /etc/apt/keyrings/docker.gpg
echo \
  "deb [arch="$(dpkg --print-architecture)" signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  "$(. /etc/os-release && echo "$VERSION_CODENAME")" stable" | \
  tee /etc/apt/sources.list.d/docker.list > /dev/null
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

echo "[2/4] Pulling GAMA base image..."
docker pull gamaplatform/gama:latest

echo "[3/4] Building Orchestrator container..."
docker compose build

echo "[4/4] Starting Orchestrator..."
docker compose up -d

echo "====================================="
echo " Deployment Complete! "
echo " Access the SaaS at: http://<YOUR_SERVER_IP>:3000"
echo " Ensure ports 3000 and 6868-6878 are open in your firewall."
echo "====================================="
