"""
adapters/ocr/base.py — OcrAdapter soyut temel sınıfı.

Tüm OCR adaptörleri bu sınıfı implement etmelidir.
"""

from __future__ import annotations

from abc import ABC, abstractmethod

from models import OcrRequest, OcrResponse


class OcrAdapter(ABC):
    """Tüm OCR adaptörlerinin uygulaması gereken temel arayüz."""

    @abstractmethod
    async def perform_ocr(self, request: OcrRequest) -> OcrResponse:
        """Verilen sayfa görüntüsü üzerinde OCR yürütür.

        Args:
            request: Base64 görüntü + dil + ek config

        Returns:
            OcrResponse: Tespit edilen bloklar ve koordinatlar

        Raises:
            OcrServiceException: Servis erişim hatası durumunda
        """
        ...

    @abstractmethod
    def get_adapter_name(self) -> str:
        """Adaptör adını döndürür (loglama için)."""
        ...
