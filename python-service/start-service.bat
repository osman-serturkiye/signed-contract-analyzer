@echo off
setlocal enabledelayedexpansion

echo ============================================================
echo  Signed Contract Analyzer - Python Microservice
echo ============================================================
echo.

REM --- venv kontrolu ---
if not exist venv\Scripts\activate.bat (
    echo [ERROR] Sanal ortam (venv) bulunamadi.
    echo         Lutfen once install.bat calistirin.
    pause
    exit /b 1
)

REM --- .env kontrolu ---
if not exist .env (
    echo [ERROR] .env dosyasi bulunamadi.
    echo         Lutfen once install.bat calistirin.
    pause
    exit /b 1
)

REM --- main.py kontrolu ---
if not exist main.py (
    echo [ERROR] main.py bulunamadi.
    echo         Python servis dosyasi eksik.
    pause
    exit /b 1
)

REM --- .env'den port oku ---
set SERVICE_PORT=8765
for /f "usebackq tokens=1,2 delims==" %%a in (".env") do (
    if "%%a"=="SERVICE_PORT" set SERVICE_PORT=%%b
)

REM --- API key uyarisi ---
set API_KEY_VALUE=
for /f "usebackq tokens=1,2 delims==" %%a in (".env") do (
    if "%%a"=="SERVICE_API_KEY" set API_KEY_VALUE=%%b
)
if "!API_KEY_VALUE!"=="change-me-secret-key" (
    echo [WARN] SERVICE_API_KEY hala varsayilan deger!
    echo        .env dosyasini duzenleyerek guvenceli bir anahtar belirleyin.
    echo.
)

REM --- Activate ---
call venv\Scripts\activate.bat

echo [OK] Sanal ortam aktive edildi.
echo [>>] Servis baslatiliyor: http://localhost:%SERVICE_PORT%
echo [>>] Endpoint listesi:
echo       /ocr/paddleocr   - PaddleOCR PP-StructureV3
echo       /ocr/surya       - Surya OCR 2
echo       /report/html     - HTML rapor uretimi
echo       /report/pdf      - PDF rapor uretimi
echo.
echo      Durdurmak icin: Ctrl+C
echo ============================================================
echo.

python -m uvicorn main:app --host 0.0.0.0 --port %SERVICE_PORT% --reload

if errorlevel 1 (
    echo.
    echo [ERROR] Servis baslatılamadi. Hata kodunu kontrol edin.
    pause
)
