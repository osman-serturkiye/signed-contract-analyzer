"""
tests/test_auth.py — auth.py dependency birim testleri.

Gereksinimler: 4.10, 22.5
- Geçerli Bearer token → 200
- Eksik header → 401
- Geçersiz token → 401
- Bearer olmayan şema → 401
"""

from __future__ import annotations

import os

import pytest
from fastapi import Depends
from fastapi.testclient import TestClient

os.environ.setdefault("SERVICE_API_KEY", "test-secret-key")

from config import get_settings
get_settings.cache_clear()

from fastapi import FastAPI
from auth import require_api_key

# Auth testi için minimal bir uygulama oluştur
_test_app = FastAPI()


@_test_app.get("/protected")
async def protected_endpoint(api_key: str = Depends(require_api_key)):
    return {"message": "erişim verildi", "key": api_key}


_client = TestClient(_test_app, raise_server_exceptions=False)

VALID_KEY = "test-secret-key"
INVALID_KEY = "wrong-key"


class TestAuthDependency:
    """require_api_key dependency testleri."""

    def test_valid_bearer_token_returns_200(self):
        """Geçerli Bearer token ile istek 200 döndürmelidir."""
        resp = _client.get("/protected", headers={"Authorization": f"Bearer {VALID_KEY}"})
        assert resp.status_code == 200
        assert resp.json()["message"] == "erişim verildi"

    def test_missing_auth_header_returns_401(self):
        """Authorization header olmadan istek 401 döndürmelidir."""
        resp = _client.get("/protected")
        assert resp.status_code == 401

    def test_invalid_token_returns_401(self):
        """Geçersiz token ile istek 401 döndürmelidir."""
        resp = _client.get("/protected", headers={"Authorization": f"Bearer {INVALID_KEY}"})
        assert resp.status_code == 401

    def test_non_bearer_scheme_returns_401(self):
        """Bearer dışı şema (Basic) ile istek 401 döndürmelidir."""
        resp = _client.get("/protected", headers={"Authorization": f"Basic {VALID_KEY}"})
        assert resp.status_code == 401

    def test_empty_bearer_token_returns_401(self):
        """Boş Bearer token 401 döndürmelidir."""
        resp = _client.get("/protected", headers={"Authorization": "Bearer "})
        assert resp.status_code == 401

    def test_401_response_has_www_authenticate_header(self):
        """401 yanıtı WWW-Authenticate: Bearer header'ı içermelidir."""
        resp = _client.get("/protected")
        assert resp.headers.get("www-authenticate") == "Bearer"
