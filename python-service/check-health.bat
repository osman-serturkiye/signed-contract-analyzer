@echo off
setlocal enabledelayedexpansion

echo ============================================================
echo  Signed Contract Analyzer - Servis Saglik Kontrolu
echo ============================================================
echo.

REM --- .env'den degerler oku ---
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
    echo [ERROR] curl bulunamadi.
    pause
    exit /b 1
)

REM --- /health (auth gerektirmez) ---
echo [1/6] /health kontrolu (auth gerektirmez)...
curl -s -o nul -w "HTTP Status: %%{http_code}" "%BASE_URL%/health"
echo.

REM --- /ocr/paddleocr (gecersiz key -> 401 beklenir) ---
echo.
echo [2/6] /ocr/paddleocr erisim kontrolu (401 beklenir)...
curl -s -o nul -w "HTTP Status: %%{http_code}" ^
     -H "Authorization: Bearer INVALID_KEY" ^
     -X POST "%BASE_URL%/ocr/paddleocr"
echo.

REM --- /image/crop (gecersiz key -> 401 beklenir) ---
echo.
echo [3/6] /image/crop erisim kontrolu (401 beklenir)...
curl -s -o nul -w "HTTP Status: %%{http_code}" ^
     -H "Authorization: Bearer INVALID_KEY" ^
     -X POST "%BASE_URL%/image/crop"
echo.

REM --- /image/stitch (gecersiz key -> 401 beklenir) ---
echo.
echo [4/6] /image/stitch erisim kontrolu (401 beklenir)...
curl -s -o nul -w "HTTP Status: %%{http_code}" ^
     -H "Authorization: Bearer INVALID_KEY" ^
     -X POST "%BASE_URL%/image/stitch"
echo.

REM --- /ai/compare (gecersiz key -> 401 beklenir) ---
echo.
echo [5/6] /ai/compare erisim kontrolu (401 beklenir)...
curl -s -o nul -w "HTTP Status: %%{http_code}" ^
     -H "Authorization: Bearer INVALID_KEY" ^
     -X POST "%BASE_URL%/ai/compare"
echo.

REM --- /report/html (gecersiz key -> 401 beklenir) ---
echo.
echo [6/6] /report/html erisim kontrolu (401 beklenir)...
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
