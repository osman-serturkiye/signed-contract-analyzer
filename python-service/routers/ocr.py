"""
routers/ocr.py — OCR endpoint'leri.

POST /ocr/paddleocr — PaddleOCR PP-StructureV3 ile sayfa analizi.
POST /ocr/surya     — Surya OCR 2 VLM ile sayfa analizi.

Her iki endpoint de Authorization: Bearer <key> header'ı gerektirir.
PaddleOCR veya Surya kurulu değilse 503 Service Unavailable döner.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status

from auth import require_api_key
from exceptions import OcrServiceException
from models import OcrRequest, OcrResponse
from adapters.ocr.paddleocr_adapter import PaddleOcrAdapter
from adapters.ocr.surya_adapter import SuryaAdapter
from adapters.ocr.azure_adapter import AzureOcrAdapter
from adapters.ocr.foundry_adapter import FoundryOcrAdapter
from adapters.ocr.mistral_adapter import MistralOcrAdapter

router = APIRouter()


@router.post(
    "/paddleocr",
    response_model=OcrResponse,
    summary="PaddleOCR PP-StructureV3 ile OCR",
    description=(
        "Verilen base64 görüntü üzerinde PaddleOCR PP-StructureV3 pipeline çalıştırır. "
        "Tablo, başlık, metin, resim ve imza bloklarını tespit eder."
    ),
)
async def ocr_paddleocr(
    request: OcrRequest,
    api_key: str = Depends(require_api_key),
) -> OcrResponse:
    """PaddleOCR adaptörü ile OCR çalıştırır."""
    try:
        return await PaddleOcrAdapter().perform_ocr(request)
    except OcrServiceException as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={
                "error": "OCR_FAILED",
                "detail": str(exc),
                "component": "paddleocr",
            },
        ) from exc


@router.post(
    "/surya",
    response_model=OcrResponse,
    summary="Surya OCR 2 VLM ile OCR",
    description=(
        "Verilen base64 görüntü üzerinde Surya layout tespiti ve OCR çalıştırır. "
        "Tablo, başlık, metin, resim ve el yazısı bloklarını tespit eder."
    ),
)
async def ocr_surya(
    request: OcrRequest,
    api_key: str = Depends(require_api_key),
) -> OcrResponse:
    """Surya OCR adaptörü ile OCR çalıştırır."""
    try:
        return await SuryaAdapter().perform_ocr(request)
    except OcrServiceException as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={
                "error": "OCR_FAILED",
                "detail": str(exc),
                "component": "surya",
            },
        ) from exc


@router.post("/azure", response_model=OcrResponse)
async def ocr_azure(request: OcrRequest, api_key: str = Depends(require_api_key)):
    try:
        return await AzureOcrAdapter(
            endpoint=request.config.get("endpoint", ""),
            api_key=request.config.get("apiKey", "")
        ).perform_ocr(request)
    except OcrServiceException as exc:
        raise HTTPException(status_code=503, detail={"error": "OCR_FAILED", "detail": str(exc), "component": "azure"})


@router.post("/foundry", response_model=OcrResponse)
async def ocr_foundry(request: OcrRequest, api_key: str = Depends(require_api_key)):
    try:
        return await FoundryOcrAdapter(
            endpoint=request.config.get("endpoint", ""),
            api_key=request.config.get("apiKey", "")
        ).perform_ocr(request)
    except OcrServiceException as exc:
        raise HTTPException(status_code=503, detail={"error": "OCR_FAILED", "detail": str(exc), "component": "foundry"})


@router.post("/mistral", response_model=OcrResponse)
async def ocr_mistral(request: OcrRequest, api_key: str = Depends(require_api_key)):
    try:
        return await MistralOcrAdapter(api_key=request.config.get("apiKey", "")).perform_ocr(request)
    except OcrServiceException as exc:
        raise HTTPException(status_code=503, detail={"error": "OCR_FAILED", "detail": str(exc), "component": "mistral"})
