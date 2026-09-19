"""
image_service.py — Pillow tabanlı görüntü kırpma ve birleştirme servisi.

Tüm işlemler bellek içi (io.BytesIO) yapılır; geçici disk dosyası kullanılmaz.
"""

from __future__ import annotations

import base64
import io
from typing import List, Optional

from PIL import Image

from models import BoundingBoxModel, CropResponse, StitchResponse


class ImageService:
    """Görüntü kırpma (crop) ve birleştirme (stitch) işlemlerini yönetir."""

    # ── Yardımcı metodlar ─────────────────────────────────────────────────────

    @staticmethod
    def _decode_image(image_b64: str) -> Image.Image:
        """Base64 kodlu görüntüyü PIL Image nesnesine dönüştürür."""
        raw = base64.b64decode(image_b64)
        return Image.open(io.BytesIO(raw)).convert("RGB")

    @staticmethod
    def _encode_image(image: Image.Image, fmt: str, quality: Optional[int] = None) -> str:
        """PIL Image'ı Base64 string'e dönüştürür."""
        buf = io.BytesIO()
        if fmt.upper() == "JPEG":
            save_kwargs: dict = {"format": "JPEG"}
            if quality is not None:
                save_kwargs["quality"] = quality
            image.save(buf, **save_kwargs)
        else:
            image.save(buf, format="PNG")
        return base64.b64encode(buf.getvalue()).decode("ascii")

    # ── Crop ──────────────────────────────────────────────────────────────────

    def crop(
        self,
        image_b64: str,
        bbox: BoundingBoxModel,
        margin: int,
        max_dimension_px: Optional[int],
        compression_quality: Optional[float],
    ) -> CropResponse:
        """
        Verilen Base64 görüntüsünü bbox koordinatları + margin ile kırpar.

        Args:
            image_b64: Base64 kodlu kaynak görüntü.
            bbox: Kırpma alanı koordinatları (x, y, width, height).
            margin: Her yöne eklenecek piksel miktarı (sınır aşılmaz).
            max_dimension_px: Kırpılmış görüntünün uzun kenar limiti (opsiyonel).
            compression_quality: JPEG kalitesi 0.0–1.0 (opsiyonel; None → PNG).

        Returns:
            CropResponse: Base64 kırpılmış görüntü ve format ("jpeg"|"png").
        """
        img = self._decode_image(image_b64)
        img_w, img_h = img.size

        # Margin uygulanmış kırpma koordinatları — sayfa sınırlarını aşmaz
        x1 = max(0, bbox.x - margin)
        y1 = max(0, bbox.y - margin)
        x2 = min(img_w, bbox.x + bbox.width + margin)
        y2 = min(img_h, bbox.y + bbox.height + margin)

        # Geçersiz bbox durumunda tüm görüntüyü koru
        if x2 <= x1:
            x1, x2 = 0, img_w
        if y2 <= y1:
            y1, y2 = 0, img_h

        cropped = img.crop((x1, y1, x2, y2))

        # max_dimension_px: uzun kenar limiti (en-boy oranı korunur)
        if max_dimension_px is not None and max_dimension_px > 0:
            cw, ch = cropped.size
            longest = max(cw, ch)
            if longest > max_dimension_px:
                scale = max_dimension_px / longest
                new_w = max(1, int(cw * scale))
                new_h = max(1, int(ch * scale))
                cropped = cropped.resize((new_w, new_h), Image.LANCZOS)

        # Sıkıştırma kalitesi verildiyse JPEG, aksi hâlde PNG
        if compression_quality is not None:
            # 0.0–1.0 aralığını Pillow'un 1–95 JPEG kalite ölçeğine dönüştür
            jpeg_quality = max(1, min(95, int(compression_quality * 95)))
            encoded = self._encode_image(cropped, "JPEG", quality=jpeg_quality)
            fmt = "jpeg"
        else:
            encoded = self._encode_image(cropped, "PNG")
            fmt = "png"

        return CropResponse(image=encoded, format=fmt)

    # ── Stitch ────────────────────────────────────────────────────────────────

    def stitch(
        self,
        images_b64: List[str],
        direction: str,
        gap_px: int,
    ) -> StitchResponse:
        """
        Birden fazla Base64 görüntüyü dikey olarak birleştirir.

        Args:
            images_b64: Sıralı Base64 görüntü listesi.
            direction: Birleştirme yönü ("vertical" desteklenir).
            gap_px: Görüntüler arasındaki beyaz boşluk (piksel).

        Returns:
            StitchResponse: Base64 birleştirilmiş görüntü ve format ("jpeg").
        """
        if not images_b64:
            # Boş liste → 1×1 beyaz JPEG döndür
            blank = Image.new("RGB", (1, 1), color=(255, 255, 255))
            encoded = self._encode_image(blank, "JPEG", quality=85)
            return StitchResponse(image=encoded, format="jpeg")

        images = [self._decode_image(b64) for b64 in images_b64]

        if len(images) == 1:
            encoded = self._encode_image(images[0], "JPEG", quality=85)
            return StitchResponse(image=encoded, format="jpeg")

        # Dikey birleştirme: tüm görüntülerin maksimum genişliği
        max_width = max(im.size[0] for im in images)
        total_height = sum(im.size[1] for im in images) + gap_px * (len(images) - 1)

        canvas = Image.new("RGB", (max_width, total_height), color=(255, 255, 255))

        y_offset = 0
        for im in images:
            canvas.paste(im, (0, y_offset))
            y_offset += im.size[1] + gap_px

        encoded = self._encode_image(canvas, "JPEG", quality=85)
        return StitchResponse(image=encoded, format="jpeg")
