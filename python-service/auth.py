"""
auth.py — API key doğrulama dependency'si.

Kullanım:
    from auth import require_api_key
    @router.post("/endpoint")
    async def endpoint(api_key: str = Depends(require_api_key)):
        ...

Geçersiz veya eksik Authorization: Bearer <key> header'ı → 401 Unauthorized.
"""

from __future__ import annotations

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from config import Settings, get_settings

# HTTPBearer şeması; auto_error=False → eksik header'da kendiliğinden 403 atmasın,
# biz kendin yönetelim ki 401 dönebilelim.
_bearer_scheme = HTTPBearer(auto_error=False)


async def require_api_key(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer_scheme),
    settings: Settings = Depends(get_settings),
) -> str:
    """
    `Authorization: Bearer <key>` header'ını doğrular.

    - Header yoksa veya Bearer şeması kullanılmamışsa → 401
    - Token ayarlanan SERVICE_API_KEY ile eşleşmiyorsa → 401
    - Doğrulama başarılıysa token string'ini döndürür.
    """
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Kimlik doğrulama başarısız: Authorization: Bearer <key> header'ı gereklidir.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    if credentials.credentials != settings.SERVICE_API_KEY:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Kimlik doğrulama başarısız: Geçersiz API anahtarı.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    return credentials.credentials
