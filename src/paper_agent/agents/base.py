"""Base RuntimeAdapter interface for PaperAgent."""

from __future__ import annotations

from abc import ABC, abstractmethod
from pathlib import Path
from typing import Any

from pydantic import BaseModel, Field


class AgentResponse(BaseModel):
    runtime: str
    model: str = ""
    raw_text: str = ""
    structured_output: dict[str, Any] | None = None
    duration_seconds: float = 0.0
    usage: dict[str, Any] = Field(default_factory=dict)


class RuntimeAdapter(ABC):
    name: str

    @abstractmethod
    def run(
        self,
        prompt: str,
        *,
        work_dir: Path,
        schema_path: Path | None = None,
        images: list[Path] | None = None,
        model: str | None = None,
        effort: str | None = None,
        timeout: int = 300,
    ) -> AgentResponse:
        """Execute a headless single-turn prompt and return structured/text output."""
