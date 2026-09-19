from __future__ import annotations
import json, logging
from models import AiCompareRequest, AiCompareResponse
from .base import AiAdapter

logger = logging.getLogger(__name__)

class OpenRouterAiAdapter(AiAdapter):
    URL = "https://openrouter.ai/api/v1/chat/completions"
    def __init__(self, api_key: str, model: str = "openai/gpt-4o-mini"):
        self._api_key = api_key; self._model = model
    def get_adapter_name(self) -> str: return "OpenRouterAI"
    async def compare(self, request: AiCompareRequest) -> AiCompareResponse:
        try:
            import httpx
            payload = {"model": self._model,
                "messages": [{"role": "system", "content": "Compare clauses. Return ONLY JSON: {\"changes\": bool, \"result\": \"markdown\"}"},
                              {"role": "user", "content": f"Signed:\n{request.signed_content}\n\nOriginal:\n{request.original_content}"}]}
            async with httpx.AsyncClient(timeout=30.0) as client:
                resp = await client.post(self.URL, json=payload,
                    headers={"Authorization": f"Bearer {self._api_key}", "Content-Type": "application/json"})
                resp.raise_for_status()
                data = json.loads(resp.json()["choices"][0]["message"]["content"])
                return AiCompareResponse(changes=bool(data.get("changes")), result=str(data.get("result", "")))
        except Exception as e:
            return AiCompareResponse(changes=None, result=f"OpenRouter error: {e}")
