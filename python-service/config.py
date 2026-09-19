"""
config.py — .env dosyasını okur ve uygulama ayarlarını doğrular.
Zorunlu alanlar eksikse ValueError fırlatır.
"""

from __future__ import annotations

import os
from functools import lru_cache

from dotenv import load_dotenv

# Proje kök dizinindeki .env dosyasını yükle
load_dotenv()


class Settings:
    """Uygulama yapılandırması. .env dosyasından veya ortam değişkenlerinden okunur."""

    def __init__(self) -> None:
        # ── Zorunlu alanlar ────────────────────────────────────────────────────
        self.SERVICE_API_KEY: str = self._require("SERVICE_API_KEY")

        # ── İsteğe bağlı alanlar (varsayılan değerlerle) ───────────────────────
        self.SERVICE_HOST: str = os.getenv("SERVICE_HOST", "0.0.0.0")
        self.SERVICE_PORT: int = int(os.getenv("SERVICE_PORT", "8765"))

        self.LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO").upper()
        self.LOG_FORMAT: str = os.getenv("LOG_FORMAT", "plain").lower()  # "plain" | "json"

        self.USE_PLAYWRIGHT_FOR_PDF: bool = (
            os.getenv("USE_PLAYWRIGHT_FOR_PDF", "false").lower() == "true"
        )

        self.PADDLE_LANG: str = os.getenv("PADDLE_LANG", "tr")
        self.SURYA_MODEL_CACHE: str = os.getenv("SURYA_MODEL_CACHE", "")

        # İstek gövdesi maksimum boyutu (bayt cinsinden; varsayılan 10 MB)
        self.MAX_BODY_SIZE_BYTES: int = int(
            os.getenv("MAX_BODY_SIZE_BYTES", str(10 * 1024 * 1024))
        )

        # ── AI Adaptör ayarları ────────────────────────────────────────────────
        self.AI_ADAPTER: str = os.getenv("AI_ADAPTER", "")
        self.AI_API_KEY: str = os.getenv("AI_API_KEY", "")
        self.AI_ENDPOINT: str = os.getenv("AI_ENDPOINT", "")
        self.AI_MODEL: str = os.getenv("AI_MODEL", "")

    @staticmethod
    def _require(key: str) -> str:
        """Ortam değişkenini okur; tanımsızsa veya boşsa ValueError fırlatır."""
        value = os.getenv(key, "").strip()
        if not value:
            raise ValueError(
                f"Zorunlu yapılandırma değişkeni eksik veya boş: '{key}'. "
                f"Lütfen .env dosyasını veya ortam değişkenlerini kontrol edin."
            )
        return value


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Singleton ayar nesnesi döndürür (test ortamında override edilebilir)."""
    return Settings()
