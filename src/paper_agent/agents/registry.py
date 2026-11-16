"""Runtime adapter registry and fallback execution."""

from __future__ import annotations

from pathlib import Path

from paper_agent.agents.antigravity import AntigravityAdapter
from paper_agent.agents.base import AgentResponse, RuntimeAdapter
from paper_agent.agents.claude import ClaudeAdapter
from paper_agent.agents.codex import CodexAdapter
from paper_agent.config import AgentsConfig

ADAPTERS: dict[str, type[RuntimeAdapter]] = {
    "antigravity": AntigravityAdapter,
    "claude": ClaudeAdapter,
    "codex": CodexAdapter,
}


def get_adapter(name: str) -> RuntimeAdapter:
    cls = ADAPTERS.get(name)
    if cls is None:
        raise ValueError(f"Unknown agent runtime: {name}")
    return cls()


def run_role(
    config: AgentsConfig,
    role: str,
    prompt: str,
    *,
    work_dir: Path,
    schema_path: Path | None = None,
    images: list[Path] | None = None,
) -> AgentResponse:
    role_cfg = config.for_role(role)
    primary = get_adapter(role_cfg.primary)
    try:
        return primary.run(
            prompt,
            work_dir=work_dir,
            schema_path=schema_path,
            images=images,
            model=role_cfg.model or None,
            effort=role_cfg.effort or None,
            timeout=config.defaults.timeout_seconds,
        )
    except Exception as primary_err:
        if not role_cfg.fallback or role_cfg.fallback == role_cfg.primary:
            raise
        fallback = get_adapter(role_cfg.fallback)
        try:
            return fallback.run(
                prompt,
                work_dir=work_dir,
                schema_path=schema_path,
                images=images,
                model=None,
                effort=role_cfg.effort or None,
                timeout=config.defaults.timeout_seconds,
            )
        except Exception as fb_err:
            raise RuntimeError(
                f"Both primary ({role_cfg.primary}) and fallback ({role_cfg.fallback}) failed: "
                f"{primary_err} | {fb_err}"
            ) from fb_err
