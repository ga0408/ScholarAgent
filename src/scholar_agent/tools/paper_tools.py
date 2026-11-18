"""Core tool implementations shared by pipeline escalation and the MCP server."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from scholar_agent.agents.registry import run_role
from scholar_agent.collector.arxiv import (
    ARXIV_ID_RE,
    fetch_arxiv_metadata,
    normalize_arxiv_id,
    search_arxiv_by_title,
)
from scholar_agent.config import AgentsConfig, get_project_root, load_agents_config
from scholar_agent.database.sqlite_store import SQLiteStore

_TRIAGE_OUTPUT_SCHEMA = {
    "type": "object",
    "required": ["tldr", "core_problem", "core_method", "key_difference"],
    "properties": {
        "tldr": {"type": "string"},
        "core_problem": {"type": "string"},
        "core_method": {"type": "string"},
        "key_difference": {"type": "string"},
    },
}

_BENCHMARK_OUTPUT_SCHEMA = {
    "type": "object",
    "required": ["name", "task_type", "description", "metrics"],
    "properties": {
        "name": {"type": "string"},
        "task_type": {"type": "string"},
        "description": {"type": "string"},
        "metrics": {"type": "string"},
    },
}


def _normalize_key(text: str) -> str:
    return re.sub(r"\s+", " ", text.strip().lower())


def _default_store() -> SQLiteStore:
    return SQLiteStore(get_project_root() / "data" / "scholar_agent.db")


def _extract_json_obj(raw_text: str) -> dict[str, Any]:
    text = raw_text.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text, flags=re.DOTALL).strip()
    return json.loads(text)


def triage_paper(
    query_or_arxiv_id: str,
    *,
    work_dir: Path | None = None,
    config: AgentsConfig | None = None,
    store: SQLiteStore | None = None,
) -> dict[str, Any]:
    """Fast abstract-only triage of a cited/related paper, backed by SQLite triage_cache."""
    store = store or _default_store()
    config = config or load_agents_config()
    key = _normalize_key(query_or_arxiv_id)

    cached = store.get_triage_cache(key)
    if cached is not None:
        return cached

    meta = None
    if ARXIV_ID_RE.search(query_or_arxiv_id):
        try:
            meta = fetch_arxiv_metadata(query_or_arxiv_id)
        except Exception:
            meta = None
    if meta is None:
        meta = search_arxiv_by_title(query_or_arxiv_id)

    if meta is None:
        return {
            "arxiv_id": "",
            "title": query_or_arxiv_id,
            "abstract": "",
            "triage": {
                "tldr": f"arXiv에서 '{query_or_arxiv_id}' 초록을 찾지 못했습니다.",
                "core_problem": "",
                "core_method": "",
                "key_difference": "",
            },
            "cached": False,
        }

    arxiv_key = _normalize_key(meta.canonical_id)
    cached_by_id = store.get_triage_cache(arxiv_key)
    if cached_by_id is not None:
        store.upsert_triage_cache(
            query_key=key,
            arxiv_id=cached_by_id["arxiv_id"],
            title=cached_by_id["title"],
            abstract=cached_by_id["abstract"],
            triage=cached_by_id["triage"],
        )
        return cached_by_id

    prompt = (
        "You are a fast Abstract-only Paper Triage Agent.\n"
        "Keep technical terms in English and write concise explanations in Korean.\n"
        "Return ONLY valid JSON matching:\n"
        f"{json.dumps(_TRIAGE_OUTPUT_SCHEMA, ensure_ascii=False)}\n\n"
        f"Title: {meta.title}\n"
        f"Abstract: {meta.abstract}\n"
    )
    run_dir = work_dir or get_project_root()
    resp = run_role(config, "daily_triage", prompt, work_dir=run_dir)
    triage_data = resp.structured_output or _extract_json_obj(resp.raw_text)

    for k in (key, arxiv_key):
        store.upsert_triage_cache(
            query_key=k,
            arxiv_id=meta.canonical_id,
            title=meta.title,
            abstract=meta.abstract,
            triage=triage_data,
        )

    return {
        "arxiv_id": meta.canonical_id,
        "title": meta.title,
        "abstract": meta.abstract,
        "triage": triage_data,
        "cached": False,
    }


def lookup_benchmark(
    name: str,
    *,
    work_dir: Path | None = None,
    config: AgentsConfig | None = None,
    store: SQLiteStore | None = None,
) -> dict[str, Any]:
    """Look up a benchmark's task type, description, and evaluation metrics with SQLite caching."""
    store = store or _default_store()
    config = config or load_agents_config()
    key = _normalize_key(name)

    cached = store.get_benchmark_cache(key)
    if cached is not None:
        return cached

    prompt = (
        "You are a Research Benchmark Reference Assistant.\n"
        "Keep technical terms in English and write concise explanations in Korean.\n"
        "Return ONLY valid JSON matching:\n"
        f"{json.dumps(_BENCHMARK_OUTPUT_SCHEMA, ensure_ascii=False)}\n\n"
        f"Benchmark name: {name}\n"
        "Explain what this benchmark evaluates, its task_type, and primary evaluation metrics."
    )
    run_dir = work_dir or get_project_root()
    resp = run_role(config, "daily_triage", prompt, work_dir=run_dir)
    info = resp.structured_output or _extract_json_obj(resp.raw_text)

    store.upsert_benchmark_cache(name_key=key, name=name, info=info)
    return {
        "name": name,
        "info": info,
        "cached": False,
    }


def read_paper_section(paper_id: str, section_or_pages: str) -> dict[str, Any]:
    """Read a canonical section name or page range (e.g. '4-6') from a processed paper."""
    work_dir = get_project_root() / "data" / "papers" / paper_id
    if not work_dir.exists():
        raise FileNotFoundError(f"Paper workspace not found: {work_dir}")

    sections_path = work_dir / "sections.json"
    query = section_or_pages.strip().lower()

    if sections_path.exists():
        sections_data = json.loads(sections_path.read_text(encoding="utf-8"))
        sec = sections_data.get("sections", {}).get(query)
        if sec is not None:
            pages = list(range(sec["start_page"], sec["end_page"] + 1))
            images = [
                str(work_dir / "pages" / f"page_{p:03d}.png")
                for p in pages
                if (work_dir / "pages" / f"page_{p:03d}.png").exists()
            ]
            return {
                "paper_id": paper_id,
                "section": query,
                "pages": pages,
                "page_images": images,
                "text": sec["text"],
            }

    # Parse page or page range like "4" or "4-6"
    m = re.match(r"^(\d+)(?:\s*-\s*(\d+))?$", query)
    if not m:
        raise ValueError(f"Unknown section or invalid page range: {section_or_pages}")
    start_p = int(m.group(1))
    end_p = int(m.group(2) or start_p)
    segments = json.loads((work_dir / "segments.json").read_text(encoding="utf-8"))
    matched = [s for s in segments if start_p <= int(s["page"]) <= end_p]
    pages = list(range(start_p, end_p + 1))
    images = [
        str(work_dir / "pages" / f"page_{p:03d}.png")
        for p in pages
        if (work_dir / "pages" / f"page_{p:03d}.png").exists()
    ]
    return {
        "paper_id": paper_id,
        "pages": pages,
        "page_images": images,
        "text": "\n".join(f"[{s['block_id']}] {s['text']}" for s in matched),
    }


def deep_dive_paper(arxiv_input: str, *, force: bool = False) -> dict[str, Any]:
    """Return cached full pipeline result or run run_paper_pipeline for an arXiv paper."""
    arxiv_id = normalize_arxiv_id(arxiv_input)
    work_dir = get_project_root() / "data" / "papers" / f"arxiv_{arxiv_id}"
    result_path = work_dir / "result.json"
    if result_path.exists() and not force:
        return json.loads(result_path.read_text(encoding="utf-8"))

    from scholar_agent.orchestrator.pipeline import run_paper_pipeline

    return run_paper_pipeline(arxiv_input, force=force)
