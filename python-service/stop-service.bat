@echo off
echo ============================================================
echo  Signed Contract Analyzer - Servis Durduruluyor
echo ============================================================
echo.

REM --- .env'den port oku ---
set SERVICE_PORT=8765
if exist .env (
    for /f "usebackq tokens=1,2 delims==" %%a in (".env") do (
        if "%%a"=="SERVICE_PORT" set SERVICE_PORT=%%b
    )
)

echo [>>] Port %SERVICE_PORT% uzerindeki uvicorn/python surecleri sonlandiriliyor...

REM uvicorn process'ini bul ve durdur
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":%SERVICE_PORT% " ^| findstr "LISTENING"') do (
    echo [>>] PID %%p sonlandiriliyor...
    taskkill /PID %%p /F >nul 2>&1
    echo [OK] PID %%p sonlandirildi.
)

REM Python main.py process'lerini de temizle (fallback)
taskkill /F /IM python.exe /FI "WINDOWTITLE eq *uvicorn*" >nul 2>&1

echo.
echo [OK] Servis durduruldu.
pause
