from __future__ import annotations
import asyncio, base64, io, logging
from typing import List
from exceptions import OcrServiceException
from models import BoundingBoxModel, OcrBlock, OcrRequest, OcrResponse
from .base import OcrAdapter

logger = logging.getLogger(__name__)

class AzureOcrAdapter(OcrAdapter):
    def __init__(self, endpoint: str, api_key: str) -> None:
        self._endpoint = endpoint.rstrip("/")
        self._api_key = api_key

    def get_adapter_name(self) -> str:
        return "AzureCognitiveService"

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
        except Exception as e:
            raise OcrServiceException(f"Image decode failed: {e}")
        url = f"{self._endpoint}/vision/v3.2/read/analyze"
        hdrs = {"Ocp-Apim-Subscription-Key": self._api_key, "Content-Type": "application/octet-stream"}
        try:
            async with httpx.AsyncClient(timeout=60.0) as client:
                resp = await client.post(url, headers=hdrs, content=img_bytes)
                if resp.status_code not in (200, 202):
                    raise OcrServiceException(f"Azure submit failed: {resp.status_code}")
                op_url = resp.headers.get("Operation-Location", "")
                if not op_url:
                    raise OcrServiceException("No Operation-Location header")
                for _ in range(15):
                    await asyncio.sleep(1)
                    r = await client.get(op_url, headers={"Ocp-Apim-Subscription-Key": self._api_key})
                    data = r.json()
                    if data.get("status") == "succeeded":
                        return OcrResponse(blocks=_parse_azure(data, page_width, page_height),
                                           page_width=page_width, page_height=page_height)
                    if data.get("status") == "failed":
                        raise OcrServiceException(f"Azure analysis failed")
                raise OcrServiceException("Azure OCR timed out")
        except OcrServiceException:
            raise
        except Exception as e:
            raise OcrServiceException(f"Azure OCR error: {e}")

def _parse_azure(result, pw, ph) -> List[OcrBlock]:
    blocks = []
    for page in result.get("analyzeResult", {}).get("readResults", []):
        for line in page.get("lines", []):
            bb = line.get("boundingBox", [])
            if len(bb) >= 8:
                xs, ys = bb[0::2], bb[1::2]
                x, y = int(min(xs)), int(min(ys))
                w, h = int(max(xs)-x), int(max(ys)-y)
            else:
                x=y=0; w=pw; h=ph
            blocks.append(OcrBlock(type="text",
                bbox=BoundingBoxModel(x=x,y=y,width=max(w,1),height=max(h,1)),
                content=line.get("text",""), confidence=None))
    return blocks
