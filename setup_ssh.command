#!/bin/bash
KEY=~/.ssh/jetson_key

# Always generate a fresh dedicated key for Jetson
ssh-keygen -t ed25519 -C "mac-to-jetson" -N "" -f "$KEY" -f "$KEY" <<< y 2>/dev/null || \
ssh-keygen -t ed25519 -C "mac-to-jetson" -N "" -f "$KEY"

echo "Public key: $(cat ${KEY}.pub)"
echo ""
echo "Copying key to Jetson (will enter password once)..."

/usr/bin/expect << EOF
spawn ssh-copy-id -i ${KEY}.pub -o StrictHostKeyChecking=no ajmalrasi@192.168.3.30
expect "password:"
send "a\r"
expect eof
EOF

echo ""
echo "Testing passwordless SSH..."
ssh -i "$KEY" -o StrictHostKeyChecking=no ajmalrasi@192.168.3.30 "echo 'SUCCESS: logged in as' \$(whoami) 'on' \$(hostname)"
