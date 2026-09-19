from __future__ import annotations
import base64, io, logging
from exceptions import OcrServiceException
from models import BoundingBoxModel, OcrBlock, OcrRequest, OcrResponse
from .base import OcrAdapter

logger = logging.getLogger(__name__)

class MistralOcrAdapter(OcrAdapter):
    MISTRAL_OCR_URL = "https://api.mistral.ai/v1/ocr"

    def __init__(self, api_key: str) -> None:
        self._api_key = api_key

    def get_adapter_name(self) -> str:
        return "MistralOcr"

    async def perform_ocr(self, request: OcrRequest) -> OcrResponse:
        try:
            import httpx
            from PIL import Image
        except ImportError as e:
            raise OcrServiceException(f"Missing dependency: {e}")
        try:
            img_bytes = base64.b64decode(request.image)
            pil = Image.open(io.BytesIO(img_bytes)).convert("RGB")
            page_width, page_height = pil.size
            img_b64 = base64.b64encode(img_bytes).decode()
        except Exception as e:
            raise OcrServiceException(f"Image decode failed: {e}")
        payload = {
            "model": "mistral-ocr-latest",
            "document": {"type": "image_url",
                         "image_url": f"data:image/jpeg;base64,{img_b64}"}
        }
        hdrs = {"Authorization": f"Bearer {self._api_key}", "Content-Type": "application/json"}
        try:
            async with httpx.AsyncClient(timeout=60.0) as client:
                resp = await client.post(self.MISTRAL_OCR_URL, json=payload, headers=hdrs)
                if resp.status_code != 200:
                    raise OcrServiceException(f"Mistral OCR failed: {resp.status_code}")
                data = resp.json()
                pages = data.get("pages", [])
                markdown = pages[0].get("markdown", "") if pages else ""
                block = OcrBlock(type="text",
                    bbox=BoundingBoxModel(x=0, y=0, width=page_width, height=page_height),
                    content=markdown, confidence=None)
                return OcrResponse(blocks=[block], page_width=page_width, page_height=page_height)
        except OcrServiceException:
            raise
        except Exception as e:
            raise OcrServiceException(f"Mistral OCR error: {e}")
