@echo off
echo ============================================================
echo  Signed Contract Analyzer - Servis Saglik Kontrolu
echo ============================================================
echo.

REM --- .env'den port ve key oku ---
set SERVICE_PORT=8765
set SERVICE_API_KEY=change-me-secret-key
if exist .env (
    for /f "usebackq tokens=1,2 delims==" %%a in (".env") do (
        if "%%a"=="SERVICE_PORT" set SERVICE_PORT=%%b
        if "%%a"=="SERVICE_API_KEY" set SERVICE_API_KEY=%%b
    )
)

set BASE_URL=http://localhost:%SERVICE_PORT%

echo [>>] Hedef: %BASE_URL%
echo.

REM curl kontrolu
curl --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] curl bulunamadi. Windows 10+ ile birlikte gelmelidir.
    pause
    exit /b 1
)

REM /health endpoint
echo [1/3] /health kontrolu...
curl -s -o nul -w "HTTP Status: %%{http_code}" "%BASE_URL%/health"
echo.

REM /ocr/paddleocr endpoint (API key ile)
echo.
echo [2/3] /ocr/paddleocr erisim kontrolu (401 beklenir - gecersiz key)...
curl -s -o nul -w "HTTP Status: %%{http_code}" ^
     -H "Authorization: Bearer INVALID_KEY" ^
     -X POST "%BASE_URL%/ocr/paddleocr"
echo.

REM /report/html endpoint (API key ile)
echo.
echo [3/3] /report/html erisim kontrolu (401 beklenir - gecersiz key)...
curl -s -o nul -w "HTTP Status: %%{http_code}" ^
     -H "Authorization: Bearer INVALID_KEY" ^
     -X POST "%BASE_URL%/report/html"
echo.

echo.
echo ============================================================
echo  Saglik kontrolu tamamlandi.
echo  Beklenen: /health -> 200, diger endpoint'ler -> 401
echo ============================================================
pause
