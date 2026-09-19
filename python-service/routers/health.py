"""
routers/health.py — /health endpoint'i.

GET /health → {"status": "ok"} 200
Auth gerektirmez (Req 27.1, 27.2, 27.3).
"""

from __future__ import annotations

from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter(tags=["health"])


class HealthResponse(BaseModel):
    status: str


@router.get(
    "/health",
    response_model=HealthResponse,
    summary="Servis sağlık kontrolü",
    description="Servisin çalışır durumda olduğunu doğrular. Kimlik doğrulama gerektirmez.",
)
async def health_check() -> HealthResponse:
    """Servis sağlık durumunu döndürür."""
    return HealthResponse(status="ok")
