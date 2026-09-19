"""
tests/test_ocr.py — /ocr/paddleocr ve /ocr/surya endpoint birim testleri.

Gereksinimler: 4.9–4.14
- Başarılı OCR yanıtı (200 + OcrResponse alanları)
- OcrServiceException → 503 Service Unavailable
- Auth: token olmadan 401
- Yanıt blok yapısı (type, bbox, content, confidence)
- language alanı kabul edilmeli
"""

from __future__ import annotations

import base64
import io
import os
from unittest.mock import AsyncMock, patch

# TestClient ve app yüklenmeden önce SERVICE_API_KEY set edilmeli
os.environ.setdefault("SERVICE_API_KEY", "test-key")

from config import get_settings
get_settings.cache_clear()

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from routers import ocr as ocr_router

# MaxBodySizeMiddleware TestClient ile uyumsuz olduğundan minimal test app kullanılır
_app = FastAPI()
_app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
_app.include_router(ocr_router.router, prefix="/ocr", tags=["ocr"])

from fastapi.testclient import TestClient

client = TestClient(_app, raise_server_exceptions=False)
AUTH = {"Authorization": "Bearer test-key"}


# ── Yardımcı fonksiyonlar ──────────────────────────────────────────────────────

from PIL import Image


def make_b64_image(w: int = 100, h: int = 100) -> str:
    """Belirtilen boyutta bir PIL görüntüsü oluşturup Base64 string döndürür."""
    img = Image.new("RGB", (w, h), (200, 200, 200))
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    return base64.b64encode(buf.getvalue()).decode()


from models import OcrResponse, OcrBlock, BoundingBoxModel


def mock_ocr_response() -> OcrResponse:
    """Tek bloklu geçerli OcrResponse oluşturur."""
    return OcrResponse(
        blocks=[
            OcrBlock(
                type="text",
                bbox=BoundingBoxModel(x=0, y=0, width=100, height=100),
                content="hello",
                confidence=0.9,
            )
        ],
        page_width=100,
        page_height=100,
    )


from exceptions import OcrServiceException


# ── PaddleOCR Testleri ────────────────────────────────────────────────────────

def test_paddleocr_returns_200_with_valid_response():
    """
    Req 4.9: PaddleOCR adaptörü başarılı dönüş yaptığında /ocr/paddleocr
    200 yanıtı döndürmeli; yanıt blocks, page_width, page_height içermelidir.
    """
    with patch(
        "adapters.ocr.paddleocr_adapter.PaddleOcrAdapter.perform_ocr",
        new_callable=AsyncMock,
        return_value=mock_ocr_response(),
    ):
        payload = {"image": make_b64_image(), "language": "tr", "config": {}}
        resp = client.post("/ocr/paddleocr", json=payload, headers=AUTH)

    assert resp.status_code == 200
    data = resp.json()
    assert "blocks" in data
    assert "page_width" in data
    assert "page_height" in data


def test_surya_returns_200_with_valid_response():
    """
    Req 4.10: Surya adaptörü başarılı dönüş yaptığında /ocr/surya
    200 yanıtı döndürmeli; yanıt blocks, page_width, page_height içermelidir.
    """
    with patch(
        "adapters.ocr.surya_adapter.SuryaAdapter.perform_ocr",
        new_callable=AsyncMock,
        return_value=mock_ocr_response(),
    ):
        payload = {"image": make_b64_image(), "language": "tr", "config": {}}
        resp = client.post("/ocr/surya", json=payload, headers=AUTH)

    assert resp.status_code == 200
    data = resp.json()
    assert "blocks" in data
    assert "page_width" in data
    assert "page_height" in data


def test_paddleocr_503_when_service_unavailable():
    """
    Req 4.11: PaddleOCR kurulu değilse OcrServiceException fırlatılır;
    /ocr/paddleocr endpoint'i 503 Service Unavailable döndürmelidir.
    """
    with patch(
        "adapters.ocr.paddleocr_adapter.PaddleOcrAdapter.perform_ocr",
        new_callable=AsyncMock,
        side_effect=OcrServiceException("not installed"),
    ):
        payload = {"image": make_b64_image(), "language": "tr", "config": {}}
        resp = client.post("/ocr/paddleocr", json=payload, headers=AUTH)

    assert resp.status_code == 503


def test_surya_503_when_service_unavailable():
    """
    Req 4.12: Surya kurulu değilse OcrServiceException fırlatılır;
    /ocr/surya endpoint'i 503 Service Unavailable döndürmelidir.
    """
    with patch(
        "adapters.ocr.surya_adapter.SuryaAdapter.perform_ocr",
        new_callable=AsyncMock,
        side_effect=OcrServiceException("not installed"),
    ):
        payload = {"image": make_b64_image(), "language": "tr", "config": {}}
        resp = client.post("/ocr/surya", json=payload, headers=AUTH)

    assert resp.status_code == 503


def test_paddleocr_401_without_auth():
    """
    Req 4.13: /ocr/paddleocr Authorization header olmadan istek
    401 Unauthorized döndürmelidir.
    """
    payload = {"image": make_b64_image(), "language": "tr", "config": {}}
    resp = client.post("/ocr/paddleocr", json=payload)
    assert resp.status_code == 401


def test_surya_401_without_auth():
    """
    Req 4.14: /ocr/surya Authorization header olmadan istek
    401 Unauthorized döndürmelidir.
    """
    payload = {"image": make_b64_image(), "language": "tr", "config": {}}
    resp = client.post("/ocr/surya", json=payload)
    assert resp.status_code == 401


def test_ocr_block_structure():
    """
    Req 4.9: OcrResponse içindeki blok tüm gerekli alanları
    (type, bbox, content, confidence) doğru değerlerle içermelidir.
    """
    with patch(
        "adapters.ocr.paddleocr_adapter.PaddleOcrAdapter.perform_ocr",
        new_callable=AsyncMock,
        return_value=mock_ocr_response(),
    ):
        payload = {"image": make_b64_image(), "language": "tr", "config": {}}
        resp = client.post("/ocr/paddleocr", json=payload, headers=AUTH)

    assert resp.status_code == 200
    data = resp.json()
    assert len(data["blocks"]) == 1
    block = data["blocks"][0]
    assert "type" in block
    assert "bbox" in block
    assert "content" in block
    assert "confidence" in block
    assert block["type"] == "text"
    assert block["content"] == "hello"
    assert abs(block["confidence"] - 0.9) < 1e-6


def test_ocr_accepts_language_field():
    """
    Req 4.9: language="en" alanı gönderildiğinde endpoint 200 döndürmeli;
    language alanı OcrRequest tarafından kabul edilmelidir.
    """
    with patch(
        "adapters.ocr.paddleocr_adapter.PaddleOcrAdapter.perform_ocr",
        new_callable=AsyncMock,
        return_value=mock_ocr_response(),
    ):
        payload = {"image": make_b64_image(), "language": "en"}
        resp = client.post("/ocr/paddleocr", json=payload, headers=AUTH)

    assert resp.status_code == 200


# ── Azure OCR Testleri ────────────────────────────────────────────────────────

def test_azure_returns_200_with_valid_response():
    """
    Req 4.11: Azure adaptörü başarılı dönüş yaptığında /ocr/azure
    200 yanıtı döndürmeli; yanıt blocks, page_width, page_height içermelidir.
    """
    with patch(
        "adapters.ocr.azure_adapter.AzureOcrAdapter.perform_ocr",
        new_callable=AsyncMock,
        return_value=mock_ocr_response(),
    ):
        payload = {
            "image": make_b64_image(),
            "language": "tr",
            "config": {"endpoint": "https://example.cognitiveservices.azure.com", "apiKey": "fake-key"},
        }
        resp = client.post("/ocr/azure", json=payload, headers=AUTH)

    assert resp.status_code == 200
    data = resp.json()
    assert "blocks" in data
    assert "page_width" in data
    assert "page_height" in data


def test_azure_503_when_service_unavailable():
    """
    Req 4.11: Azure servisi erişilemez durumdaysa OcrServiceException fırlatılır;
    /ocr/azure endpoint'i 503 Service Unavailable döndürmelidir.
    """
    with patch(
        "adapters.ocr.azure_adapter.AzureOcrAdapter.perform_ocr",
        new_callable=AsyncMock,
        side_effect=OcrServiceException("Azure unavailable"),
    ):
        payload = {
            "image": make_b64_image(),
            "language": "tr",
            "config": {"endpoint": "https://example.cognitiveservices.azure.com", "apiKey": "fake-key"},
        }
        resp = client.post("/ocr/azure", json=payload, headers=AUTH)

    assert resp.status_code == 503


def test_azure_401_without_auth():
    """
    Req 4.13: /ocr/azure Authorization header olmadan istek
    401 Unauthorized döndürmelidir.
    """
    payload = {
        "image": make_b64_image(),
        "language": "tr",
        "config": {"endpoint": "https://example.cognitiveservices.azure.com", "apiKey": "fake-key"},
    }
    resp = client.post("/ocr/azure", json=payload)
    assert resp.status_code == 401


def test_azure_block_structure():
    """
    Req 4.11: Azure OcrResponse içindeki blok tüm gerekli alanları
    (type, bbox, content, confidence) içermelidir.
    """
    with patch(
        "adapters.ocr.azure_adapter.AzureOcrAdapter.perform_ocr",
        new_callable=AsyncMock,
        return_value=mock_ocr_response(),
    ):
        payload = {
            "image": make_b64_image(),
            "language": "tr",
            "config": {"endpoint": "https://example.cognitiveservices.azure.com", "apiKey": "fake-key"},
        }
        resp = client.post("/ocr/azure", json=payload, headers=AUTH)

    assert resp.status_code == 200
    data = resp.json()
    block = data["blocks"][0]
    assert "type" in block
    assert "bbox" in block
    assert "content" in block
    assert "confidence" in block


# ── Foundry OCR Testleri ──────────────────────────────────────────────────────

def test_foundry_returns_200_with_valid_response():
    """
    Req 4.12: Foundry adaptörü başarılı dönüş yaptığında /ocr/foundry
    200 yanıtı döndürmeli; yanıt blocks, page_width, page_height içermelidir.
    """
    with patch(
        "adapters.ocr.foundry_adapter.FoundryOcrAdapter.perform_ocr",
        new_callable=AsyncMock,
        return_value=mock_ocr_response(),
    ):
        payload = {
            "image": make_b64_image(),
            "language": "tr",
            "config": {"endpoint": "https://example.openai.azure.com", "apiKey": "fake-key"},
        }
        resp = client.post("/ocr/foundry", json=payload, headers=AUTH)

    assert resp.status_code == 200
    data = resp.json()
    assert "blocks" in data
    assert "page_width" in data
    assert "page_height" in data


def test_foundry_503_when_service_unavailable():
    """
    Req 4.12: Foundry servisi erişilemez durumdaysa OcrServiceException fırlatılır;
    /ocr/foundry endpoint'i 503 Service Unavailable döndürmelidir.
    """
    with patch(
        "adapters.ocr.foundry_adapter.FoundryOcrAdapter.perform_ocr",
        new_callable=AsyncMock,
        side_effect=OcrServiceException("Foundry unavailable"),
    ):
        payload = {
            "image": make_b64_image(),
            "language": "tr",
            "config": {"endpoint": "https://example.openai.azure.com", "apiKey": "fake-key"},
        }
        resp = client.post("/ocr/foundry", json=payload, headers=AUTH)

    assert resp.status_code == 503


def test_foundry_401_without_auth():
    """
    Req 4.13: /ocr/foundry Authorization header olmadan istek
    401 Unauthorized döndürmelidir.
    """
    payload = {
        "image": make_b64_image(),
        "language": "tr",
        "config": {"endpoint": "https://example.openai.azure.com", "apiKey": "fake-key"},
    }
    resp = client.post("/ocr/foundry", json=payload)
    assert resp.status_code == 401


def test_foundry_block_structure():
    """
    Req 4.12: Foundry OcrResponse içindeki blok tüm gerekli alanları
    (type, bbox, content, confidence) içermelidir.
    """
    with patch(
        "adapters.ocr.foundry_adapter.FoundryOcrAdapter.perform_ocr",
        new_callable=AsyncMock,
        return_value=mock_ocr_response(),
    ):
        payload = {
            "image": make_b64_image(),
            "language": "tr",
            "config": {"endpoint": "https://example.openai.azure.com", "apiKey": "fake-key"},
        }
        resp = client.post("/ocr/foundry", json=payload, headers=AUTH)

    assert resp.status_code == 200
    data = resp.json()
    block = data["blocks"][0]
    assert "type" in block
    assert "bbox" in block
    assert "content" in block
    assert "confidence" in block


# ── Mistral OCR Testleri ──────────────────────────────────────────────────────

def test_mistral_returns_200_with_valid_response():
    """
    Req 4.13: Mistral adaptörü başarılı dönüş yaptığında /ocr/mistral
    200 yanıtı döndürmeli; yanıt blocks, page_width, page_height içermelidir.
    """
    with patch(
        "adapters.ocr.mistral_adapter.MistralOcrAdapter.perform_ocr",
        new_callable=AsyncMock,
        return_value=mock_ocr_response(),
    ):
        payload = {
            "image": make_b64_image(),
            "language": "tr",
            "config": {"apiKey": "fake-mistral-key"},
        }
        resp = client.post("/ocr/mistral", json=payload, headers=AUTH)

    assert resp.status_code == 200
    data = resp.json()
    assert "blocks" in data
    assert "page_width" in data
    assert "page_height" in data


def test_mistral_503_when_service_unavailable():
    """
    Req 4.13: Mistral servisi erişilemez durumdaysa OcrServiceException fırlatılır;
    /ocr/mistral endpoint'i 503 Service Unavailable döndürmelidir.
    """
    with patch(
        "adapters.ocr.mistral_adapter.MistralOcrAdapter.perform_ocr",
        new_callable=AsyncMock,
        side_effect=OcrServiceException("Mistral unavailable"),
    ):
        payload = {
            "image": make_b64_image(),
            "language": "tr",
            "config": {"apiKey": "fake-mistral-key"},
        }
        resp = client.post("/ocr/mistral", json=payload, headers=AUTH)

    assert resp.status_code == 503


def test_mistral_401_without_auth():
    """
    Req 4.14: /ocr/mistral Authorization header olmadan istek
    401 Unauthorized döndürmelidir.
    """
    payload = {
        "image": make_b64_image(),
        "language": "tr",
        "config": {"apiKey": "fake-mistral-key"},
    }
    resp = client.post("/ocr/mistral", json=payload)
    assert resp.status_code == 401


def test_mistral_block_structure():
    """
    Req 4.13: Mistral OcrResponse içindeki blok tüm gerekli alanları
    (type, bbox, content, confidence) içermelidir.
    """
    with patch(
        "adapters.ocr.mistral_adapter.MistralOcrAdapter.perform_ocr",
        new_callable=AsyncMock,
        return_value=mock_ocr_response(),
    ):
        payload = {
            "image": make_b64_image(),
            "language": "tr",
            "config": {"apiKey": "fake-mistral-key"},
        }
        resp = client.post("/ocr/mistral", json=payload, headers=AUTH)

    assert resp.status_code == 200
    data = resp.json()
    block = data["blocks"][0]
    assert "type" in block
    assert "bbox" in block
    assert "content" in block
    assert "confidence" in block


def test_mistral_accepts_language_field():
    """
    Req 4.13: language="en" alanı gönderildiğinde /ocr/mistral 200 döndürmeli.
    """
    with patch(
        "adapters.ocr.mistral_adapter.MistralOcrAdapter.perform_ocr",
        new_callable=AsyncMock,
        return_value=mock_ocr_response(),
    ):
        payload = {
            "image": make_b64_image(),
            "language": "en",
            "config": {"apiKey": "fake-mistral-key"},
        }
        resp = client.post("/ocr/mistral", json=payload, headers=AUTH)

    assert resp.status_code == 200
