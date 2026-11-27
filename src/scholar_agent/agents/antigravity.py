"""Antigravity CLI (agy) RuntimeAdapter with isolated stage directory & UTF-8 stdin support."""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

from scholar_agent.agents.base import (
    AgentResponse,
    RuntimeAdapter,
    isolated_stage_dir,
    prepare_cli_prompt_delivery,
)


def _resolve_agy_bin() -> str:
    found = shutil.which("agy")
    if found:
        return found
    win_default = Path.home() / "AppData" / "Local" / "agy" / "bin" / "agy.exe"
    if win_default.exists():
        return str(win_default)
    return "agy"


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
        with isolated_stage_dir(images) as (stage_dir, staged_images):
            full_prompt = prompt
            if staged_images:
                image_list = "\n".join(f"- {p}" for p in staged_images)
                full_prompt = (
                    f"{prompt}\n\n"
                    f"Attached page images (inspect these files directly to verify equations, figures, and tables):\n"
                    f"{image_list}"
                )

            staged_schema: Path | None = None
            if schema_path is not None and schema_path.exists():
                staged_schema = stage_dir / schema_path.name
                staged_schema.write_text(schema_path.read_text(encoding="utf-8"), encoding="utf-8")

            argv_prompt, stdin_payload = prepare_cli_prompt_delivery(full_prompt, stage_dir)

            cmd: list[str] = [
                _resolve_agy_bin(),
                "-p",
                argv_prompt,
                "--output-format",
                "json",
                "--disable-slash-commands",
                "--dangerously-skip-permissions",
                "--add-dir",
                str(stage_dir),
            ]
            if staged_schema is not None:
                cmd.extend(["--json-schema", str(staged_schema)])
            if model:
                cmd.extend(["--model", model])
            has_effort_suffix = bool(
                model and model.endswith(("-low", "-medium", "-high", "-xhigh", "-max"))
            )
            if effort and not has_effort_suffix:
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
