@echo off
cd /d "%~dp0"
echo Starting AI Bot Server...
echo.
python -m uvicorn app:app --host 0.0.0.0 --port 8080 --reload
pause
