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
    # Truncate safety cap for prompt context while preserving full file on disk
    paper_text_excerpt = extracted_text[:80_000]

    # 1. Triage
    triage_prompt = (
        f"You are the Paper Triage Agent.\n{TERMINOLOGY_RULE}\n\n"
        f"Title: {meta.title}\nAbstract: {meta.abstract}\n\n"
        f"Extracted paper text (with [block_id] and <!-- PAGE N -->):\n{paper_text_excerpt[:24_000]}"
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

    method_pages = triage.get("key_pages", {}).get("method_pages", [2, 3, 4])
    exp_pages = triage.get("key_pages", {}).get("experiment_pages", [5, 6, 7])
    method_imgs = _select_page_images(work_dir, method_pages, max_images=4)
    exp_imgs = _select_page_images(work_dir, exp_pages, max_images=4)

    # 2. Parallel Subagents
    stages = {
        "method_deep_dive": {
            "role": "method_deep_dive",
            "schema": "method_deep_dive.schema.json",
            "images": method_imgs,
            "prompt": (
                f"You are the Method Deep Dive Agent.\n{TERMINOLOGY_RULE}\n\n"
                f"Paper: {meta.title}\nTriage Overview: {triage.get('overview')}\n\n"
                f"Analyze the core architecture, tensor flow, complexity, and differences from prior art.\n"
                f"Full extracted paper:\n{paper_text_excerpt}"
            ),
        },
        "equations": {
            "role": "equation_explainer",
            "schema": "equations.schema.json",
            "images": method_imgs,
            "prompt": (
                f"You are the Equation Explainer Agent.\n{TERMINOLOGY_RULE}\n\n"
                f"Inspect the attached method page images and extracted text for '{meta.title}'. "
                f"Transcribe the key equations into clean LaTeX, list variables, and explain the design rationale.\n\n"
                f"Extracted text:\n{paper_text_excerpt[:45_000]}"
            ),
        },
        "figures_tables": {
            "role": "figure_table",
            "schema": "figures_tables.schema.json",
            "images": exp_imgs,
            "prompt": (
                f"You are the Figure & Table Analyzer Agent.\n{TERMINOLOGY_RULE}\n\n"
                f"Inspect the attached experiment page images and extracted text for '{meta.title}'. "
                f"Summarize experimental setup and verify exact benchmark numbers from tables/figures.\n\n"
                f"Extracted text:\n{paper_text_excerpt[:45_000]}"
            ),
        },
        "critical_review": {
            "role": "critical_review",
            "schema": "critical_review.schema.json",
            "images": None,
            "prompt": (
                f"You are the Critical Reviewer Agent.\n{TERMINOLOGY_RULE}\n\n"
                f"Identify concrete limitations, practical takeaways, critical analysis, and future directions "
                f"for '{meta.title}'.\n\nExtracted text:\n{paper_text_excerpt}"
            ),
        },
        "highlights_raw": {
            "role": "highlight_selector",
            "schema": "highlights.schema.json",
            "images": None,
            "prompt": (
                f"You are the Highlight Selector Agent.\n"
                f"Select 10 to 15 high-value verbatim sentences across background, contribution, method, "
                f"experiment, and limitation from '{meta.title}'.\n"
                f"CRITICAL: Each `quote` MUST be an exact verbatim substring from the corresponding `[block_id]` "
                f"line below. Never paraphrase.\n\nExtracted blocks:\n{paper_text_excerpt[:60_000]}"
            ),
        },
    }

    results: dict[str, dict[str, Any]] = {}
    max_workers = max(1, config.defaults.max_concurrent_runs)
    with ThreadPoolExecutor(max_workers=max_workers) as pool:
        future_map = {
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
            for name, spec in stages.items()
        }
        for fut in as_completed(future_map):
            name = future_map[fut]
            results[name] = fut.result()

    # 3. Deterministic PDF Highlighting
    out_pdf = work_dir / "highlights" / "highlighted.pdf"
    out_hl_json = work_dir / "highlights" / "highlights.json"
    enriched_highlights = apply_highlights(
        pdf_path,
        Path(prep["segments_json"]),
        results["highlights_raw"].get("highlights", []),
        out_pdf,
        out_hl_json,
    )
    store.replace_highlights(meta.paper_id, enriched_highlights)

    # 4. Report Synthesis (Korean & English)
    bundle = {
        "metadata": meta.model_dump(),
        "triage": triage,
        "method_deep_dive": results["method_deep_dive"],
        "equations": results["equations"],
        "figures_tables": results["figures_tables"],
        "critical_review": results["critical_review"],
    }
    bundle_json = json.dumps(bundle, ensure_ascii=False, indent=2)

    ko_path = work_dir / "summary" / "summary.ko.md"
    if not ko_path.exists() or force:
        ko_prompt = (
            f"You are the Report Synthesis Agent.\n{TERMINOLOGY_RULE}\n\n"
            f"Write a comprehensive, structured Korean research report in Markdown for '{meta.title}'.\n"
            f"Use these exact 10 sections:\n"
            f"1. Overview\n2. Background\n3. Problem\n4. Motivation\n5. Contribution\n"
            f"6. Method (include architecture details & key LaTeX equations)\n"
            f"7. Experiment (include verified numbers & tables)\n"
            f"8. Limitation\n9. Takeaways\n10. Critical Analysis\n\n"
            f"Source analysis JSON:\n{bundle_json}"
        )
        ko_resp = run_role(config, "synthesis", ko_prompt, work_dir=work_dir)
        ko_path.write_text(ko_resp.raw_text, encoding="utf-8")
        store.record_summary(
            paper_id=meta.paper_id,
            lang="ko",
            runtime=ko_resp.runtime,
            model=ko_resp.model,
            file_path=str(ko_path),
        )

    en_path = work_dir / "summary" / "summary.en.md"
    if not en_path.exists() or force:
        en_prompt = (
            f"You are the Report Synthesis Agent.\n"
            f"Write a concise, high-precision English research report in Markdown for '{meta.title}' "
            f"using the same 10 sections (Overview, Background, Problem, Motivation, Contribution, "
            f"Method, Experiment, Limitation, Takeaways, Critical Analysis) grounded in the JSON below.\n\n"
            f"Source analysis JSON:\n{bundle_json}"
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
    summary_result = {
        "status": "completed",
        "paper_id": meta.paper_id,
        "title": meta.title,
        "work_dir": str(work_dir),
        "artifacts": {
            "original_pdf": str(pdf_path),
            "highlighted_pdf": str(out_pdf),
            "highlights_json": str(out_hl_json),
            "summary_ko": str(ko_path),
            "summary_en": str(en_path),
        },
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
