"""
tests/test_health.py — /health endpoint birim testleri.

Gereksinimler: 27.1, 27.2, 27.3
- GET /health → 200 {"status": "ok"}
- Auth gerektirmez
"""

from __future__ import annotations

import os

import pytest
from fastapi.testclient import TestClient

# Test ortamı için SERVICE_API_KEY zorunlu olduğundan test öncesinde set ediyoruz.
os.environ.setdefault("SERVICE_API_KEY", "test-api-key-for-tests")

# config cache'ini temizle (ortam değişkeni test öncesinde set edilmiş olmalı)
from config import get_settings
get_settings.cache_clear()

from main import app  # noqa: E402  (import sırası kasıtlı)

client = TestClient(app, raise_server_exceptions=True)


class TestHealthEndpoint:
    """GET /health endpoint testleri."""

    def test_health_returns_200(self):
        """Req 27.1: /health GET isteği 200 durum kodu döndürmelidir."""
        response = client.get("/health")
        assert response.status_code == 200

    def test_health_returns_status_ok(self):
        """Req 27.2: /health yanıtı {"status": "ok"} içermelidir."""
        response = client.get("/health")
        data = response.json()
        assert data == {"status": "ok"}

    def test_health_no_auth_required(self):
        """Req 27.3: /health endpoint'i Authorization header olmadan erişilebilir olmalıdır."""
        # Authorization header gönderilmeksizin istek yapılıyor
        response = client.get("/health", headers={})
        assert response.status_code == 200

    def test_health_with_invalid_auth_still_returns_200(self):
        """
        /health endpoint'i auth gerektirmediğinden, geçersiz auth header'ı
        bile 200 döndürmelidir.
        """
        response = client.get(
            "/health",
            headers={"Authorization": "Bearer invalid-key"},
        )
        assert response.status_code == 200

    def test_health_content_type_is_json(self):
        """/health yanıt Content-Type'ı application/json olmalıdır."""
        response = client.get("/health")
        assert "application/json" in response.headers.get("content-type", "")
