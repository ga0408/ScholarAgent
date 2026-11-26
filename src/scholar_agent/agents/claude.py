"""Claude Code CLI RuntimeAdapter with isolated stage directory & UTF-8 stdin support."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path
from typing import Any

from scholar_agent.agents.base import (
    AgentResponse,
    RuntimeAdapter,
    isolated_stage_dir,
    prepare_cli_prompt_delivery,
)


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
        with isolated_stage_dir(images) as (stage_dir, staged_images):
            full_prompt = prompt
            if staged_images:
                image_list = "\n".join(f"- {p}" for p in staged_images)
                full_prompt = (
                    f"{prompt}\n\n"
                    f"Attached page images (inspect these files directly):\n{image_list}"
                )

            argv_prompt, stdin_payload = prepare_cli_prompt_delivery(full_prompt, stage_dir)

            cmd: list[str] = [
                "claude",
                "-p",
                argv_prompt,
                "--output-format",
                "json",
                "--dangerously-skip-permissions",
                "--add-dir",
                str(stage_dir),
            ]
            if schema_path is not None and schema_path.exists():
                cmd.extend(["--json-schema", schema_path.read_text(encoding="utf-8")])
            if model:
                cmd.extend(["--model", model])
            if effort:
                cmd.extend(["--effort", effort])

            proc = subprocess.run(
                cmd,
                input=stdin_payload,
                cwd=str(stage_dir),
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
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
