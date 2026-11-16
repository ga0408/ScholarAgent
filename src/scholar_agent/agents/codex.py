"""Codex CLI RuntimeAdapter."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

from scholar_agent.agents.base import AgentResponse, RuntimeAdapter


class CodexAdapter(RuntimeAdapter):
    name = "codex"

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
        cmd: list[str] = ["codex", "exec", "--full-auto"]
        if model:
            cmd.extend(["--model", model])
        if schema_path is not None:
            cmd.extend(["--output-schema", str(schema_path)])
        if images:
            for img in images:
                cmd.extend(["--image", str(img)])
        cmd.append(prompt)

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
                f"codex failed (code={proc.returncode}): {proc.stderr.strip() or proc.stdout.strip()}"
            )

        raw = proc.stdout.strip()
        structured = None
        try:
            structured = json.loads(raw)
        except json.JSONDecodeError:
            pass

        return AgentResponse(
            runtime=self.name,
            model=model or "codex-default",
            raw_text=raw,
            structured_output=structured,
        )
