from __future__ import annotations
import json, logging
from models import AiCompareRequest, AiCompareResponse
from .base import AiAdapter

logger = logging.getLogger(__name__)
_SYS = ("You are a legal contract analyst. Compare the signed and original clause. "
        "Return ONLY valid JSON: {\"changes\": true/false, \"result\": \"markdown explanation\"}")

class AzureFoundryAiAdapter(AiAdapter):
    def __init__(self, endpoint: str, api_key: str, model: str = "gpt-4o"):
        self._endpoint = endpoint; self._api_key = api_key; self._model = model
    def get_adapter_name(self) -> str: return "AzureFoundryAI"
    async def compare(self, request: AiCompareRequest) -> AiCompareResponse:
        try:
            import openai
            client = openai.AsyncAzureOpenAI(azure_endpoint=self._endpoint, api_key=self._api_key, api_version="2024-02-01")
            resp = await client.chat.completions.create(
                model=self._model, response_format={"type": "json_object"},
                messages=[{"role": "system", "content": _SYS},
                          {"role": "user", "content": f"Signed:\n{request.signed_content}\n\nOriginal:\n{request.original_content}"}])
            data = json.loads(resp.choices[0].message.content)
            return AiCompareResponse(changes=bool(data.get("changes")), result=str(data.get("result", "")))
        except Exception as e:
            return AiCompareResponse(changes=None, result=f"Azure AI error: {e}")
