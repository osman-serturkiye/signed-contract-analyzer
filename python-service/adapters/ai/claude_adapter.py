from __future__ import annotations
import json, logging
from models import AiCompareRequest, AiCompareResponse
from .base import AiAdapter

logger = logging.getLogger(__name__)

class ClaudeAiAdapter(AiAdapter):
    def __init__(self, api_key: str, model: str = "claude-3-5-haiku-20241022"):
        self._api_key = api_key; self._model = model
    def get_adapter_name(self) -> str: return "ClaudeAI"
    async def compare(self, request: AiCompareRequest) -> AiCompareResponse:
        try:
            import anthropic
            client = anthropic.AsyncAnthropic(api_key=self._api_key)
            msg = await client.messages.create(
                model=self._model, max_tokens=1024,
                system="Compare clauses and return ONLY JSON: {\"changes\": bool, \"result\": \"markdown\"}",
                messages=[{"role": "user", "content": f"Signed:\n{request.signed_content}\n\nOriginal:\n{request.original_content}"}])
            text = msg.content[0].text if msg.content else "{}"
            data = json.loads(text)
            return AiCompareResponse(changes=bool(data.get("changes")), result=str(data.get("result", "")))
        except Exception as e:
            return AiCompareResponse(changes=None, result=f"Claude AI error: {e}")
