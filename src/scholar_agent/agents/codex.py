"""Codex CLI RuntimeAdapter with read-only sandbox, stdin prompt & clean message output."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

from scholar_agent.agents.base import (
    AgentResponse,
    RuntimeAdapter,
    isolated_stage_dir,
)


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
        with isolated_stage_dir(images) as (stage_dir, staged_images):
            out_msg_path = stage_dir / "last_message.txt"
            cmd: list[str] = [
                "codex",
                "exec",
                "--sandbox",
                "read-only",
                "--skip-git-repo-check",
                "-o",
                str(out_msg_path),
            ]
            if model:
                cmd.extend(["--model", model])
            if schema_path is not None and schema_path.exists():
                staged_schema = stage_dir / schema_path.name
                staged_schema.write_text(schema_path.read_text(encoding="utf-8"), encoding="utf-8")
                cmd.extend(["--output-schema", str(staged_schema)])
            for img in staged_images:
                cmd.extend(["--image", str(img)])
            # Read prompt from stdin (`-`) to avoid CLI argv length limits
            cmd.append("-")

            proc = subprocess.run(
                cmd,
                input=prompt,
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
                    f"codex failed (code={proc.returncode}): {proc.stderr.strip() or proc.stdout.strip()}"
                )

            raw = (
                out_msg_path.read_text(encoding="utf-8").strip()
                if out_msg_path.exists()
                else proc.stdout.strip()
            )
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
