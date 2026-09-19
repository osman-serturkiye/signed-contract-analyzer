"""
main.py — FastAPI uygulama giriş noktası.

Özellikler:
- Lifespan context manager (başlangıç/kapanış loglama)
- CORS middleware (tüm origin'lere izin; yerel mikroservis kullanımı)
- Global hata yönetimi (standart JSON hata yanıtları)
- Request body boyut sınırı (413 Payload Too Large desteği)
"""

from __future__ import annotations

import logging
import sys
from contextlib import asynccontextmanager
from typing import AsyncIterator

from fastapi import FastAPI, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware

from config import get_settings
from routers import health
from routers import image as image_router

# ── Loglama kurulumu ──────────────────────────────────────────────────────────

settings = get_settings()

logging.basicConfig(
    level=getattr(logging, settings.LOG_LEVEL, logging.INFO),
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
    datefmt="%Y-%m-%dT%H:%M:%S",
    stream=sys.stdout,
)
logger = logging.getLogger("main")


# ── Lifespan ──────────────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Uygulama başlarken ve kapanırken çalışır."""
    logger.info(
        "Python OCR/Report Mikroservisi başlatılıyor — port=%d, log_level=%s",
        settings.SERVICE_PORT,
        settings.LOG_LEVEL,
    )
    yield
    logger.info("Python OCR/Report Mikroservisi kapatılıyor.")


# ── FastAPI uygulaması ────────────────────────────────────────────────────────

app = FastAPI(
    title="Signed Contract Analyzer — Python Mikroservis",
    description=(
        "OCR (PaddleOCR, Surya, Azure, Foundry, Mistral), "
        "görüntü işleme, AI karşılaştırma ve rapor üretimi endpoint'leri."
    ),
    version="1.0.0",
    lifespan=lifespan,
    # Üretim ortamında docs kapatılmak istenirse: docs_url=None, redoc_url=None
)


# ── CORS Middleware ───────────────────────────────────────────────────────────

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],          # Yerel mikroservis; tüm origin'lere izin
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ── Request Body Boyut Sınırı Middleware ──────────────────────────────────────

class MaxBodySizeMiddleware(BaseHTTPMiddleware):
    """
    İstek gövdesini tamamen belleğe okumadan önce Content-Length başlığını
    kontrol eder; sınır aşılırsa 413 döndürür.

    Streaming body için de çalışır: chunk'lar birikmeden önce toplam
    okunan bayt sayısı MAX_BODY_SIZE_BYTES'ı aşarsa bağlantı kesilir.
    """

    def __init__(self, app, max_body_size: int) -> None:
        super().__init__(app)
        self.max_body_size = max_body_size

    async def dispatch(self, request: Request, call_next):
        content_length = request.headers.get("content-length")
        if content_length and int(content_length) > self.max_body_size:
            return JSONResponse(
                status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                content={
                    "error": "PAYLOAD_TOO_LARGE",
                    "detail": (
                        f"İstek gövdesi çok büyük. "
                        f"Maksimum izin verilen boyut: "
                        f"{self.max_body_size // (1024 * 1024)} MB."
                    ),
                    "component": "MaxBodySizeMiddleware",
                },
            )

        # Body akış okuma sırasında boyut kontrolü
        received = 0
        chunks: list[bytes] = []
        async for chunk in request.stream():
            received += len(chunk)
            if received > self.max_body_size:
                return JSONResponse(
                    status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                    content={
                        "error": "PAYLOAD_TOO_LARGE",
                        "detail": (
                            f"İstek gövdesi çok büyük. "
                            f"Maksimum izin verilen boyut: "
                            f"{self.max_body_size // (1024 * 1024)} MB."
                        ),
                        "component": "MaxBodySizeMiddleware",
                    },
                )
            chunks.append(chunk)

        # Body'yi yeniden oluştur (Starlette iç mekanizması için)
        async def receive():
            return {"type": "http.request", "body": b"".join(chunks), "more_body": False}

        request._receive = receive
        return await call_next(request)


app.add_middleware(
    MaxBodySizeMiddleware,
    max_body_size=settings.MAX_BODY_SIZE_BYTES,
)


# ── Global Hata İşleyicileri ──────────────────────────────────────────────────

@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    """Yakalanmamış tüm exception'ları standart JSON formatında döndürür."""
    logger.exception("Beklenmeyen hata: %s %s", request.method, request.url)
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content={
            "error": "INTERNAL_SERVER_ERROR",
            "detail": str(exc),
            "component": "global_exception_handler",
        },
    )


@app.exception_handler(422)
async def validation_exception_handler(request: Request, exc) -> JSONResponse:
    """Pydantic doğrulama hatalarını standart formata çevirir."""
    logger.warning("Doğrulama hatası: %s %s — %s", request.method, request.url, exc)
    return JSONResponse(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        content={
            "error": "VALIDATION_ERROR",
            "detail": str(exc),
            "component": "request_validation",
        },
    )


# ── Router kayıtları ──────────────────────────────────────────────────────────

app.include_router(health.router)
app.include_router(image_router.router, prefix="/image", tags=["image"])

from routers import ocr as ocr_router
app.include_router(ocr_router.router, prefix="/ocr", tags=["ocr"])

from routers import ai as ai_router
app.include_router(ai_router.router, prefix="/ai", tags=["ai"])

from routers import report as report_router
app.include_router(report_router.router, prefix="/report", tags=["report"])


# ── Doğrudan çalıştırma (uvicorn) ─────────────────────────────────────────────

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "main:app",
        host=settings.SERVICE_HOST,
        port=settings.SERVICE_PORT,
        log_level=settings.LOG_LEVEL.lower(),
        reload=False,
    )
