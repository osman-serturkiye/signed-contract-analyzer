"""
tests/test_image.py — /image/crop ve /image/stitch endpoint birim testleri.

Gereksinimler: 2.10, 3.6, 26.1, 26.2
- Crop: temel kırpma, margin clamping, max_dimension_px ölçekleme, JPEG/PNG çıktı formatı
- Stitch: dikey birleştirme (yükseklik doğrulama), tek görüntü
- Auth: token olmadan 401
"""

from __future__ import annotations

import base64
import io
import os

# TestClient ve app yüklenmeden önce SERVICE_API_KEY set edilmeli
os.environ["SERVICE_API_KEY"] = "test-key"

from config import get_settings
get_settings.cache_clear()

from fastapi import FastAPI, Depends
from fastapi.middleware.cors import CORSMiddleware
from fastapi.testclient import TestClient
from PIL import Image

from routers import image as image_router

# MaxBodySizeMiddleware TestClient ile uyumsuz olduğundan minimal test app kullanılır
_app = FastAPI()
_app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
_app.include_router(image_router.router, prefix="/image", tags=["image"])

client = TestClient(_app, raise_server_exceptions=True)

AUTH_HEADERS = {"Authorization": "Bearer test-key"}


# ── Yardımcı fonksiyonlar ──────────────────────────────────────────────────────

def make_b64_image(w: int = 200, h: int = 200, color: tuple = (255, 255, 255)) -> str:
    """Belirtilen boyutta ve renkte bir PIL görüntüsü oluşturup Base64 string döndürür."""
    img = Image.new("RGB", (w, h), color)
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    return base64.b64encode(buf.getvalue()).decode()


# ── Crop Testleri ─────────────────────────────────────────────────────────────

def test_crop_basic():
    """
    Req 2.10, 26.1: 200×200 görüntüde bbox (10,10,50,50) margin=5 ile kırpma
    başarılı olmalı; yanıt 200 döndürmeli ve format alanı içermelidir.
    """
    img_b64 = make_b64_image(200, 200)
    payload = {
        "image": img_b64,
        "bbox": {"x": 10, "y": 10, "width": 50, "height": 50},
        "margin": 5,
        "compression_quality": 0.85,
    }
    resp = client.post("/image/crop", json=payload, headers=AUTH_HEADERS)
    assert resp.status_code == 200
    data = resp.json()
    assert "image" in data
    assert "format" in data


def test_crop_margin_clamp():
    """
    Req 2.10: bbox kenar pikselde (x=180, y=180, w=50, h=50) ve margin=20
    verildiğinde (sınır aşımı oluşur) 4xx hatası dönmemeli, geçerli görüntü
    döndürülmelidir.
    """
    img_b64 = make_b64_image(200, 200)
    payload = {
        "image": img_b64,
        "bbox": {"x": 180, "y": 180, "width": 50, "height": 50},
        "margin": 20,  # 180+50+20=250 > 200 → sınır aşımı
        "compression_quality": 0.85,
    }
    resp = client.post("/image/crop", json=payload, headers=AUTH_HEADERS)
    assert resp.status_code < 400
    data = resp.json()
    assert "image" in data
    result_img = Image.open(io.BytesIO(base64.b64decode(data["image"])))
    assert result_img.width > 0 and result_img.height > 0


def test_crop_max_dimension_scaling():
    """
    Req 3.6: Tam görüntü (0,0,200,200) kırpılıp max_dimension_px=50
    verildiğinde dönen görüntünün uzun kenarı ≤ 50 piksel olmalıdır.
    """
    img_b64 = make_b64_image(200, 200)
    payload = {
        "image": img_b64,
        "bbox": {"x": 0, "y": 0, "width": 200, "height": 200},
        "margin": 0,
        "max_dimension_px": 50,
        "compression_quality": 0.85,
    }
    resp = client.post("/image/crop", json=payload, headers=AUTH_HEADERS)
    assert resp.status_code == 200
    data = resp.json()
    result_img = Image.open(io.BytesIO(base64.b64decode(data["image"])))
    assert max(result_img.width, result_img.height) <= 50


def test_crop_jpeg_compression():
    """
    Req 26.1: compression_quality=0.5 verildiğinde yanıttaki format
    alanı 'jpeg' olmalıdır.
    """
    img_b64 = make_b64_image(200, 200)
    payload = {
        "image": img_b64,
        "bbox": {"x": 10, "y": 10, "width": 50, "height": 50},
        "margin": 5,
        "compression_quality": 0.5,
    }
    resp = client.post("/image/crop", json=payload, headers=AUTH_HEADERS)
    assert resp.status_code == 200
    data = resp.json()
    assert data["format"] == "jpeg"


def test_crop_png_output():
    """
    Req 26.2: compression_quality=null gönderildiğinde yanıttaki
    format alanı 'png' olmalıdır.

    Not: CropRequest.compression_quality default değeri 0.85 olduğundan,
    PNG çıktısı için key'i omit etmek yeterli değildir; null açıkça
    gönderilmelidir.
    """
    img_b64 = make_b64_image(200, 200)
    # compression_quality key'i bilinçli olarak payload'a dahil edilmez;
    # null olarak göndermek için açıkça None atanır
    body = {"image": img_b64, "bbox": {"x": 0, "y": 0, "width": 100, "height": 100}, "margin": 5}
    # compression_quality omit → default 0.85 gelir, bu yüzden null göndermek gerekir
    body_with_null = {**body, "compression_quality": None}
    resp = client.post("/image/crop", json=body_with_null, headers=AUTH_HEADERS)
    assert resp.status_code == 200
    data = resp.json()
    assert data["format"] == "png"


# ── Stitch Testleri ───────────────────────────────────────────────────────────

def test_stitch_two_images():
    """
    Req 2.10: 2 görüntü (her biri 200×100) gap_px=5 ile dikey birleştirildiğinde
    sonuç görüntü yüksekliği >= 205 piksel olmalıdır.
    """
    img1_b64 = make_b64_image(200, 100)
    img2_b64 = make_b64_image(200, 100)
    payload = {
        "images": [img1_b64, img2_b64],
        "direction": "vertical",
        "gap_px": 5,
    }
    resp = client.post("/image/stitch", json=payload, headers=AUTH_HEADERS)
    assert resp.status_code == 200
    data = resp.json()
    result_img = Image.open(io.BytesIO(base64.b64decode(data["image"])))
    assert result_img.height >= 205


def test_stitch_single_image():
    """
    Req 3.6: Tek görüntü gönderildiğinde geçerli Base64 yanıt dönmeli
    ve format 'jpeg' olmalıdır.
    """
    img_b64 = make_b64_image(200, 200)
    payload = {
        "images": [img_b64],
        "direction": "vertical",
        "gap_px": 2,
    }
    resp = client.post("/image/stitch", json=payload, headers=AUTH_HEADERS)
    assert resp.status_code == 200
    data = resp.json()
    assert data["format"] == "jpeg"
    result_img = Image.open(io.BytesIO(base64.b64decode(data["image"])))
    assert result_img.width > 0 and result_img.height > 0


# ── Auth Testleri ─────────────────────────────────────────────────────────────

def test_crop_no_auth():
    """
    Req 26.1: /image/crop Authorization header olmadan istek 401 döndürmelidir.
    """
    img_b64 = make_b64_image(200, 200)
    payload = {
        "image": img_b64,
        "bbox": {"x": 10, "y": 10, "width": 50, "height": 50},
        "margin": 5,
    }
    resp = client.post("/image/crop", json=payload)
    assert resp.status_code == 401


def test_stitch_no_auth():
    """
    Req 26.2: /image/stitch Authorization header olmadan istek 401 döndürmelidir.
    """
    img_b64 = make_b64_image(200, 200)
    payload = {
        "images": [img_b64, img_b64],
        "direction": "vertical",
        "gap_px": 2,
    }
    resp = client.post("/image/stitch", json=payload)
    assert resp.status_code == 401
