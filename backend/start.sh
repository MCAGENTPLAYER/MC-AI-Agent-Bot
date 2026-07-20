#!/bin/bash
cd "$(dirname "$0")"
echo "Starting AI Bot Server..."
python -m uvicorn app:app --host 0.0.0.0 --port 8080 --reload
