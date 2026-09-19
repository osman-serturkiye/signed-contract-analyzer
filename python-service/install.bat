@echo off
setlocal enabledelayedexpansion

echo ============================================================
echo  Signed Contract Analyzer - Python Microservice Installer
echo ============================================================
echo.

REM --- Python kontrolu ---
python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python bulunamadi. Lutfen Python 3.10+ yukleyin.
    echo         https://www.python.org/downloads/
    pause
    exit /b 1
)
for /f "tokens=2" %%v in ('python --version 2^>^&1') do set PYVER=%%v
echo [OK] Python %PYVER% bulundu.

REM --- pip kontrolu ---
pip --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] pip bulunamadi.
    pause
    exit /b 1
)
echo [OK] pip bulundu.

REM --- Virtualenv olustur ---
echo.
echo [1/5] Sanal ortam olusturuluyor (venv)...
if exist venv (
    echo [SKIP] venv zaten mevcut, atlanıyor.
) else (
    python -m venv venv
    if errorlevel 1 (
        echo [ERROR] venv olusturulamadi.
        pause
        exit /b 1
    )
    echo [OK] venv olusturuldu.
)

REM --- Activate ---
call venv\Scripts\activate.bat

REM --- pip guncelle ---
echo.
echo [2/5] pip guncelleniyor...
python -m pip install --upgrade pip --quiet
echo [OK] pip guncellendi.

REM --- Temel bagimliliklar ---
echo.
echo [3/5] Temel bagimliliklar yukleniyor...
pip install fastapi==0.115.6 uvicorn[standard]==0.32.1 python-multipart==0.0.20 ^
    httpx==0.28.1 python-dotenv==1.0.1 markdown==3.7 --quiet
if errorlevel 1 (
    echo [ERROR] Temel bagimliliklar yuklenemedi.
    pause
    exit /b 1
)
echo [OK] Temel bagimliliklar yuklendi.

REM --- PaddleOCR ---
echo.
echo [4/5] PaddleOCR (PP-StructureV3) yukleniyor...
echo      Bu adim uzun surebilir (model dosyalari dahil)...
pip install paddlepaddle==3.0.0 --quiet
if errorlevel 1 (
    echo [WARN] paddlepaddle yuklenemedi. GPU versiyonu icin: pip install paddlepaddle-gpu
)
pip install paddleocr==2.10.0 --quiet
if errorlevel 1 (
    echo [WARN] paddleocr yuklenemedi.
)
echo [OK] PaddleOCR yuklendi (veya uyari kontrol edin).

REM --- Surya OCR ---
echo.
echo [5/5] Surya OCR yukleniyor...
echo      Bu adim da uzun surebilir (model dosyalari dahil)...
pip install surya-ocr==0.8.0 --quiet
if errorlevel 1 (
    echo [WARN] surya-ocr yuklenemedi.
)
echo [OK] Surya OCR yuklendi (veya uyari kontrol edin).

REM --- WeasyPrint ---
echo.
echo [+] WeasyPrint yukleniyor (PDF donusumu icin)...
pip install weasyprint==62.3 --quiet
if errorlevel 1 (
    echo [WARN] WeasyPrint yuklenemedi.
    echo        GTK runtime gerekebilir: https://github.com/tschoonj/GTK-for-Windows-Runtime-Environment-Installer
)
echo [OK] WeasyPrint yuklendi (veya uyari kontrol edin).

REM --- .env olustur ---
echo.
if not exist .env (
    echo [+] .env dosyasi olusturuluyor...
    (
        echo # Python OCR + Report Microservice Konfigurasyonu
        echo SERVICE_HOST=0.0.0.0
        echo SERVICE_PORT=8765
        echo SERVICE_API_KEY=change-me-secret-key
        echo LOG_LEVEL=INFO
    ) > .env
    echo [OK] .env olusturuldu. Lutfen SERVICE_API_KEY degerini degistirin!
) else (
    echo [SKIP] .env zaten mevcut.
)

echo.
echo ============================================================
echo  Kurulum tamamlandi!
echo  Servisi baslatmak icin: start-service.bat
echo ============================================================
echo.
pause
