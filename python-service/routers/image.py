"""
routers/image.py — Görüntü kırpma ve birleştirme endpoint'leri.

POST /image/crop  — Bounding box koordinatlarına göre sayfa görüntüsü kırpar.
POST /image/stitch — Birden fazla görüntüyü dikey olarak birleştirir.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status

from auth import require_api_key
from models import CropRequest, CropResponse, StitchRequest, StitchResponse
from services.image_service import ImageService

router = APIRouter()
_service = ImageService()


@router.post("/crop", response_model=CropResponse)
async def image_crop(
    request: CropRequest,
    api_key: str = Depends(require_api_key),
) -> CropResponse:
    """
    Sayfa görüntüsünü verilen bounding box + margin ile kırpar.

    - Kırpma alanı sayfa sınırlarını hiçbir zaman aşmaz (margin clamping).
    - Sonuç görüntü bellek içi (io.BytesIO) işlenir; disk dosyası oluşturulmaz.
    """
    try:
        return _service.crop(
            image_b64=request.image,
            bbox=request.bbox,
            margin=request.margin,
            max_dimension_px=request.max_dimension_px,
            compression_quality=request.compression_quality,
        )
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={
                "error": "CROP_FAILED",
                "detail": str(exc),
                "component": "image_service",
            },
        ) from exc


@router.post("/stitch", response_model=StitchResponse)
async def image_stitch(
    request: StitchRequest,
    api_key: str = Depends(require_api_key),
) -> StitchResponse:
    """
    Sıralı görüntü listesini dikey olarak birleştirir.

    - Görüntüler arasına gap_px kadar beyaz boşluk eklenir.
    - Tüm işlem bellek içi (io.BytesIO) yapılır; disk dosyası oluşturulmaz.
    """
    try:
        return _service.stitch(
            images_b64=request.images,
            direction=request.direction,
            gap_px=request.gap_px,
        )
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={
                "error": "STITCH_FAILED",
                "detail": str(exc),
                "component": "image_service",
            },
        ) from exc
