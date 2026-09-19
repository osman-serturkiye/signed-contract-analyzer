"""
routers/report.py — HTML ve PDF rapor üretim endpoint'leri.

POST /report/html — Jinja2 ile render edilmiş HTML5 döküman (text/html)
POST /report/pdf   — Aynı şablon üzerinden WeasyPrint/Playwright ile üretilen PDF (application/pdf)

Her iki endpoint de Authorization: Bearer <key> gerektirir (ReportServiceApiKey,
OcrServiceApiKey ile aynı SERVICE_API_KEY).
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Response, status

from auth import require_api_key
from config import get_settings
from models import ReportRequest
from services.report_service import ReportService

router = APIRouter()
_service = ReportService()


@router.post(
    "/html",
    summary="HTML rapor üretimi",
    description="analyze.json verisini report-template.html üzerinden render eder.",
)
async def report_html(
    request: ReportRequest,
    api_key: str = Depends(require_api_key),
) -> Response:
    try:
        html = _service.render_html(request.model_dump())
        return Response(content=html, media_type="text/html; charset=UTF-8")
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"error": "REPORT_HTML_FAILED", "detail": str(exc), "component": "report_service"},
        ) from exc


@router.post(
    "/pdf",
    summary="PDF rapor üretimi",
    description="Aynı ReportTemplate'ten HTML üretip WeasyPrint/Playwright ile PDF'e dönüştürür.",
)
async def report_pdf(
    request: ReportRequest,
    api_key: str = Depends(require_api_key),
) -> Response:
    settings = get_settings()
    try:
        pdf_bytes = _service.render_pdf(request.model_dump(), use_playwright=settings.USE_PLAYWRIGHT_FOR_PDF)
        return Response(content=pdf_bytes, media_type="application/pdf")
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"error": "REPORT_PDF_FAILED", "detail": str(exc), "component": "report_service"},
        ) from exc
