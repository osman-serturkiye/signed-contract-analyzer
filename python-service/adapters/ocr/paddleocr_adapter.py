"""
adapters/ocr/paddleocr_adapter.py — PaddleOCR PP-StructureV3 adaptörü.

PaddleOCR yüklü değilse ImportError yerine OcrServiceException fırlatır.
Model ilk kullanımda lazy olarak yüklenir.
"""

from __future__ import annotations

import base64
import io
import logging
from typing import Any, Dict, List, Optional

from exceptions import OcrServiceException
from models import BoundingBoxModel, OcrBlock, OcrRequest, OcrResponse

from .base import OcrAdapter

logger = logging.getLogger(__name__)


# PP-Structure blok türü → OcrBlock.type eşlemesi
_TYPE_MAP: Dict[str, str] = {
    "text": "text",
    "title": "title",
    "paragraph": "paragraph",
    "table": "table",
    "figure": "image",
    "image": "image",
    "figure_caption": "text",
    "reference": "text",
    "equation": "text",
    "header": "title",
    "footer": "text",
    "seal": "signature",
    "signature": "signature",
    "handwriting": "signature",
}

_DEFAULT_TYPE = "text"


def _map_type(pp_type: str) -> str:
    """PP-Structure blok türünü OcrBlock türüne çevirir."""
    return _TYPE_MAP.get(pp_type.lower(), _DEFAULT_TYPE)


def _bbox_from_pp(bbox_raw: Any) -> Optional[BoundingBoxModel]:
    """
    PP-Structure bbox formatını BoundingBoxModel'e çevirir.

    PP-Structure v3 bbox formatları:
      - [x1, y1, x2, y2]  (xyxy)
      - [[x1,y1],[x2,y1],[x2,y2],[x1,y2]]  (quad)
    """
    try:
        if isinstance(bbox_raw, (list, tuple)):
            flat = bbox_raw
            # Quad format → düzleştir
            if len(flat) == 4 and isinstance(flat[0], (list, tuple)):
                xs = [p[0] for p in flat]
                ys = [p[1] for p in flat]
                x = int(min(xs))
                y = int(min(ys))
                w = int(max(xs) - x)
                h = int(max(ys) - y)
            elif len(flat) == 4:
                x, y, x2, y2 = (int(v) for v in flat)
                w = x2 - x
                h = y2 - y
            else:
                return None
            return BoundingBoxModel(x=x, y=y, width=max(w, 1), height=max(h, 1))
        return None
    except Exception:
        return None


def _table_to_markdown(table_html: str) -> str:
    """Basit HTML tablo → Markdown tablo dönüşümü."""
    try:
        # html.parser ile işle; gerekli paket standart kütüphane
        from html.parser import HTMLParser

        class _TableParser(HTMLParser):
            def __init__(self) -> None:
                super().__init__()
                self.rows: List[List[str]] = []
                self._current_row: List[str] = []
                self._current_cell: List[str] = []
                self._in_cell = False

            def handle_starttag(self, tag: str, attrs: Any) -> None:
                if tag in ("tr",):
                    self._current_row = []
                elif tag in ("td", "th"):
                    self._current_cell = []
                    self._in_cell = True

            def handle_endtag(self, tag: str) -> None:
                if tag in ("td", "th"):
                    self._current_row.append(" ".join(self._current_cell).strip())
                    self._in_cell = False
                elif tag == "tr":
                    if self._current_row:
                        self.rows.append(self._current_row)

            def handle_data(self, data: str) -> None:
                if self._in_cell:
                    self._current_cell.append(data)

        parser = _TableParser()
        parser.feed(table_html)

        if not parser.rows:
            return table_html  # Ham HTML'i döndür

        # Markdown tablo oluştur
        lines: List[str] = []
        header = parser.rows[0]
        lines.append("| " + " | ".join(header) + " |")
        lines.append("| " + " | ".join(["---"] * len(header)) + " |")
        for row in parser.rows[1:]:
            # Sütun sayısını header ile hizala
            padded = row + [""] * (len(header) - len(row))
            lines.append("| " + " | ".join(padded[: len(header)]) + " |")
        return "\n".join(lines)
    except Exception:
        return table_html


def _extract_blocks_from_pp_result(result: Any, page_width: int, page_height: int) -> List[OcrBlock]:
    """
    PP-StructureV3 pipeline çıktısını OcrBlock listesine dönüştürür.

    PP-Structure v3 çıktı formatı (per-page list):
      [
        {
          "type": "text" | "title" | "table" | ...,
          "bbox": [x1, y1, x2, y2],
          "res": <içerik dict veya str>
        },
        ...
      ]
    """
    blocks: List[OcrBlock] = []

    if not isinstance(result, (list, tuple)):
        return blocks

    for item in result:
        if not isinstance(item, dict):
            continue

        raw_type = item.get("type", "text")
        block_type = _map_type(str(raw_type))
        bbox_raw = item.get("bbox") or item.get("layout_bbox")
        bbox = _bbox_from_pp(bbox_raw)

        if bbox is None:
            # bbox yoksa tüm sayfa
            bbox = BoundingBoxModel(x=0, y=0, width=page_width, height=page_height)

        # İçerik çıkarımı
        res = item.get("res", {})
        content = ""

        if block_type == "table":
            # PP-Structure tablo HTML içerir
            if isinstance(res, dict):
                html = res.get("html", "") or res.get("markdown", "")
                content = _table_to_markdown(html) if html else ""
            elif isinstance(res, str):
                content = _table_to_markdown(res)
        else:
            # Metin blokları
            if isinstance(res, dict):
                # v3 format: {"rec_res": [("text", conf), ...]}
                rec = res.get("rec_res") or res.get("text") or res.get("content")
                if isinstance(rec, (list, tuple)):
                    parts = []
                    for item2 in rec:
                        if isinstance(item2, (list, tuple)) and len(item2) >= 1:
                            parts.append(str(item2[0]))
                        elif isinstance(item2, str):
                            parts.append(item2)
                    content = " ".join(parts)
                elif isinstance(rec, str):
                    content = rec
            elif isinstance(res, str):
                content = res
            elif isinstance(res, (list, tuple)):
                # Fallback: düz liste
                content = " ".join(str(r) for r in res)

        # Güven skoru
        confidence: Optional[float] = None
        if isinstance(res, dict):
            score = res.get("score") or res.get("confidence")
            if score is not None:
                try:
                    confidence = float(score)
                except (TypeError, ValueError):
                    confidence = None

        blocks.append(
            OcrBlock(
                type=block_type,
                bbox=bbox,
                content=content.strip(),
                confidence=confidence,
            )
        )

    return blocks


class PaddleOcrAdapter(OcrAdapter):
    """
    PaddleOCR PP-StructureV3 pipeline adaptörü.

    Lazy initialization: model ilk `perform_ocr` çağrısında yüklenir.
    PaddleOCR kurulu değilse `OcrServiceException` fırlatır.
    """

    def __init__(self) -> None:
        self._pipeline: Any = None
        self._initialized = False

    def _ensure_initialized(self, lang: str = "tr") -> None:
        """PP-StructureV3 pipeline'ı lazily başlatır."""
        if self._initialized:
            return

        try:
            from paddleocr import PPStructure  # type: ignore[import]
        except ImportError as exc:
            raise OcrServiceException(
                "PaddleOCR kurulu değil. Lütfen 'pip install paddleocr' komutunu çalıştırın. "
                f"Detay: {exc}"
            ) from exc

        try:
            logger.info("PaddleOCR PP-StructureV3 pipeline başlatılıyor (lang=%s)...", lang)
            # PP-StructureV3: show_log=False ile gereksiz çıktı bastırılır
            self._pipeline = PPStructure(
                table=True,
                ocr=True,
                lang=lang,
                show_log=False,
                image_orientation=False,
                layout=True,
            )
            self._initialized = True
            logger.info("PaddleOCR PP-StructureV3 pipeline hazır.")
        except Exception as exc:
            raise OcrServiceException(
                f"PaddleOCR PP-StructureV3 pipeline başlatılamadı: {exc}"
            ) from exc

    async def perform_ocr(self, request: OcrRequest) -> OcrResponse:
        """
        Base64 görüntüyü PP-StructureV3 ile işler ve OcrResponse döndürür.

        Raises:
            OcrServiceException: PaddleOCR kurulu değilse veya işlem başarısız olursa.
        """
        try:
            import numpy as np  # type: ignore[import]
            from PIL import Image  # type: ignore[import]
        except ImportError as exc:
            raise OcrServiceException(
                f"Gerekli bağımlılık eksik (numpy veya Pillow): {exc}"
            ) from exc

        # Base64 → numpy array
        try:
            img_bytes = base64.b64decode(request.image)
            pil_image = Image.open(io.BytesIO(img_bytes)).convert("RGB")
            page_width, page_height = pil_image.size
            img_array = np.array(pil_image)
        except Exception as exc:
            raise OcrServiceException(f"Görüntü çözümlenemedi: {exc}") from exc

        # Pipeline'ı başlat (lazy)
        lang = request.language or "tr"
        self._ensure_initialized(lang)

        # PP-StructureV3 çalıştır
        try:
            logger.debug("PP-StructureV3 çalıştırılıyor (image: %dx%d)...", page_width, page_height)
            result = self._pipeline(img_array)
        except Exception as exc:
            raise OcrServiceException(f"PaddleOCR işlemi başarısız: {exc}") from exc

        # Sonuçları dönüştür
        blocks = _extract_blocks_from_pp_result(result, page_width, page_height)

        logger.debug("PP-StructureV3 tamamlandı: %d blok tespit edildi.", len(blocks))

        return OcrResponse(
            blocks=blocks,
            page_width=page_width,
            page_height=page_height,
        )

    def get_adapter_name(self) -> str:
        return "PaddleOCR"
