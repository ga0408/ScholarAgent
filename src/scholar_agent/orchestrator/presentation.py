"""Presentation deck + spoken script planner and video orchestrator."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from scholar_agent.agents.registry import run_role
from scholar_agent.collector.arxiv import normalize_arxiv_id
from scholar_agent.config import get_project_root, load_agents_config
from scholar_agent.orchestrator.pipeline import TERMINOLOGY_RULE, run_paper_pipeline
from scholar_agent.processors.video_maker import build_presentation_video


def generate_presentation_video(
    arxiv_input: str,
    *,
    out_base_dir: Path | None = None,
    voice: str = "ko-KR-InJoonNeural",
    style: str = "학술 세미나 발표 (약 3~5분, 명확하고 논리적인 톤)",
    force: bool = False,
) -> dict[str, Any]:
    """Plan slides & Korean spoken script from summarized paper artifacts, then build MP4 + SRT."""
    root = get_project_root()
    config = load_agents_config()
    arxiv_id = normalize_arxiv_id(arxiv_input)
    work_dir = (out_base_dir or (root / "data" / "papers")) / f"arxiv_{arxiv_id}"

    core_path = work_dir / "core_summary.json"
    summary_path = work_dir / "summary" / "summary.ko.md"
    if not core_path.exists() or not summary_path.exists():
        run_paper_pipeline(arxiv_input, out_base_dir=out_base_dir, force=False)

    meta = json.loads((work_dir / "metadata.json").read_text(encoding="utf-8"))
    triage = json.loads((work_dir / "triage.json").read_text(encoding="utf-8"))
    core_summary = json.loads(core_path.read_text(encoding="utf-8"))
    sections = (
        json.loads((work_dir / "sections.json").read_text(encoding="utf-8"))
        if (work_dir / "sections.json").exists()
        else {}
    )
    summary_ko = summary_path.read_text(encoding="utf-8")

    pres_dir = work_dir / "presentation"
    pres_dir.mkdir(parents=True, exist_ok=True)
    plan_path = pres_dir / "presentation.json"

    if plan_path.exists() and not force:
        presentation = json.loads(plan_path.read_text(encoding="utf-8"))
    else:
        schema_path = root / "prompts" / "schemas" / "presentation.schema.json"
        toc_info = json.dumps(sections.get("toc", []), ensure_ascii=False)
        prompt = (
            f"You are the Academic Presentation Planner Agent.\n{TERMINOLOGY_RULE}\n\n"
            f"Design a clear, engaging 7-to-9 slide presentation deck in Korean for '{meta.get('title')}' "
            f"following the paper's natural flow: "
            f"title/TL;DR -> overview -> introduction (problem & contributions) -> "
            f"related_work -> method (with key equation_text & visual_page) -> "
            f"experiment (with benchmark results & visual_page) -> conclusion.\n\n"
            f"Target style: {style}\n\n"
            f"RULES FOR SLIDES & TTS SCRIPTS:\n"
            f"1. Keep `bullets` concise (3-4 short bullets per slide, keeping technical terms in English).\n"
            f"2. Set `visual_page` (1-indexed integer) to the best matching paper page from TOC/core_summary "
            f"(e.g. architecture diagram page for `method`, results table/chart page for `experiment`), or null.\n"
            f"3. Write `speaker_script` in natural spoken Korean (~2-4 sentences per slide) suitable for TTS: "
            f"spell out symbols/ratios naturally instead of raw LaTeX backslashes.\n\n"
            f"Section TOC:\n{toc_info}\n\n"
            f"Triage TL;DR:\n{triage.get('tldr', '')}\n\n"
            f"Core Summary JSON:\n{json.dumps(core_summary, ensure_ascii=False)[:18_000]}\n\n"
            f"Korean Summary Markdown:\n{summary_ko[:12_000]}"
        )
        resp = run_role(
            config,
            "presentation_planner",
            prompt,
            work_dir=work_dir,
            schema_path=schema_path,
        )
        presentation = resp.structured_output or json.loads(resp.raw_text)
        plan_path.write_text(
            json.dumps(presentation, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    video_res = build_presentation_video(
        presentation,
        work_dir,
        voice=voice,
        force=force,
    )
    return {
        "status": "completed",
        "paper_id": f"arxiv_{arxiv_id}",
        "title": meta.get("title", ""),
        "presentation_json": str(plan_path),
        **video_res,
    }
