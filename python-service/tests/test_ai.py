"""
tests/test_ai.py — /ai/compare endpoint birim testleri.

Gereksinimler: 11.7, 11.8
- Başarılı karşılaştırma → 200, changes ve result alanları dolu
- Servis hatası → 200, changes=null
- Auth yoksa → 401
- İdentik maddeler → changes=False
- Zorunlu istek gövdesi alanları kabul edilir
- İsteğe bağlı language_hint alanı kabul edilir
"""

from __future__ import annotations

import os

os.environ.setdefault("SERVICE_API_KEY", "test-key")
os.environ.setdefault("AI_ADAPTER", "OpenAI")
os.environ.setdefault("AI_API_KEY", "fake-key")

from config import get_settings
get_settings.cache_clear()

from unittest.mock import AsyncMock, patch

import pytest
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.testclient import TestClient

from models import AiCompareResponse
from routers import ai as ai_router

_app = FastAPI()
_app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
_app.include_router(ai_router.router, prefix="/ai", tags=["ai"])

client = TestClient(_app, raise_server_exceptions=False)
AUTH = {"Authorization": "Bearer test-key"}

# Çoğu test için kullanılan standart istek gövdesi
BODY = {
    "clause_number": "1",
    "signed_content": "Madde 1 imzalı",
    "original_content": "Madde 1 orijinal",
}


class TestAiCompareEndpoint:
    """POST /ai/compare endpoint birim testleri."""

    def test_compare_success_changes_true(self):
        """Başarılı AI karşılaştırması 200 döndürmeli; changes=True olmalıdır."""
        mock_response = AiCompareResponse(changes=True, result="Clause changed")
        with patch(
            "adapters.ai.openai_adapter.OpenAiAdapter.compare",
            new_callable=AsyncMock,
            return_value=mock_response,
        ):
            resp = client.post("/ai/compare", json=BODY, headers=AUTH)

        assert resp.status_code == 200
        data = resp.json()
        assert data["changes"] is True

    def test_compare_service_error_returns_null_changes(self):
        """AI servis erişim hatası durumunda changes null (None) dönmelidir."""
        mock_response = AiCompareResponse(
            changes=None, result="AI service error: connection failed"
        )
        with patch(
            "adapters.ai.openai_adapter.OpenAiAdapter.compare",
            new_callable=AsyncMock,
            return_value=mock_response,
        ):
            resp = client.post("/ai/compare", json=BODY, headers=AUTH)

        assert resp.status_code == 200
        data = resp.json()
        assert data["changes"] is None

    def test_compare_no_auth_returns_401(self):
        """Authorization header olmadan yapılan istek 401 döndürmelidir."""
        resp = client.post("/ai/compare", json=BODY)
        assert resp.status_code == 401

    def test_compare_identical_clauses(self):
        """İdentik maddeler için changes=False dönmelidir."""
        mock_response = AiCompareResponse(changes=False, result="No changes")
        with patch(
            "adapters.ai.openai_adapter.OpenAiAdapter.compare",
            new_callable=AsyncMock,
            return_value=mock_response,
        ):
            resp = client.post("/ai/compare", json=BODY, headers=AUTH)

        assert resp.status_code == 200
        data = resp.json()
        assert data["changes"] is False

    def test_compare_request_required_fields(self):
        """Zorunlu alanları içeren minimal istek gövdesi kabul edilmelidir (200)."""
        body = {
            "clause_number": "1",
            "signed_content": "text A",
            "original_content": "text B",
        }
        mock_response = AiCompareResponse(changes=True, result="Clause changed")
        with patch(
            "adapters.ai.openai_adapter.OpenAiAdapter.compare",
            new_callable=AsyncMock,
            return_value=mock_response,
        ):
            resp = client.post("/ai/compare", json=body, headers=AUTH)

        assert resp.status_code == 200

    def test_compare_with_optional_language_hint(self):
        """İsteğe bağlı language_hint='tr' alanı içeren istek kabul edilmelidir (200)."""
        body = {**BODY, "language_hint": "tr"}
        mock_response = AiCompareResponse(changes=True, result="Clause changed")
        with patch(
            "adapters.ai.openai_adapter.OpenAiAdapter.compare",
            new_callable=AsyncMock,
            return_value=mock_response,
        ):
            resp = client.post("/ai/compare", json=body, headers=AUTH)

        assert resp.status_code == 200
