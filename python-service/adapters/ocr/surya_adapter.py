"""
adapters/ocr/surya_adapter.py — Surya OCR 2 VLM adaptörü.

Surya yüklü değilse ImportError yerine OcrServiceException fırlatır.
Model ilk kullanımda lazy olarak yüklenir.
"""

from __future__ import annotations

import base64
import io
import logging
from typing import Any, Dict, List, Optional, Tuple

from exceptions import OcrServiceException
from models import BoundingBoxModel, OcrBlock, OcrRequest, OcrResponse

from .base import OcrAdapter

logger = logging.getLogger(__name__)


# Surya layout türü → OcrBlock.type eşlemesi
_LAYOUT_TYPE_MAP: Dict[str, str] = {
    "Text": "text",
    "TextInlineMath": "text",
    "Title": "title",
    "SectionHeader": "title",
    "Caption": "paragraph",
    "Footnote": "paragraph",
    "Paragraph": "paragraph",
    "Table": "table",
    "Figure": "image",
    "Picture": "image",
    "FigureGroup": "image",
    "Form": "paragraph",
    "Handwriting": "signature",
    "Formula": "text",
    "Code": "text",
    "ListItem": "text",
    "PageFooter": "text",
    "PageHeader": "title",
    "TOCItem": "text",
    "Document": "text",
}

_DEFAULT_LAYOUT_TYPE = "text"


def _map_layout_type(surya_type: str) -> str:
    """Surya layout türünü OcrBlock türüne çevirir."""
    return _LAYOUT_TYPE_MAP.get(surya_type, _DEFAULT_LAYOUT_TYPE)


def _bbox_from_surya(bbox_raw: Any) -> Optional[BoundingBoxModel]:
    """
    Surya bbox [x1, y1, x2, y2] formatını BoundingBoxModel'e çevirir.
    """
    try:
        if isinstance(bbox_raw, (list, tuple)) and len(bbox_raw) == 4:
            x1, y1, x2, y2 = (float(v) for v in bbox_raw)
            x = int(x1)
            y = int(y1)
            w = max(int(x2 - x1), 1)
            h = max(int(y2 - y1), 1)
            return BoundingBoxModel(x=x, y=y, width=w, height=h)
        return None
    except Exception:
        return None


def _lines_to_text(lines: Any) -> str:
    """Surya OCR satırlarından metin içeriğini birleştirir."""
    if not isinstance(lines, (list, tuple)):
        return ""
    parts: List[str] = []
    for line in lines:
        if isinstance(line, dict):
            spans = line.get("spans") or []
            if isinstance(spans, (list, tuple)):
                for span in spans:
                    if isinstance(span, dict):
                        text = span.get("text", "")
                        if text:
                            parts.append(str(text))
            else:
                text = line.get("text", "")
                if text:
                    parts.append(str(text))
        elif hasattr(line, "text"):
            parts.append(str(line.text))
    return " ".join(parts)


def _extract_text_from_ocr_result(ocr_result: Any, bbox: Optional[BoundingBoxModel]) -> str:
    """
    Surya OCR sonucundan bbox ile örtüşen metin içeriğini çıkarır.
    ocr_result; TextLine listesi veya dict listesi olabilir.
    """
    if ocr_result is None:
        return ""

    # Surya OCRResult nesnesi
    if hasattr(ocr_result, "text_lines"):
        lines = ocr_result.text_lines
    elif isinstance(ocr_result, dict):
        lines = ocr_result.get("text_lines", [])
    elif isinstance(ocr_result, (list, tuple)):
        lines = ocr_result
    else:
        return str(ocr_result)

    if bbox is None:
        # Tüm metni birleştir
        return _lines_to_text(lines)

    # Bbox ile örtüşen satırları filtrele
    filtered_parts: List[str] = []
    for line in lines:
        line_bbox = None
        if isinstance(line, dict):
            line_bbox = line.get("bbox")
            line_text = line.get("text", "")
        elif hasattr(line, "bbox"):
            line_bbox = line.bbox
            line_text = getattr(line, "text", "")
        else:
            continue

        if line_bbox is not None and _bboxes_overlap(bbox, line_bbox):
            if line_text:
                filtered_parts.append(str(line_text))
        elif line_bbox is None and line_text:
            filtered_parts.append(str(line_text))

    return " ".join(filtered_parts)


def _bboxes_overlap(block_bbox: BoundingBoxModel, line_bbox: Any) -> bool:
    """İki bbox arasındaki örtüşmeyi kontrol eder."""
    try:
        if isinstance(line_bbox, (list, tuple)) and len(line_bbox) == 4:
            lx1, ly1, lx2, ly2 = (float(v) for v in line_bbox)
        elif hasattr(line_bbox, "bbox"):
            inner = line_bbox.bbox
            lx1, ly1, lx2, ly2 = (float(v) for v in inner)
        else:
            return True  # Bilinmeyen format; dahil et

        bx1 = float(block_bbox.x)
        by1 = float(block_bbox.y)
        bx2 = float(block_bbox.x + block_bbox.width)
        by2 = float(block_bbox.y + block_bbox.height)

        # Örtüşme kontrolü
        return not (lx2 <= bx1 or lx1 >= bx2 or ly2 <= by1 or ly1 >= by2)
    except Exception:
        return True


class SuryaAdapter(OcrAdapter):
    """
    Surya OCR 2 VLM adaptörü.

    Lazy initialization: modeller ilk `perform_ocr` çağrısında yüklenir.
    Surya kurulu değilse `OcrServiceException` fırlatır.
    """

    def __init__(self) -> None:
        self._ocr_model: Any = None
        self._ocr_processor: Any = None
        self._layout_model: Any = None
        self._layout_processor: Any = None
        self._initialized = False

    def _ensure_initialized(self) -> None:
        """Surya modellerini lazily başlatır."""
        if self._initialized:
            return

        try:
            from surya.model.detection.model import load_model as load_det_model  # type: ignore[import]
            from surya.model.detection.processor import load_processor as load_det_processor  # type: ignore[import]
            from surya.model.recognition.model import load_model as load_rec_model  # type: ignore[import]
            from surya.model.recognition.processor import load_processor as load_rec_processor  # type: ignore[import]
        except ImportError as exc:
            raise OcrServiceException(
                "Surya kurulu değil. Lütfen 'pip install surya-ocr' komutunu çalıştırın. "
                f"Detay: {exc}"
            ) from exc

        try:
            logger.info("Surya OCR modelleri yükleniyor...")
            self._ocr_model = load_rec_model()
            self._ocr_processor = load_rec_processor()
            self._layout_model = load_det_model()
            self._layout_processor = load_det_processor()
            self._initialized = True
            logger.info("Surya OCR modelleri hazır.")
        except Exception as exc:
            raise OcrServiceException(
                f"Surya modelleri yüklenemedi: {exc}"
            ) from exc

    async def perform_ocr(self, request: OcrRequest) -> OcrResponse:
        """
        Base64 görüntüyü Surya layout + OCR pipeline ile işler ve OcrResponse döndürür.

        Raises:
            OcrServiceException: Surya kurulu değilse veya işlem başarısız olursa.
        """
        try:
            from PIL import Image  # type: ignore[import]
        except ImportError as exc:
            raise OcrServiceException(
                f"Gerekli bağımlılık eksik (Pillow): {exc}"
            ) from exc

        # Base64 → PIL Image
        try:
            img_bytes = base64.b64decode(request.image)
            pil_image = Image.open(io.BytesIO(img_bytes)).convert("RGB")
            page_width, page_height = pil_image.size
        except Exception as exc:
            raise OcrServiceException(f"Görüntü çözümlenemedi: {exc}") from exc

        # Modelleri başlat (lazy)
        self._ensure_initialized()

        try:
            from surya.layout import batch_layout_detection  # type: ignore[import]
            from surya.ocr import run_ocr  # type: ignore[import]
        except ImportError as exc:
            raise OcrServiceException(
                "Surya kurulu değil. Lütfen 'pip install surya-ocr' komutunu çalıştırın. "
                f"Detay: {exc}"
            ) from exc

        # Layout tespiti
        try:
            logger.debug("Surya layout tespiti çalıştırılıyor (%dx%d)...", page_width, page_height)
            layout_results = batch_layout_detection(
                [pil_image],
                self._layout_model,
                self._layout_processor,
            )
            layout_result = layout_results[0] if layout_results else None
        except Exception as exc:
            raise OcrServiceException(f"Surya layout tespiti başarısız: {exc}") from exc

        # OCR çalıştır
        try:
            langs = [request.language] if request.language else ["tr"]
            logger.debug("Surya OCR çalıştırılıyor (lang=%s)...", langs)
            ocr_results = run_ocr(
                [pil_image],
                [langs],
                self._layout_model,
                self._layout_processor,
                self._ocr_model,
                self._ocr_processor,
            )
            ocr_result = ocr_results[0] if ocr_results else None
        except Exception as exc:
            raise OcrServiceException(f"Surya OCR işlemi başarısız: {exc}") from exc

        # Sonuçları dönüştür
        blocks = self._build_blocks(layout_result, ocr_result, page_width, page_height)

        logger.debug("Surya tamamlandı: %d blok tespit edildi.", len(blocks))

        return OcrResponse(
            blocks=blocks,
            page_width=page_width,
            page_height=page_height,
        )

    def _build_blocks(
        self,
        layout_result: Any,
        ocr_result: Any,
        page_width: int,
        page_height: int,
    ) -> List[OcrBlock]:
        """Layout ve OCR sonuçlarını OcrBlock listesine çevirir."""
        blocks: List[OcrBlock] = []

        # Layout bbox'larını al
        layout_bboxes: List[Tuple[Any, str]] = []
        if layout_result is not None:
            layout_items = None
            if hasattr(layout_result, "bboxes"):
                layout_items = layout_result.bboxes
            elif isinstance(layout_result, dict):
                layout_items = layout_result.get("bboxes", [])
            elif isinstance(layout_result, (list, tuple)):
                layout_items = layout_result

            if layout_items:
                for item in layout_items:
                    if isinstance(item, dict):
                        bbox_raw = item.get("bbox")
                        ltype = item.get("label", "Text")
                    elif hasattr(item, "bbox"):
                        bbox_raw = item.bbox
                        ltype = getattr(item, "label", "Text")
                    else:
                        continue
                    layout_bboxes.append((bbox_raw, str(ltype)))

        if not layout_bboxes:
            # Layout bilgisi yoksa tüm OCR metnini tek blok olarak döndür
            full_text = _extract_text_from_ocr_result(ocr_result, None)
            if full_text.strip():
                blocks.append(
                    OcrBlock(
                        type="text",
                        bbox=BoundingBoxModel(x=0, y=0, width=page_width, height=page_height),
                        content=full_text.strip(),
                        confidence=None,
                    )
                )
            return blocks

        for bbox_raw, layout_type in layout_bboxes:
            bbox = _bbox_from_surya(bbox_raw)
            if bbox is None:
                bbox = BoundingBoxModel(x=0, y=0, width=page_width, height=page_height)

            block_type = _map_layout_type(layout_type)
            content = _extract_text_from_ocr_result(ocr_result, bbox)

            blocks.append(
                OcrBlock(
                    type=block_type,
                    bbox=bbox,
                    content=content.strip(),
                    confidence=None,  # Surya blok düzeyinde güven skoru sunmaz
                )
            )

        return blocks

    def get_adapter_name(self) -> str:
        return "Surya"
