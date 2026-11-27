"""Configuration loader for ScholarAgent."""

from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml
from pydantic import BaseModel, Field


def get_project_root() -> Path:
    return Path(__file__).resolve().parents[2]


class RoleConfig(BaseModel):
    primary: str = "antigravity"
    fallback: str | None = "claude"
    model: str = ""
    fallback_model: str = ""
    effort: str = "medium"


class AgentsDefaults(BaseModel):
    primary_runtime: str = "antigravity"
    fallback_runtime: str = "claude"
    timeout_seconds: int = 360


class UserProfileConfig(BaseModel):
    research_interests: list[str] = Field(
        default_factory=lambda: [
            "LLM Efficiency, Long-Context & KV Cache Compression",
            "External Knowledge Injection, RAG & Agent Memory Systems",
            "Multimodal Reasoning & Research Automation Agents",
        ]
    )
    preferred_language: str = "ko"


class AgentsConfig(BaseModel):
    defaults: AgentsDefaults = Field(default_factory=AgentsDefaults)
    user_profile: UserProfileConfig = Field(default_factory=UserProfileConfig)
    roles: dict[str, RoleConfig] = Field(default_factory=dict)

    def for_role(self, role: str) -> RoleConfig:
        if role in self.roles:
            return self.roles[role]
        return RoleConfig(
            primary=self.defaults.primary_runtime,
            fallback=self.defaults.fallback_runtime,
        )


def load_agents_config(path: Path | None = None) -> AgentsConfig:
    cfg_path = path or (get_project_root() / "config" / "agents.yaml")
    if not cfg_path.exists():
        return AgentsConfig()
    data: dict[str, Any] = yaml.safe_load(cfg_path.read_text(encoding="utf-8")) or {}
    return AgentsConfig.model_validate(data)
