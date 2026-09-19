@echo off
setlocal enabledelayedexpansion

echo ============================================================
echo  Signed Contract Analyzer - Python Microservice
echo  v2.0 - OCR + AI + Image + Report
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

REM --- .env'den degerler oku ---
set SERVICE_PORT=8765
set SERVICE_API_KEY=change-me-secret-key
set LOG_LEVEL=INFO

for /f "usebackq tokens=1,2 delims==" %%a in (".env") do (
    if "%%a"=="SERVICE_PORT" set SERVICE_PORT=%%b
    if "%%a"=="SERVICE_API_KEY" set SERVICE_API_KEY=%%b
    if "%%a"=="LOG_LEVEL" set LOG_LEVEL=%%b
)

REM --- API key uyarisi ---
if "!SERVICE_API_KEY!"=="change-me-secret-key" (
    echo [WARN] SERVICE_API_KEY hala varsayilan deger!
    echo        .env dosyasini duzenleyerek guvenceli bir anahtar belirleyin.
    echo.
)

REM --- Activate ---
call venv\Scripts\activate.bat

echo [OK] Sanal ortam aktive edildi.
echo [OK] Log seviyesi: %LOG_LEVEL%
echo.
echo [>>] Servis baslatiliyor: http://localhost:%SERVICE_PORT%
echo.
echo [>>] Endpoint listesi:
echo       GET  /health            - Saglik kontrolu (auth gerektirmez)
echo       POST /ocr/paddleocr     - PaddleOCR PP-StructureV3
echo       POST /ocr/surya         - Surya OCR 2
echo       POST /ocr/azure         - Azure Cognitive Service OCR
echo       POST /ocr/foundry       - Azure Document Intelligence (Foundry)
echo       POST /ocr/mistral       - Mistral OCR
echo       POST /image/crop        - BBox ile goruntu kirpma (Pillow)
echo       POST /image/stitch      - Cok goruntu dikey birlestirme (Pillow)
echo       POST /ai/compare        - AI semantik karsilastirma
echo       POST /report/html       - HTML rapor uretimi (Jinja2)
echo       POST /report/pdf        - PDF rapor uretimi (WeasyPrint)
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
