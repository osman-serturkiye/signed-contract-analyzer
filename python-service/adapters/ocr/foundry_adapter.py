from __future__ import annotations
import base64, io, logging
from exceptions import OcrServiceException
from models import BoundingBoxModel, OcrBlock, OcrRequest, OcrResponse
from .base import OcrAdapter

logger = logging.getLogger(__name__)

class FoundryOcrAdapter(OcrAdapter):
    def __init__(self, endpoint: str, api_key: str, model: str = "gpt-4o") -> None:
        self._endpoint = endpoint.rstrip("/")
        self._api_key = api_key
        self._model = model

    def get_adapter_name(self) -> str:
        return "MicrosoftFoundry"

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
            "model": self._model,
            "messages": [{"role": "user", "content": [
                {"type": "text", "text": "Extract all text from this document image. Return plain text only."},
                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{img_b64}"}}
            ]}],
            "max_tokens": 4096
        }
        url = f"{self._endpoint}/openai/deployments/{self._model}/chat/completions?api-version=2024-02-01"
        hdrs = {"api-key": self._api_key, "Content-Type": "application/json"}
        try:
            async with httpx.AsyncClient(timeout=60.0) as client:
                resp = await client.post(url, json=payload, headers=hdrs)
                if resp.status_code != 200:
                    raise OcrServiceException(f"Foundry OCR failed: {resp.status_code}")
                text = resp.json()["choices"][0]["message"]["content"]
                block = OcrBlock(type="text",
                    bbox=BoundingBoxModel(x=0, y=0, width=page_width, height=page_height),
                    content=text, confidence=None)
                return OcrResponse(blocks=[block], page_width=page_width, page_height=page_height)
        except OcrServiceException:
            raise
        except Exception as e:
            raise OcrServiceException(f"Foundry OCR error: {e}")
