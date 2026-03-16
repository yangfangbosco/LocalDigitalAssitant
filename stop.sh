#!/bin/bash

echo "=== Stopping Digital Human ==="

# 1. Stop FastAPI server
if [ -f /tmp/digital_human_server.pid ]; then
  PID=$(cat /tmp/digital_human_server.pid)
  if kill -0 "$PID" 2>/dev/null; then
    kill "$PID"
    echo "[1/2] Backend server stopped (PID $PID)"
  else
    echo "[1/2] Backend server not running"
  fi
  rm -f /tmp/digital_human_server.pid
else
  # Fallback: kill by port
  PID=$(lsof -ti:8000 2>/dev/null)
  if [ -n "$PID" ]; then
    kill $PID
    echo "[1/2] Backend server stopped (PID $PID)"
  else
    echo "[1/2] Backend server not running"
  fi
fi

# 2. Stop Ollama
echo "[2/2] Stopping Ollama..."
brew services stop ollama 2>/dev/null

echo "=== Digital Human stopped ==="
