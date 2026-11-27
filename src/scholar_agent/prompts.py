"""Prompt template loader for ScholarAgent (eliminates hardcoded prompts in Python modules)."""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path


def _project_root() -> Path:
    return Path(__file__).resolve().parents[2]

TERMINOLOGY_RULE = (
    "CRITICAL TERMINOLOGY & EVIDENCE RULES:\n"
    "1. Do NOT force-translate technical terms: keep model names, architectures, mechanisms, "
    "tensor/loss names, and benchmarks in English (e.g., Self-Attention, Key-Value (KV) Cache, "
    "In-Context Learning (ICL), Ablation Study, LoRA, RAG).\n"
    "2. Write explanations, synthesis, and critical analysis in clear, natural Korean academic prose.\n"
    "3. Ground every quantitative claim, metric, and architectural detail with exact page numbers "
    "(e.g., p.4, Eq. (6), Table 2, Fig. 5). Prefix visually estimated plot readings with `~`.\n"
    "4. Render all mathematical expressions in valid LaTeX (`$...$` inline, `$$...$$` display).\n"
    "5. Do NOT write or modify files on disk; return your output directly in the response."
)


@lru_cache(maxsize=32)
def load_prompt_template(name: str) -> str:
    template_path: Path = _project_root() / "prompts" / "templates" / f"{name}.md"
    if not template_path.exists():
        raise FileNotFoundError(f"Prompt template not found: {template_path}")
    return template_path.read_text(encoding="utf-8")


def render_prompt(name: str, **variables: object) -> str:
    """Render `prompts/templates/{name}.md` substituting `{{var}}` placeholders safely.

    Uses double-brace `{{key}}` tokens so LaTeX braces (`\\frac{a}{b}`) and JSON examples
    inside prompt templates never collide with Python `.format()`.
    """
    raw = load_prompt_template(name)
    merged = {"terminology_rule": TERMINOLOGY_RULE, **variables}
    rendered = raw
    for key, val in merged.items():
        rendered = rendered.replace(f"{{{{{key}}}}}", str(val))
    return rendered
