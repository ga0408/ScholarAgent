"""Claude Code CLI RuntimeAdapter (for Opus/Sonnet fallback & experiments)."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path
from typing import Any

from scholar_agent.agents.base import AgentResponse, RuntimeAdapter


class ClaudeAdapter(RuntimeAdapter):
    name = "claude"

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
        full_prompt = prompt
        if images:
            image_list = "\n".join(f"- {p}" for p in images)
            full_prompt = f"{prompt}\n\nAttached page images:\n{image_list}"

        cmd: list[str] = [
            "claude",
            "-p",
            full_prompt,
            "--output-format",
            "json",
            "--dangerously-skip-permissions",
        ]
        if schema_path is not None:
            cmd.extend(["--json-schema", schema_path.read_text(encoding="utf-8")])
        if model:
            cmd.extend(["--model", model])

        proc = subprocess.run(
            cmd,
            cwd=str(work_dir),
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
        if proc.returncode != 0:
            raise RuntimeError(
                f"claude failed (code={proc.returncode}): {proc.stderr.strip() or proc.stdout.strip()}"
            )

        payload: dict[str, Any] = json.loads(proc.stdout)
        structured = payload.get("structured_output")
        result_text = payload.get("result", "")
        if structured is None and isinstance(result_text, str):
            try:
                structured = json.loads(result_text)
            except json.JSONDecodeError:
                structured = None

        return AgentResponse(
            runtime=self.name,
            model=model or "claude-default",
            raw_text=result_text if isinstance(result_text, str) else json.dumps(result_text),
            structured_output=structured,
            usage=payload.get("usage", {}) or {},
        )
