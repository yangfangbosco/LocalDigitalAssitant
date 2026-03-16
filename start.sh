#!/bin/bash

echo "=== Starting Digital Human ==="

# 1. Start Ollama
echo "[1/2] Starting Ollama..."
brew services start ollama 2>/dev/null
sleep 3

# 2. Start FastAPI server
echo "[2/2] Starting backend server..."
cd "$(dirname "$0")/digital_human_server"
source venv/bin/activate
python main.py &
SERVER_PID=$!
echo $SERVER_PID > /tmp/digital_human_server.pid

sleep 3
echo ""
echo "=== Digital Human is running ==="
echo "Open http://localhost:8000 in your browser"
echo "To stop: ./stop.sh"
echo ""

wait $SERVER_PID
