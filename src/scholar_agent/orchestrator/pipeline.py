"""Single-paper end-to-end subagent pipeline with stage caching & partial retry."""

from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any

from scholar_agent.agents.registry import run_role
from scholar_agent.collector.arxiv import PaperMetadata, download_pdf, fetch_arxiv_metadata
from scholar_agent.config import AgentsConfig, get_project_root, load_agents_config
from scholar_agent.database.sqlite_store import SQLiteStore
from scholar_agent.processors.highlighter import apply_highlights
from scholar_agent.processors.pdf_parser import preprocess_pdf
from scholar_agent.tools.paper_tools import lookup_benchmark, triage_paper

TERMINOLOGY_RULE = (
    "CRITICAL TERMINOLOGY RULE: Keep technical terms, model names, mechanisms, "
    "and standard benchmarks in English (e.g., Self-Attention, KV Cache, LoRA, ICL, "
    "Ablation Study). Write explanations in clear academic Korean. "
    "Ground all claims with page numbers and exact numbers from the paper."
)


def _run_cached_json_stage(
    *,
    stage: str,
    role: str,
    prompt: str,
    work_dir: Path,
    schema_name: str,
    config: AgentsConfig,
    store: SQLiteStore,
    paper_id: str,
    images: list[Path] | None = None,
    force: bool = False,
) -> dict[str, Any]:
    out_path = work_dir / f"{stage}.json"
    if out_path.exists() and not force:
        return json.loads(out_path.read_text(encoding="utf-8"))

    schema_path = get_project_root() / "prompts" / "schemas" / schema_name
    role_cfg = config.for_role(role)
    store.upsert_job(
        paper_id=paper_id,
        stage=stage,
        runtime=role_cfg.primary,
        model=role_cfg.model,
        status="running",
    )
    try:
        resp = run_role(
            config,
            role,
            prompt,
            work_dir=work_dir,
            schema_path=schema_path,
            images=images,
        )
        data = resp.structured_output
        if data is None:
            data = json.loads(resp.raw_text)
        out_path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        store.upsert_job(
            paper_id=paper_id,
            stage=stage,
            runtime=resp.runtime,
            model=resp.model,
            status="completed",
            artifact_path=str(out_path),
        )
        return data
    except Exception as exc:
        store.upsert_job(
            paper_id=paper_id,
            stage=stage,
            runtime=role_cfg.primary,
            model=role_cfg.model,
            status="failed",
            error_message=str(exc),
        )
        raise


def _build_sectioned_context(
    sections_data: dict[str, Any],
    fallback_text: str,
) -> str:
    """Assemble a balanced, section-labeled paper context for core_summary."""
    sections = sections_data.get("sections", {})
    if not sections:
        return fallback_text[:80_000]

    budgets = [
        ("abstract", "Abstract", 4_000),
        ("introduction", "Introduction", 14_000),
        ("related_work", "Background & Related Works", 10_000),
        ("method", "Main Method", 22_000),
        ("experiment", "Experiments", 20_000),
        ("conclusion", "Conclusion & Limitations", 8_000),
    ]
    parts: list[str] = []
    for key, label, limit in budgets:
        sec = sections.get(key)
        if sec and sec.get("text"):
            parts.append(
                f"=== {label} (pages {sec['start_page']}-{sec['end_page']}) ===\n"
                f"{sec['text'][:limit]}"
            )
    return "\n\n".join(parts) if parts else fallback_text[:80_000]


def _select_page_images(work_dir: Path, page_nums: list[int], max_images: int = 5) -> list[Path]:
    pages_dir = work_dir / "pages"
    selected: list[Path] = []
    for p in page_nums[:max_images]:
        candidate = pages_dir / f"page_{int(p):03d}.png"
        if candidate.exists():
            selected.append(candidate)
    return selected


def run_paper_pipeline(
    arxiv_input: str,
    *,
    out_base_dir: Path | None = None,
    force: bool = False,
    include_en: bool = False,
) -> dict[str, Any]:
    root = get_project_root()
    config = load_agents_config()
    store = SQLiteStore(root / "data" / "scholar_agent.db")

    meta: PaperMetadata = fetch_arxiv_metadata(arxiv_input)
    work_dir = (out_base_dir or (root / "data" / "papers")) / meta.paper_id
    work_dir.mkdir(parents=True, exist_ok=True)
    (work_dir / "summary").mkdir(exist_ok=True)
    (work_dir / "highlights").mkdir(exist_ok=True)

    pdf_path = work_dir / "original.pdf"
    sha256 = download_pdf(meta.pdf_url, pdf_path)

    meta_path = work_dir / "metadata.json"
    meta_path.write_text(meta.model_dump_json(indent=2), encoding="utf-8")

    store.upsert_paper(
        paper_id=meta.paper_id,
        canonical_id=meta.canonical_id,
        source=meta.source,
        title=meta.title,
        authors=meta.authors,
        abstract=meta.abstract,
        published_at=meta.published_at,
        pdf_url=meta.pdf_url,
        content_sha256=sha256,
        status="processing",
        work_dir=str(work_dir),
    )

    prep = preprocess_pdf(pdf_path, work_dir)
    extracted_text = Path(prep["extracted_md"]).read_text(encoding="utf-8")
    sections_data = json.loads(Path(prep["sections_json"]).read_text(encoding="utf-8"))
    sections_map = sections_data.get("sections", {})
    toc_summary = json.dumps(sections_data.get("toc", []), ensure_ascii=False)
    paper_text_excerpt = extracted_text[:80_000]
    sectioned_context = _build_sectioned_context(sections_data, extracted_text)

    # 1. Abstract-first Triage (with optional Intro / Method peek only when needed)
    abstract_text = sections_map.get("abstract", {}).get("text") or meta.abstract
    triage_prompt = (
        f"You are the Paper Triage Agent.\n{TERMINOLOGY_RULE}\n\n"
        f"Read ONLY the Title, Abstract, and Section TOC below.\n"
        f"1) Write a crisp 1-3 sentence Korean `tldr` (problem + proposed method + key result).\n"
        f"2) Write `overview` and `domain_tags`.\n"
        f"3) Set `needs_intro_peek=true` or `needs_method_peek=true` ONLY if the Abstract is "
        f"too vague to understand the core problem or proposed mechanism.\n"
        f"4) Fill `key_pages` using the TOC below.\n\n"
        f"Title: {meta.title}\nAbstract:\n{abstract_text}\n\nSection TOC:\n{toc_summary}"
    )
    triage = _run_cached_json_stage(
        stage="triage",
        role="triage",
        prompt=triage_prompt,
        work_dir=work_dir,
        schema_name="triage.schema.json",
        config=config,
        store=store,
        paper_id=meta.paper_id,
        force=force,
    )

    if triage.get("needs_intro_peek") or triage.get("needs_method_peek"):
        peek_parts: list[str] = []
        if triage.get("needs_intro_peek") and sections_map.get("introduction"):
            peek_parts.append(f"Introduction (excerpt):\n{sections_map['introduction']['text'][:6_000]}")
        if triage.get("needs_method_peek") and sections_map.get("method"):
            peek_parts.append(f"Main Method (excerpt):\n{sections_map['method']['text'][:6_000]}")
        if peek_parts:
            refined_prompt = (
                f"You are the Paper Triage Agent refining an Abstract-only triage.\n{TERMINOLOGY_RULE}\n\n"
                f"Title: {meta.title}\nAbstract:\n{abstract_text}\n\n"
                + "\n\n".join(peek_parts)
                + "\n\nRefine `tldr` and `overview`, and set `needs_intro_peek=false`, `needs_method_peek=false`."
            )
            triage = _run_cached_json_stage(
                stage="triage_refined",
                role="triage",
                prompt=refined_prompt,
                work_dir=work_dir,
                schema_name="triage.schema.json",
                config=config,
                store=store,
                paper_id=meta.paper_id,
                force=force,
            )

    # 2. Core Structured Summary Skeleton (Introduction -> Related Works -> Method -> Experiments -> Conclusion)
    core_prompt = (
        f"You are the Core Paper Summarizer.\n{TERMINOLOGY_RULE}\n\n"
        f"Analyze '{meta.title}' following the natural paper flow:\n"
        f"- introduction: problem solved, prior_limitations, key_differences, contributions.\n"
        f"- related_works: itemize prior studies by category with representative methods; set `needs_triage=true` "
        f"ONLY if the paper's explanation of a cited method is too brief to understand.\n"
        f"- method: proposed method, differences_from_prior, key equations (`latex` + `meaning`); "
        f"set `needs_deep_dive=true` ONLY if architecture/equations are complex enough to inspect page images.\n"
        f"- experiments: benchmarks used (set `needs_lookup=true` if unfamiliar/unexplained), setup, "
        f"results_comparison; set `needs_figure_table_check=true` ONLY if tables/charts need visual verification.\n"
        f"- conclusion: closing_summary and author/paper limitations.\n"
        f"- key_points: 12-16 structural anchor points across background/contribution/method/experiment/limitation "
        f"following academic discourse structure (Swales' CARS & rhetorical signposts):\n"
        f"  (a) Gap/Contrast Pivot ('However, ...', 'Unlike prior work...');\n"
        f"  (b) Thesis/Proposal Anchor ('In this paper, we propose...', 'To address this...');\n"
        f"  (c) COMPLETE Enumeration Chains ('First, ...', 'Then/Second, ...', 'Lastly/Finally, ...' or '1. ... 2. ... 3. ...' "
        f"— NEVER omit a limb of an enumerated sequence!);\n"
        f"  (d) Key experimental takeaway & author-stated limitation.\n\n"
        f"Triage TL;DR: {triage.get('tldr', '')}\n\n"
        f"Sectioned Paper Text:\n{sectioned_context}"
    )
    core_summary = _run_cached_json_stage(
        stage="core_summary",
        role="core_summary",
        prompt=core_prompt,
        work_dir=work_dir,
        schema_name="core_summary.schema.json",
        config=config,
        store=store,
        paper_id=meta.paper_id,
        force=force,
    )

    # 3. On-Demand Subagents (run ONLY when core_summary flags complexity/gaps)
    key_pages = triage.get("key_pages", {})
    method_pages = (
        core_summary.get("method", {}).get("target_pages")
        or key_pages.get("method_pages")
        or (
            list(range(sections_map["method"]["start_page"], sections_map["method"]["end_page"] + 1))
            if "method" in sections_map
            else [2, 3, 4]
        )
    )
    exp_pages = (
        core_summary.get("experiments", {}).get("target_pages")
        or key_pages.get("experiment_pages")
        or (
            list(range(sections_map["experiment"]["start_page"], sections_map["experiment"]["end_page"] + 1))
            if "experiment" in sections_map
            else [5, 6, 7]
        )
    )
    method_imgs = _select_page_images(work_dir, method_pages, max_images=4)
    exp_imgs = _select_page_images(work_dir, exp_pages, max_images=4)

    escalated_stages: dict[str, dict[str, Any]] = {}
    if core_summary.get("method", {}).get("needs_deep_dive"):
        method_text = sections_map.get("method", {}).get("text") or paper_text_excerpt
        escalated_stages["method_deep_dive"] = {
            "role": "method_deep_dive",
            "schema": "method_deep_dive.schema.json",
            "images": method_imgs,
            "prompt": (
                f"You are the Method Deep Dive Agent.\n{TERMINOLOGY_RULE}\n\n"
                f"Paper: {meta.title}\nCore Method Summary: {core_summary['method'].get('overview')}\n\n"
                f"Inspect the attached method page images and text to detail architecture, tensor flow, "
                f"complexity, and differences from prior art.\n\nMethod text:\n{method_text[:45_000]}"
            ),
        }
        escalated_stages["equations"] = {
            "role": "equation_explainer",
            "schema": "equations.schema.json",
            "images": method_imgs,
            "prompt": (
                f"You are the Equation Explainer Agent.\n{TERMINOLOGY_RULE}\n\n"
                f"Inspect the attached method page images and text for '{meta.title}'. "
                f"Transcribe key equations into clean LaTeX, list variables, and explain their meaning.\n\n"
                f"Method text:\n{method_text[:45_000]}"
            ),
        }

    if core_summary.get("experiments", {}).get("needs_figure_table_check"):
        exp_text = sections_map.get("experiment", {}).get("text") or paper_text_excerpt
        escalated_stages["figures_tables"] = {
            "role": "figure_table",
            "schema": "figures_tables.schema.json",
            "images": exp_imgs,
            "prompt": (
                f"You are the Figure & Table Analyzer Agent.\n{TERMINOLOGY_RULE}\n\n"
                f"Inspect the attached experiment page images and text for '{meta.title}'. "
                f"Verify exact benchmark numbers from tables/figures.\n\nExperiment text:\n{exp_text[:45_000]}"
            ),
        }

    triage_targets: list[tuple[str, str]] = []
    for cat in core_summary.get("related_works", []):
        for m in cat.get("methods", []):
            if m.get("needs_triage") and len(triage_targets) < 5:
                triage_targets.append((m["name"], m.get("arxiv_hint") or m["name"]))

    benchmark_targets: list[str] = [
        b["name"]
        for b in core_summary.get("experiments", {}).get("benchmarks", [])
        if b.get("needs_lookup")
    ][:5]

    results: dict[str, dict[str, Any]] = {}
    related_work_triages: dict[str, Any] = {}
    benchmark_lookups: dict[str, Any] = {}

    if escalated_stages or triage_targets or benchmark_targets:
        max_workers = max(1, config.defaults.max_concurrent_runs)
        with ThreadPoolExecutor(max_workers=max_workers) as pool:
            stage_futs = {
                pool.submit(
                    _run_cached_json_stage,
                    stage=name,
                    role=spec["role"],
                    prompt=spec["prompt"],
                    work_dir=work_dir,
                    schema_name=spec["schema"],
                    config=config,
                    store=store,
                    paper_id=meta.paper_id,
                    images=spec["images"],
                    force=force,
                ): name
                for name, spec in escalated_stages.items()
            }
            triage_futs = {
                pool.submit(
                    triage_paper,
                    query,
                    work_dir=work_dir,
                    config=config,
                    store=store,
                ): method_name
                for method_name, query in triage_targets
            }
            bench_futs = {
                pool.submit(
                    lookup_benchmark,
                    b_name,
                    work_dir=work_dir,
                    config=config,
                    store=store,
                ): b_name
                for b_name in benchmark_targets
            }
            for fut in as_completed(stage_futs):
                name = stage_futs[fut]
                results[name] = fut.result()
            for fut in as_completed(triage_futs):
                m_name = triage_futs[fut]
                try:
                    related_work_triages[m_name] = fut.result()
                except Exception as exc:
                    related_work_triages[m_name] = {"error": str(exc)}
            for fut in as_completed(bench_futs):
                b_name = bench_futs[fut]
                try:
                    benchmark_lookups[b_name] = fut.result()
                except Exception as exc:
                    benchmark_lookups[b_name] = {"error": str(exc)}

    # 4. Key-Point Grounded Highlight Selection & Deterministic PDF Highlighting
    key_points_json = json.dumps(core_summary.get("key_points", []), ensure_ascii=False, indent=2)
    hl_prompt = (
        f"You are the Structural Academic Highlight Selector Agent.\n"
        f"Select 12 to 18 concise, concept-focused verbatim highlight spans for '{meta.title}' grounded in the Target Key Points:\n"
        f"1. SHORT CONCEPT SPAN ONLY (NOT FULL MULTI-LINE SENTENCES):\n"
        f"   - Do NOT highlight entire 2-3 line sentences.\n"
        f"   - Extract ONLY the core concept phrase or signpost+concept clause (4 to 12 consecutive words, fitting within ~1 line) "
        f"that represents the key idea on that line (e.g., 'First, KBLAM maps each triple into a fixed-length key-value vector pair', "
        f"'time and memory complexities of in-context learning are quadratic', 'simple rectangular attention structure', "
        f"'Lastly, we show that the linear adapters can be learned').\n"
        f"2. DISCOURSE STRUCTURE & ENUMERATION COMPLETENESS:\n"
        f"   - Capture the structural flow: Thesis ('we propose Knowledge Base augmented Language Model (KBLAM)'), "
        f"Gap ('catastrophic forgetting', 'complexities of in-context learning are quadratic'), and "
        f"COMPLETE Enumeration Chains ('First, ...', 'Then/Second, ...', 'Lastly/Finally, ...' — include every sibling item).\n"
        f"3. CONCISE CONCEPT LABEL IN `reason`: Write `reason` as a brief 1-line Korean concept summary (15-35 chars, e.g. "
        f"'핵심 제안: 외부 지식을 결합하는 KBLAM', '1단계: Triple → 고정 길이 KV 벡터(Knowledge Token) 변환').\n"
        f"4. EXACT VERBATIM SUBSTRING: Each `quote` MUST be an exact character-for-character substring of its `[block_id]` line.\n\n"
        f"Target Key Points:\n{key_points_json}\n\n"
        f"Paper Blocks:\n{sectioned_context[:60_000]}"
    )
    highlights_raw = _run_cached_json_stage(
        stage="highlights_raw",
        role="highlight_selector",
        prompt=hl_prompt,
        work_dir=work_dir,
        schema_name="highlights.schema.json",
        config=config,
        store=store,
        paper_id=meta.paper_id,
        force=force,
    )

    out_pdf = work_dir / "highlights" / "highlighted.pdf"
    out_hl_json = work_dir / "highlights" / "highlights.json"
    enriched_highlights = apply_highlights(
        pdf_path,
        Path(prep["segments_json"]),
        highlights_raw.get("highlights", []),
        out_pdf,
        out_hl_json,
    )
    store.replace_highlights(meta.paper_id, enriched_highlights)

    # 5. Report Synthesis (TL;DR callout + 6-section paper-flow Markdown)
    bundle: dict[str, Any] = {
        "metadata": meta.model_dump(),
        "triage": triage,
        "core_summary": core_summary,
    }
    if results:
        bundle["escalated_deep_dives"] = results
    if related_work_triages:
        bundle["related_work_triages"] = related_work_triages
    if benchmark_lookups:
        bundle["benchmark_lookups"] = benchmark_lookups
    bundle_json = json.dumps(bundle, ensure_ascii=False, indent=2)

    ko_path = work_dir / "summary" / "summary.ko.md"
    if not ko_path.exists() or force:
        mtime_before = ko_path.stat().st_mtime if ko_path.exists() else 0.0
        ko_prompt = (
            f"You are the Report Synthesis Agent.\n{TERMINOLOGY_RULE}\n\n"
            f"IMPORTANT: Do NOT write or edit any files on disk. Return the complete Markdown report "
            f"directly in your response text.\n\n"
            f"Start the report with a blockquote TL;DR from `triage.tldr`:\n"
            f"> **TL;DR**: ...\n\n"
            f"Then write a structured Korean research report in Markdown for '{meta.title}' "
            f"using these exact 6 sections:\n"
            f"1. Overview (Abstract 기반 전체 흐름)\n"
            f"2. Introduction (해결 문제, 기존 한계, 차별점, Contributions)\n"
            f"3. Background & Related Works (카테고리별 Itemize 및 대표 방법론 설명)\n"
            f"4. Main Method (제안 방법론, 기존 대비 차이, 핵심 LaTeX 수식 및 의미)\n"
            f"5. Experiments (벤치마크 설명, 실험 세팅, 결과 비교)\n"
            f"6. Conclusion & Limitations (클로징 정리 및 한계점)\n\n"
            f"Source analysis JSON:\n{bundle_json}"
        )
        ko_resp = run_role(config, "synthesis", ko_prompt, work_dir=work_dir)
        file_was_written_by_agent = ko_path.exists() and ko_path.stat().st_mtime > mtime_before
        if not (file_was_written_by_agent and len(ko_resp.raw_text) < ko_path.stat().st_size // 2):
            ko_path.write_text(ko_resp.raw_text, encoding="utf-8")
        store.record_summary(
            paper_id=meta.paper_id,
            lang="ko",
            runtime=ko_resp.runtime,
            model=ko_resp.model,
            file_path=str(ko_path),
        )

    en_path = work_dir / "summary" / "summary.en.md"
    if include_en and (not en_path.exists() or force):
        en_prompt = (
            f"You are the Report Synthesis Agent.\n"
            f"Start with `> **TL;DR**: ...`, then write a concise, high-precision English research report "
            f"in Markdown for '{meta.title}' using the 6 sections (Overview, Introduction, "
            f"Background & Related Works, Main Method, Experiments, Conclusion & Limitations) "
            f"grounded in the JSON below.\n\nSource analysis JSON:\n{bundle_json}"
        )
        en_resp = run_role(config, "synthesis", en_prompt, work_dir=work_dir)
        en_path.write_text(en_resp.raw_text, encoding="utf-8")
        store.record_summary(
            paper_id=meta.paper_id,
            lang="en",
            runtime=en_resp.runtime,
            model=en_resp.model,
            file_path=str(en_path),
        )

    store.update_paper_status(meta.paper_id, "completed")
    matched_count = sum(1 for h in enriched_highlights if h["matched"])
    artifacts = {
        "original_pdf": str(pdf_path),
        "highlighted_pdf": str(out_pdf),
        "highlights_json": str(out_hl_json),
        "sections_json": prep["sections_json"],
        "summary_ko": str(ko_path),
    }
    if include_en and en_path.exists():
        artifacts["summary_en"] = str(en_path)

    summary_result = {
        "status": "completed",
        "paper_id": meta.paper_id,
        "title": meta.title,
        "tldr": triage.get("tldr", ""),
        "escalated_stages": list(escalated_stages.keys()),
        "work_dir": str(work_dir),
        "artifacts": artifacts,
        "metrics": {
            "pages": prep["page_count"],
            "highlights_total": len(enriched_highlights),
            "highlights_matched": matched_count,
        },
    }
    (work_dir / "result.json").write_text(
        json.dumps(summary_result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return summary_result
