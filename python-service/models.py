"""
models.py — Pydantic request/response veri modelleri.

OCR, Image, AI ve Report endpoint'leri tarafından paylaşılan modeller burada tanımlanır.
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional

from pydantic import BaseModel


# ── BoundingBox ───────────────────────────────────────────────────────────────

class BoundingBoxModel(BaseModel):
    x: int
    y: int
    width: int
    height: int


# ── OCR Modelleri ─────────────────────────────────────────────────────────────

class OcrBlock(BaseModel):
    type: str
    """Blok türü: text | title | paragraph | table | image | signature"""
    bbox: BoundingBoxModel
    content: str
    """Markdown formatında metin içeriği; tablolar Markdown tablo olarak."""
    confidence: Optional[float] = None
    """Güven skoru 0.0–1.0; desteklenmeyen motorlar için null."""


class OcrRequest(BaseModel):
    image: str
    """Base64 kodlu sayfa görüntüsü."""
    language: str = "tr"
    """ISO 639-1 dil kodu."""
    config: Dict[str, Any] = {}
    """Adaptöre özgü ek parametreler."""


class OcrResponse(BaseModel):
    blocks: List[OcrBlock]
    page_width: int
    page_height: int


# ── Image Modelleri ───────────────────────────────────────────────────────────

class CropRequest(BaseModel):
    image: str
    """Base64 kodlu sayfa görüntüsü."""
    bbox: BoundingBoxModel
    margin: int = 5
    """Her yöne eklenecek piksel boşluğu."""
    max_dimension_px: Optional[int] = 2000
    """Uzun kenar maksimum piksel limiti."""
    compression_quality: Optional[float] = 0.85
    """JPEG kalitesi (0.0–1.0)."""


class CropResponse(BaseModel):
    image: str
    """Base64 kırpılmış görüntü."""
    format: str
    """'jpeg' | 'png'"""


class StitchRequest(BaseModel):
    images: List[str]
    """Sıralı Base64 görüntü listesi."""
    direction: str = "vertical"
    gap_px: int = 2


class StitchResponse(BaseModel):
    image: str
    format: str


# ── AI Modelleri ──────────────────────────────────────────────────────────────

class AiCompareRequest(BaseModel):
    clause_number: str
    signed_content: str
    """Markdown formatında imzalı içerik."""
    original_content: str
    """Markdown formatında orijinal içerik."""
    language_hint: Optional[str] = None
    """Aktif dil (tr, en, ...)."""
    multilingual_note: Optional[str] = None
    """Çok dilli uyarı metni."""


class AiCompareResponse(BaseModel):
    changes: Optional[bool] = None
    """null → servis erişim hatası."""
    result: str
    """Markdown formatında AI değerlendirmesi."""


# ── Report Modelleri ──────────────────────────────────────────────────────────

class ReportRequest(BaseModel):
    signed_contract: Dict[str, Any]
    original_contract: Dict[str, Any]
    analysis: Dict[str, Any]


# ── Hata Yanıt Modeli ─────────────────────────────────────────────────────────

class ErrorResponse(BaseModel):
    error: str
    """Hata kodu (OCR_FAILED, AUTH_ERROR, ...)."""
    detail: str
    """Açıklayıcı mesaj."""
    component: str
    """Hatayı üreten bileşen."""
