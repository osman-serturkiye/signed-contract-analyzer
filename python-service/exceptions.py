"""
exceptions.py — Uygulama genelinde özel exception tanımları.
"""


class OcrServiceException(Exception):
    """OCR servisi erişim veya işlem hatası."""
    pass


class AiServiceException(Exception):
    """AI servisi erişim veya işlem hatası."""
    pass
