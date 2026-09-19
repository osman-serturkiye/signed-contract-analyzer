from __future__ import annotations
from fastapi import APIRouter, Depends
from auth import require_api_key
from models import AiCompareRequest, AiCompareResponse
from config import get_settings

router = APIRouter()

@router.post("/compare", response_model=AiCompareResponse)
async def ai_compare(request: AiCompareRequest, api_key: str = Depends(require_api_key)):
    settings = get_settings()
    name = getattr(settings, "AI_ADAPTER", "") or "OpenAI"
    key = getattr(settings, "AI_API_KEY", "")
    endpoint = getattr(settings, "AI_ENDPOINT", "")
    model = getattr(settings, "AI_MODEL", "")
    try:
        if name == "OpenAI":
            from adapters.ai.openai_adapter import OpenAiAdapter
            return await OpenAiAdapter(api_key=key, model=model or "gpt-4o-mini").compare(request)
        elif name == "AzureFoundryAI":
            from adapters.ai.azure_foundry_adapter import AzureFoundryAiAdapter
            return await AzureFoundryAiAdapter(endpoint=endpoint, api_key=key, model=model or "gpt-4o").compare(request)
        elif name == "ClaudeAI":
            from adapters.ai.claude_adapter import ClaudeAiAdapter
            return await ClaudeAiAdapter(api_key=key, model=model or "claude-3-5-haiku-20241022").compare(request)
        elif name == "OpenRouterAI":
            from adapters.ai.openrouter_adapter import OpenRouterAiAdapter
            return await OpenRouterAiAdapter(api_key=key, model=model or "openai/gpt-4o-mini").compare(request)
        else:
            return AiCompareResponse(changes=None, result=f"Unknown adapter: {name}")
    except Exception as e:
        return AiCompareResponse(changes=None, result=f"AI service error: {e}")
