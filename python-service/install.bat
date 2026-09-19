@echo off
setlocal enabledelayedexpansion

echo ============================================================
echo  Signed Contract Analyzer - Python Microservice Installer
echo  v2.0 - Tum dis servis entegrasyonlari bu servis uzerinden
echo ============================================================
echo.
echo  Bu servis asagidaki islevleri uzer:
echo   - OCR : PaddleOCR (PP-StructureV3), Surya OCR 2
echo            Azure Cognitive Service, Microsoft Foundry, Mistral OCR
echo   - AI  : OpenAI, Azure Foundry AI, Claude AI, OpenRouter
echo   - IMG : BBox kırpma (Pillow), cok sayfa birlestirme
echo   - RPT : HTML rapor (Jinja2), PDF donusum (WeasyPrint)
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
echo [1/7] Sanal ortam olusturuluyor (venv)...
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
echo [2/7] pip guncelleniyor...
python -m pip install --upgrade pip --quiet
echo [OK] pip guncellendi.

REM --- Temel bagimliliklar ---
echo.
echo [3/7] Temel bagimliliklar yukleniyor...
pip install fastapi==0.115.6 uvicorn[standard]==0.32.1 python-multipart==0.0.20 ^
    httpx==0.28.1 python-dotenv==1.0.1 markdown==3.7 ^
    jinja2==3.1.5 --quiet
if errorlevel 1 (
    echo [ERROR] Temel bagimliliklar yuklenemedi.
    pause
    exit /b 1
)
echo [OK] Temel bagimliliklar yuklendi.

REM --- Pillow (Image crop/stitch) ---
echo.
echo [4/7] Pillow (goruntu isleme: bbox kirpma + birlestirme) yukleniyor...
pip install Pillow==11.1.0 --quiet
if errorlevel 1 (
    echo [ERROR] Pillow yuklenemedi.
    pause
    exit /b 1
)
echo [OK] Pillow yuklendi.

REM --- PaddleOCR ---
echo.
echo [5/7] PaddleOCR (PP-StructureV3) yukleniyor...
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
echo [6/7] Surya OCR 2 yukleniyor...
pip install surya-ocr==0.8.0 --quiet
if errorlevel 1 (
    echo [WARN] surya-ocr yuklenemedi.
)
echo [OK] Surya OCR yuklendi (veya uyari kontrol edin).

REM --- AI SDK'lari ---
echo.
echo [+] AI SDK'lari yukleniyor (OpenAI, Anthropic, OpenRouter)...
pip install openai==1.59.6 anthropic==0.43.0 --quiet
if errorlevel 1 (
    echo [WARN] AI SDK'lardan biri yuklenemedi.
)
echo [OK] AI SDK'lari yuklendi (veya uyari kontrol edin).

REM --- Azure SDK'lari ---
echo.
echo [+] Azure Cognitive Service / Foundry SDK'lari yukleniyor...
pip install azure-ai-formrecognizer==3.3.3 azure-ai-vision-imageanalysis==1.0.0 --quiet
if errorlevel 1 (
    echo [WARN] Azure SDK'lardan biri yuklenemedi.
)
echo [OK] Azure SDK'lari yuklendi (veya uyari kontrol edin).

REM --- Mistral ---
echo.
echo [+] Mistral SDK yukleniyor...
pip install mistralai==1.3.0 --quiet
if errorlevel 1 (
    echo [WARN] mistralai yuklenemedi.
)
echo [OK] Mistral SDK yuklendi (veya uyari kontrol edin).

REM --- WeasyPrint (PDF donusumu) ---
echo.
echo [7/7] WeasyPrint yukleniyor (PDF donusumu icin)...
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
        echo # Python Microservice Konfigurasyonu
        echo # OCR + AI + Image + Report servisi
        echo.
        echo # Servis
        echo SERVICE_HOST=0.0.0.0
        echo SERVICE_PORT=8765
        echo SERVICE_API_KEY=change-me-secret-key
        echo LOG_LEVEL=INFO
        echo LOG_FORMAT=plain
        echo.
        echo # PDF donusumu (weasyprint veya playwright)
        echo USE_PLAYWRIGHT_FOR_PDF=false
        echo.
        echo # OCR - PaddleOCR
        echo PADDLE_LANG=tr
        echo.
        echo # OCR - Surya
        echo SURYA_MODEL_CACHE=
        echo.
        echo # OCR - Azure Cognitive Service
        echo AZURE_OCR_ENDPOINT=
        echo AZURE_OCR_KEY=
        echo.
        echo # OCR - Azure Document Intelligence (Foundry)
        echo AZURE_FOUNDRY_ENDPOINT=
        echo AZURE_FOUNDRY_KEY=
        echo.
        echo # OCR - Mistral
        echo MISTRAL_API_KEY=
        echo.
        echo # AI - OpenAI
        echo OPENAI_API_KEY=
        echo OPENAI_MODEL=gpt-4o
        echo.
        echo # AI - Claude (Anthropic)
        echo ANTHROPIC_API_KEY=
        echo ANTHROPIC_MODEL=claude-opus-4-5
        echo.
        echo # AI - Azure Foundry AI
        echo AZURE_AI_ENDPOINT=
        echo AZURE_AI_KEY=
        echo AZURE_AI_MODEL=
        echo.
        echo # AI - OpenRouter
        echo OPENROUTER_API_KEY=
        echo OPENROUTER_MODEL=openai/gpt-4o
        echo.
        echo # Image islem
        echo DEFAULT_BBOX_MARGIN=5
        echo MAX_IMAGE_DIMENSION_PX=2000
        echo IMAGE_COMPRESSION_QUALITY=0.85
        echo IMAGE_STITCH_GAP_PX=2
    ) > .env
    echo [OK] .env olusturuldu. Lutfen SERVICE_API_KEY ve kullanacaginiz servis anahtarlarini doldurun!
) else (
    echo [SKIP] .env zaten mevcut.
)

echo.
echo ============================================================
echo  Kurulum tamamlandi!
echo.
echo  ONEMLI: .env dosyasini duzenleyerek:
echo    - SERVICE_API_KEY degerini guvenli bir anahtarla degistirin
echo    - Kullanacaginiz OCR/AI servislerin API anahtarlarini girin
echo.
echo  Servisi baslatmak icin: start-service.bat
echo ============================================================
echo.
pause
