#!/bin/bash
# Deploy ClawPhones Billing Proxy to GCP node
# Usage: bash deploy.sh [node-ssh-alias]
# Default: codex-node-1

set -e

NODE="${1:-codex-node-1}"
REMOTE_DIR="/opt/clawphones-billing"
SERVICE_NAME="clawphones-billing"

echo "=== Deploying ClawPhones Billing Proxy to $NODE ==="

# 1. Create remote directory
echo "[1/5] Setting up remote directory..."
ssh "$NODE" "sudo mkdir -p $REMOTE_DIR/data && sudo chown -R \$(whoami) $REMOTE_DIR"

# 2. Copy files
echo "[2/5] Copying files..."
scp server.py requirements.txt .env "$NODE:$REMOTE_DIR/"

# 3. Install Python deps
echo "[3/5] Installing dependencies..."
ssh "$NODE" "cd $REMOTE_DIR && python3 -m venv venv 2>/dev/null || true && ./venv/bin/pip install -q -r requirements.txt"

# 4. Create systemd service
echo "[4/5] Creating systemd service..."
ssh "$NODE" "sudo tee /etc/systemd/system/${SERVICE_NAME}.service > /dev/null << 'UNIT'
[Unit]
Description=ClawPhones Billing Proxy
After=network.target

[Service]
Type=simple
User=$(ssh $NODE whoami)
WorkingDirectory=$REMOTE_DIR
ExecStart=$REMOTE_DIR/venv/bin/uvicorn server:app --host 0.0.0.0 --port 8090
Restart=always
RestartSec=5
EnvironmentFile=$REMOTE_DIR/.env

[Install]
WantedBy=multi-user.target
UNIT"

ssh "$NODE" "sudo systemctl daemon-reload && sudo systemctl enable $SERVICE_NAME && sudo systemctl restart $SERVICE_NAME"

# 5. Verify
echo "[5/5] Verifying..."
sleep 2
ssh "$NODE" "curl -sf http://localhost:8090/health | python3 -m json.tool"

echo ""
echo "=== Deployed successfully ==="
echo "Internal: http://\$(ssh $NODE 'hostname -I | awk {print \$1}'):8090"
echo "Health:   ssh $NODE curl -s http://localhost:8090/health"
echo ""
echo "Next: Set up Caddy reverse proxy for HTTPS"
