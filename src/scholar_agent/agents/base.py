"""Base RuntimeAdapter interface and isolated stage sandbox helpers."""

from __future__ import annotations

import shutil
import tempfile
from abc import ABC, abstractmethod
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

from pydantic import BaseModel, Field

# Safe UTF-8 byte ceiling for CLI argv across Windows (32K chars) and Linux (128KB MAX_ARG_STRLEN)
MAX_INLINE_PROMPT_BYTES = 20_000


class AgentResponse(BaseModel):
    runtime: str
    model: str = ""
    raw_text: str = ""
    structured_output: dict[str, Any] | None = None
    duration_seconds: float = 0.0
    usage: dict[str, Any] = Field(default_factory=dict)


@contextmanager
def isolated_stage_dir(images: list[Path] | None = None) -> Iterator[tuple[Path, list[Path]]]:
    """Create an empty temporary directory staging ONLY explicitly provided images.

    Prevents subagents from reading sibling artifacts (`extracted.md`, `original.pdf`,
    cached JSONs) or overwriting output files (`summary.ko.md`) in `work_dir`.
    """
    stage_dir = Path(tempfile.mkdtemp(prefix="scholar_agent_stage_"))
    try:
        staged_images: list[Path] = []
        if images:
            img_dir = stage_dir / "images"
            img_dir.mkdir(parents=True, exist_ok=True)
            for idx, src in enumerate(images):
                if not src.exists():
                    continue
                dest = img_dir / f"{idx + 1:02d}_{src.name}"
                shutil.copy2(src, dest)
                staged_images.append(dest)
        yield stage_dir, staged_images
    finally:
        shutil.rmtree(stage_dir, ignore_errors=True)


def prepare_cli_prompt_delivery(
    full_prompt: str,
    stage_dir: Path,
) -> tuple[str, str | None]:
    """Split oversized prompts so CLI argv never exceeds OS limits (WinError 206 / E2BIG).

    Returns `(argv_prompt, stdin_payload)`.
    When oversized, writes `prompt_input.md` into `stage_dir` AND pipes `full_prompt` on stdin.
    """
    if len(full_prompt.encode("utf-8")) <= MAX_INLINE_PROMPT_BYTES:
        return full_prompt, None

    prompt_file = stage_dir / "prompt_input.md"
    prompt_file.write_text(full_prompt, encoding="utf-8")
    head_preview = full_prompt[:1_200].rsplit("\n", 1)[0]
    argv_prompt = (
        f"{head_preview}\n\n"
        f"[IMPORTANT: The complete prompt and source text are piped via stdin and also saved at "
        f"'{prompt_file}'. Read the complete input before responding, and do NOT write output files to disk.]"
    )
    return argv_prompt, full_prompt


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
