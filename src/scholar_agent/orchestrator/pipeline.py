"""Single-paper end-to-end pipeline: Triage orientation -> 6-way parallel analysis -> Synthesis."""

from __future__ import annotations

import json
from concurrent.futures import Future, ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any

from scholar_agent.agents.registry import run_role
from scholar_agent.collector.arxiv import PaperMetadata, download_pdf, fetch_arxiv_metadata
from scholar_agent.config import AgentsConfig, get_project_root, load_agents_config
from scholar_agent.database.sqlite_store import SQLiteStore
from scholar_agent.processors.highlighter import apply_highlights
from scholar_agent.processors.pdf_parser import preprocess_pdf
from scholar_agent.prompts import render_prompt
from scholar_agent.tools.paper_tools import lookup_benchmark, triage_paper


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
    *,
    include_appendix: bool = True,
) -> str:
    """Assemble a balanced, section-labeled paper context across all canonical sections."""
    sections = sections_data.get("sections", {})
    if not sections:
        return fallback_text[:95_000]

    budgets = [
        ("abstract", "Abstract", 4_500),
        ("introduction", "Introduction", 14_000),
        ("related_work", "Background & Related Works", 12_000),
        ("method", "Main Method & Architecture", 28_000),
        ("experiment", "Experiments & Results", 26_000),
        ("conclusion", "Conclusion, Discussion & Limitations", 10_000),
    ]
    if include_appendix:
        budgets.append(("appendix", "Appendix (Ablations & Implementation Details)", 12_000))

    parts: list[str] = []
    for key, label, limit in budgets:
        sec = sections.get(key)
        if sec and sec.get("text"):
            headings_str = ", ".join(sec.get("headings", [])[:6])
            header_meta = f"pages {sec['start_page']}-{sec['end_page']}"
            if headings_str:
                header_meta += f" | headings: {headings_str}"
            parts.append(f"=== {label} ({header_meta}) ===\n{sec['text'][:limit]}")
    return "\n\n".join(parts) if parts else fallback_text[:95_000]


def _resolve_section_pages(
    sections_map: dict[str, Any],
    section_key: str,
    page_count: int,
    fallback: list[int],
) -> list[int]:
    sec = sections_map.get(section_key)
    if sec and sec.get("start_page") and sec.get("end_page"):
        return list(range(int(sec["start_page"]), int(sec["end_page"]) + 1))
    return [p for p in fallback if 1 <= p <= page_count] or [1]


def _select_page_images(
    work_dir: Path,
    target_pages: list[int],
    visual_pages: list[int] | None = None,
    *,
    max_images: int = 6,
) -> list[Path]:
    """Select rendered page PNGs, prioritizing pages within `target_pages` that contain figures/tables."""
    pages_dir = work_dir / "pages"
    visual_set = set(int(p) for p in (visual_pages or []))
    unique_targets: list[int] = []
    for p in target_pages:
        pi = int(p)
        if pi not in unique_targets:
            unique_targets.append(pi)

    prioritized = [p for p in unique_targets if p in visual_set] + [
        p for p in unique_targets if p not in visual_set
    ]
    chosen_nums = sorted(prioritized[:max_images])

    selected: list[Path] = []
    for p in chosen_nums:
        candidate = pages_dir / f"page_{p:03d}.png"
        if candidate.exists():
            selected.append(candidate)
    return selected


def run_paper_pipeline(
    arxiv_input: str,
    *,
    out_base_dir: Path | None = None,
    force: bool = False,
    include_en: bool = False,
    fast_mode: bool = False,
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

    try:
        # Phase 0: Deterministic PDF Preprocessing
        prep = preprocess_pdf(pdf_path, work_dir)
        page_count = int(prep["page_count"])
        extracted_text = Path(prep["extracted_md"]).read_text(encoding="utf-8")
        sections_data = json.loads(Path(prep["sections_json"]).read_text(encoding="utf-8"))
        sections_map = sections_data.get("sections", {})
        visual_pages: list[int] = sections_data.get("visual_pages", [])
        toc_summary = json.dumps(sections_data.get("toc", []), ensure_ascii=False, indent=2)
        paper_text_excerpt = extracted_text[:90_000]
        sectioned_context = _build_sectioned_context(sections_data, extracted_text, include_appendix=True)
        highlight_blocks = _build_sectioned_context(sections_data, extracted_text, include_appendix=False)

        abstract_text = sections_map.get("abstract", {}).get("text") or meta.abstract
        intro_excerpt = sections_map.get("introduction", {}).get("text", "")[:5_000]
        appendix_text = sections_map.get("appendix", {}).get("text", "")[:15_000]

        # Phase 1: Fast Triage Orientation (~20s) — establishes TL;DR & verified key_pages first
        triage_prompt = render_prompt(
            "triage",
            title=meta.title,
            abstract_text=abstract_text,
            intro_excerpt=intro_excerpt or "(Introduction section not separately headed; see TOC)",
            toc_summary=toc_summary,
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

        key_pages = triage.get("key_pages", {})
        method_pages = key_pages.get("method_pages") or _resolve_section_pages(
            sections_map, "method", page_count, [2, 3, 4, 5]
        )
        exp_pages = key_pages.get("experiment_pages") or _resolve_section_pages(
            sections_map, "experiment", page_count, [6, 7, 8, 9]
        )
        if "appendix" in sections_map:
            app_sec = sections_map["appendix"]
            app_visual = [
                p for p in visual_pages if int(app_sec["start_page"]) <= p <= int(app_sec["end_page"])
            ][:2]
            exp_pages = list(exp_pages) + [p for p in app_visual if p not in exp_pages]

        method_imgs = _select_page_images(work_dir, method_pages, visual_pages, max_images=6)
        exp_imgs = _select_page_images(work_dir, exp_pages, visual_pages, max_images=6)

        method_base_text = sections_map.get("method", {}).get("text") or paper_text_excerpt[:45_000]
        method_full_text = (
            f"{method_base_text[:42_000]}\n\n=== Relevant Appendix Excerpt ===\n{appendix_text}"
            if appendix_text
            else method_base_text[:50_000]
        )
        exp_base_text = sections_map.get("experiment", {}).get("text") or paper_text_excerpt[:45_000]
        exp_full_text = (
            f"{exp_base_text[:42_000]}\n\n=== Relevant Appendix / Ablation Excerpt ===\n{appendix_text}"
            if appendix_text
            else exp_base_text[:50_000]
        )
        triage_context = f"{triage.get('tldr', '')}\n{triage.get('overview', '')}".strip() or meta.abstract

        # Phase 2: Unthrottled Parallel Fan-Out across ALL body analysis & highlight subagents
        parallel_specs: dict[str, dict[str, Any]] = {
            "core_summary": {
                "role": "core_summary",
                "schema": "core_summary.schema.json",
                "images": None,
                "prompt": render_prompt(
                    "core_summary",
                    title=meta.title,
                    sectioned_context=sectioned_context,
                ),
            },
            "highlights_raw": {
                "role": "highlight_selector",
                "schema": "highlights.schema.json",
                "images": None,
                "prompt": render_prompt(
                    "highlights",
                    title=meta.title,
                    abstract_text=triage_context,
                    highlight_blocks=highlight_blocks[:95_000],
                ),
            },
        }

        deep_stage_names: list[str] = []
        if not fast_mode:
            deep_stage_names = ["method_deep_dive", "equations", "figures_tables", "critical_review"]
            parallel_specs["method_deep_dive"] = {
                "role": "method_deep_dive",
                "schema": "method_deep_dive.schema.json",
                "images": method_imgs,
                "prompt": render_prompt(
                    "method_deep_dive",
                    title=meta.title,
                    abstract_text=triage_context,
                    method_text=method_full_text,
                ),
            }
            parallel_specs["equations"] = {
                "role": "equation_explainer",
                "schema": "equations.schema.json",
                "images": method_imgs,
                "prompt": render_prompt(
                    "equations",
                    title=meta.title,
                    method_text=method_full_text,
                ),
            }
            parallel_specs["figures_tables"] = {
                "role": "figure_table",
                "schema": "figures_tables.schema.json",
                "images": exp_imgs,
                "prompt": render_prompt(
                    "figures_tables",
                    title=meta.title,
                    exp_text=exp_full_text,
                ),
            }
            parallel_specs["critical_review"] = {
                "role": "critical_review",
                "schema": "critical_review.schema.json",
                "images": None,
                "prompt": render_prompt(
                    "critical_review",
                    title=meta.title,
                    abstract_text=triage_context,
                    sectioned_context=sectioned_context[:80_000],
                ),
            }

        out_pdf = work_dir / "highlights" / "highlighted.pdf"
        out_hl_json = work_dir / "highlights" / "highlights.json"

        parallel_outputs: dict[str, dict[str, Any]] = {}
        enriched_highlights: list[dict[str, Any]] = []
        related_work_triages: dict[str, Any] = {}
        benchmark_lookups: dict[str, Any] = {}

        with ThreadPoolExecutor(max_workers=len(parallel_specs) + 8) as pool:
            stage_futs: dict[Future[dict[str, Any]], str] = {
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
                for name, spec in parallel_specs.items()
            }

            hl_apply_fut: Future[list[dict[str, Any]]] | None = None
            triage_futs: dict[Future[dict[str, Any]], str] = {}
            bench_futs: dict[Future[dict[str, Any]], str] = {}

            for fut in as_completed(stage_futs):
                stage_name = stage_futs[fut]
                stage_data = fut.result()
                parallel_outputs[stage_name] = stage_data

                # Render highlighted.pdf immediately in background while remaining deep-dive agents finish
                if stage_name == "highlights_raw":
                    hl_apply_fut = pool.submit(
                        apply_highlights,
                        pdf_path,
                        Path(prep["segments_json"]),
                        stage_data.get("highlights", []),
                        out_pdf,
                        out_hl_json,
                    )
                elif stage_name == "core_summary":
                    triage_targets: list[tuple[str, str]] = []
                    for cat in stage_data.get("related_works", []):
                        for m in cat.get("methods", []):
                            if m.get("needs_triage") and len(triage_targets) < 5:
                                triage_targets.append((m["name"], m.get("arxiv_hint") or m["name"]))
                    benchmark_targets: list[str] = [
                        b["name"]
                        for b in stage_data.get("experiments", {}).get("benchmarks", [])
                        if b.get("needs_lookup")
                    ][:5]

                    for method_name, query in triage_targets:
                        tf = pool.submit(
                            triage_paper,
                            query,
                            work_dir=work_dir,
                            config=config,
                            store=store,
                        )
                        triage_futs[tf] = method_name
                    for b_name in benchmark_targets:
                        bf = pool.submit(
                            lookup_benchmark,
                            b_name,
                            work_dir=work_dir,
                            config=config,
                            store=store,
                        )
                        bench_futs[bf] = b_name

            for tf in as_completed(triage_futs):
                m_name = triage_futs[tf]
                try:
                    related_work_triages[m_name] = tf.result()
                except Exception as exc:
                    related_work_triages[m_name] = {"error": str(exc)}

            for bf in as_completed(bench_futs):
                b_name = bench_futs[bf]
                try:
                    benchmark_lookups[b_name] = bf.result()
                except Exception as exc:
                    benchmark_lookups[b_name] = {"error": str(exc)}

            if hl_apply_fut is not None:
                enriched_highlights = hl_apply_fut.result()
                store.replace_highlights(meta.paper_id, enriched_highlights)

        core_summary = parallel_outputs["core_summary"]
        deep_results = {k: parallel_outputs[k] for k in deep_stage_names if k in parallel_outputs}

        # Phase 3: Grounded Report Synthesis (KO and optional EN run concurrently)
        bundle: dict[str, Any] = {
            "metadata": meta.model_dump(),
            "triage": triage,
            "core_summary": core_summary,
        }
        if deep_results:
            bundle["deep_dives"] = deep_results
        if related_work_triages:
            bundle["related_work_triages"] = related_work_triages
        if benchmark_lookups:
            bundle["benchmark_lookups"] = benchmark_lookups
        bundle_json = json.dumps(bundle, ensure_ascii=False, indent=2)
        authors_str = ", ".join(meta.authors[:8]) + (" et al." if len(meta.authors) > 8 else "")
        paper_excerpts = sectioned_context[:45_000]

        ko_path = work_dir / "summary" / "summary.ko.md"
        en_path = work_dir / "summary" / "summary.en.md"

        synth_tasks: dict[str, tuple[Path, str]] = {}
        if not ko_path.exists() or force:
            synth_tasks["ko"] = (
                ko_path,
                render_prompt(
                    "synthesis_ko",
                    title=meta.title,
                    canonical_id=meta.canonical_id,
                    authors_str=authors_str or "Unknown",
                    published_at=meta.published_at or "arXiv",
                    bundle_json=bundle_json,
                    paper_excerpts=paper_excerpts,
                ),
            )
        if include_en and (not en_path.exists() or force):
            synth_tasks["en"] = (
                en_path,
                render_prompt(
                    "synthesis_en",
                    title=meta.title,
                    canonical_id=meta.canonical_id,
                    bundle_json=bundle_json,
                    paper_excerpts=paper_excerpts,
                ),
            )

        if synth_tasks:
            with ThreadPoolExecutor(max_workers=len(synth_tasks)) as synth_pool:
                synth_futs = {
                    synth_pool.submit(run_role, config, "synthesis", prm, work_dir=work_dir): (lang, path)
                    for lang, (path, prm) in synth_tasks.items()
                }
                for sf in as_completed(synth_futs):
                    lang, out_md_path = synth_futs[sf]
                    resp = sf.result()
                    out_md_path.write_text(resp.raw_text.strip() + "\n", encoding="utf-8")
                    store.record_summary(
                        paper_id=meta.paper_id,
                        lang=lang,
                        runtime=resp.runtime,
                        model=resp.model,
                        file_path=str(out_md_path),
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
            "executed_deep_stages": deep_stage_names,
            "work_dir": str(work_dir),
            "artifacts": artifacts,
            "metrics": {
                "pages": page_count,
                "highlights_total": len(enriched_highlights),
                "highlights_matched": matched_count,
            },
        }
        (work_dir / "result.json").write_text(
            json.dumps(summary_result, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        return summary_result
    except Exception:
        store.update_paper_status(meta.paper_id, "failed")
        raise
