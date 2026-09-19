from __future__ import annotations
from abc import ABC, abstractmethod

class AiAdapter(ABC):
    @abstractmethod
    async def compare(self, request) -> object: ...
    @abstractmethod
    def get_adapter_name(self) -> str: ...
