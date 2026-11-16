# AGENTS.md — PaperAgent Shared Rules

This repository implements **PaperAgent**, a runtime-independent personal research intelligence platform.
All coding and analysis agents (`agy`, `claude`, `codex`) share these rules.

## 1. Architecture & Independence Rules
- **Runtime Independence**: Never couple core business logic to a specific CLI (`agy`, `claude`, `codex`). All LLM calls must go through `src/paper_agent/agents/base.py` (`RuntimeAdapter`).
- **Deterministic PDF Coordinates**: Never ask an LLM to guess PDF coordinates. LLMs select verbatim text quotes + `block_id` from `segments.json`; Python (`pymupdf`) resolves exact bounding boxes/quads and embeds `/AP` appearance streams via `annot.update()`.
- **Main Orchestrator Readiness**:
  - Keep core pipelines callable as pure CLI commands (`paper-agent ... --json`) and Python services, separate from any long-running daemon (Telegram poller / cron).
  - Use SQLite WAL mode (`PRAGMA journal_mode=WAL; busy_timeout=5000`).
  - Enforce global concurrency limits (`max_concurrent_runs`) when spawning subagents.
  - Prefix all skill names with `paper-` to prevent namespace collisions.

## 2. Paper Analysis & Terminology Rules (Korean / English)
- **Do NOT force-translate technical terms**: Keep model names, architectures, mechanisms, tensor names, and standard benchmarks in English (e.g., `Self-Attention`, `Key-Value (KV) Cache`, `In-Context Learning (ICL)`, `Ablation Study`, `LoRA`, `RAG`).
- **Academic Korean Style**: Write explanations and critical analysis in clear, natural Korean academic prose, paired with exact English technical terms.
- **Grounded Evidence**: Every quantitative claim, experimental metric, and highlight quote must reference page numbers and original paper text. Never fabricate numbers or citations.
- **Equations**: Render mathematical formulas in valid LaTeX (`$...$` or `$$...$$`).

## 3. Code Quality
- Keep modules small, typed (`pydantic` / type hints), and testable.
- Cache intermediate subagent outputs locally under `data/work/{paper_id}/` so failed stages can be retried individually without re-running completed stages.
