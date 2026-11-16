"""Antigravity CLI (agy) RuntimeAdapter."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path
from typing import Any

from paper_agent.agents.base import AgentResponse, RuntimeAdapter


class AntigravityAdapter(RuntimeAdapter):
    name = "antigravity"

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
            full_prompt = (
                f"{prompt}\n\n"
                f"Attached page images (inspect these files directly if needed):\n{image_list}"
            )

        cmd: list[str] = [
            "agy",
            "-p",
            full_prompt,
            "--output-format",
            "json",
            "--disable-slash-commands",
            "--dangerously-skip-permissions",
            "--add-dir",
            str(work_dir),
        ]
        if schema_path is not None:
            cmd.extend(["--json-schema", str(schema_path)])
        if model:
            cmd.extend(["--model", model])
        if effort:
            cmd.extend(["--effort", effort])

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
                f"agy failed (code={proc.returncode}): {proc.stderr.strip() or proc.stdout.strip()}"
            )

        payload: dict[str, Any] = json.loads(proc.stdout)
        if payload.get("status") != "SUCCESS":
            raise RuntimeError(f"agy non-success status: {payload}")

        return AgentResponse(
            runtime=self.name,
            model=model or "agy-default",
            raw_text=payload.get("response", ""),
            structured_output=payload.get("structured_output"),
            duration_seconds=float(payload.get("duration_seconds", 0.0)),
            usage=payload.get("usage", {}) or {},
        )
