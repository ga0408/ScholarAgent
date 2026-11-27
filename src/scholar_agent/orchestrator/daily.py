"""Daily HF Papers collection & structured hype-filtered abstract triage pipeline."""

from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from scholar_agent.agents.registry import run_role
from scholar_agent.collector.arxiv import fetch_hf_daily_papers, normalize_arxiv_id
from scholar_agent.config import get_project_root, load_agents_config
from scholar_agent.database.sqlite_store import SQLiteStore
from scholar_agent.orchestrator.pipeline import run_paper_pipeline
from scholar_agent.prompts import render_prompt
from scholar_agent.storage.google_drive import GoogleDriveStorage

_TRIAGE_FIELDS = (
    "problem_ko",
    "method_ko",
    "result_ko",
    "caveat_ko",
    "summary_ko",
    "why_read",
    "relevance_score",
    "tags",
)


def _render_triage_markdown(date_str: str, items: list[dict[str, Any]]) -> str:
    lines = [
        f"# Hugging Face Daily Papers — {date_str}",
        "",
        f"총 **{len(items)}편** 수집 완료. 심층 요약할 논문을 선택해 주세요 (`scholar-agent daily-select --index ... --run-summary`).",
        "",
    ]
    for idx, it in enumerate(items, start=1):
        tags = ", ".join(it.get("tags", []))
        rel = int(it.get("relevance_score") or 3)
        stars = "★" * max(1, min(5, rel)) + "☆" * (5 - max(1, min(5, rel)))
        status_badge = " *(이미 처리됨)*" if it.get("already_processed") else ""
        repo = it.get("github_repo", "")
        repo_str = f" | **Code**: {repo}" if repo else ""

        lines.extend(
            [
                f"## {idx}. {it['title']}{status_badge}",
                f"- **arXiv**: `{it['canonical_id']}` | **관심도**: {stars} ({rel}/5) | **Upvotes**: {it.get('upvotes', 0)}{repo_str} | **Tags**: {tags}",
            ]
        )
        if it.get("problem_ko") or it.get("method_ko"):
            lines.extend(
                [
                    f"- **해결 문제**: {it.get('problem_ko', '')}",
                    f"- **핵심 기법**: {it.get('method_ko', '')}",
                    f"- **정량 성과**: {it.get('result_ko', '')}",
                    f"- **전제/유의점**: {it.get('caveat_ko', '')}",
                ]
            )
        else:
            lines.append(f"- **요약**: {it.get('summary_ko', '')}")
        lines.extend(
            [
                f"- **선정 포인트**: {it.get('why_read', '')}",
                "",
            ]
        )
    return "\n".join(lines)


def prepare_daily_triage(
    *,
    limit: int = 10,
    date_str: str = "",
    upload_drive: bool = False,
    force: bool = False,
) -> dict[str, Any]:
    """Fetch HF Daily Papers, deduplicate against SQLite, and run structured abstract triage."""
    root = get_project_root()
    config = load_agents_config()
    store = SQLiteStore(root / "data" / "scholar_agent.db")

    day = date_str[:10] if len(date_str) >= 10 else datetime.now(timezone.utc).strftime("%Y-%m-%d")
    daily_dir = root / "data" / "daily" / day
    daily_dir.mkdir(parents=True, exist_ok=True)
    triage_json_path = daily_dir / "triage.json"
    triage_md_path = daily_dir / "triage.md"

    raw_papers = fetch_hf_daily_papers(limit=limit)
    to_triage: list[dict[str, Any]] = []
    cached_by_id: dict[str, dict[str, Any]] = {}

    if triage_json_path.exists() and not force:
        prev = json.loads(triage_json_path.read_text(encoding="utf-8"))
        for p in prev.get("papers", []):
            cached_by_id[p["paper_id"]] = p

    enriched: list[dict[str, Any]] = []
    for p in raw_papers:
        pid = p["paper_id"]
        existing = store.get_paper(pid)
        already_processed = bool(
            existing and existing.get("status") in ("completed", "selected")
        )
        if not existing:
            store.upsert_paper(
                paper_id=pid,
                canonical_id=p["canonical_id"],
                source="hf_daily",
                title=p["title"],
                authors=p.get("authors", []),
                abstract=p["abstract"],
                pdf_url=p["pdf_url"],
                status="discovered",
            )

        entry = {
            **p,
            "already_processed": already_processed,
        }
        if pid in cached_by_id and not force and cached_by_id[pid].get("method_ko"):
            for f in _TRIAGE_FIELDS:
                if f in cached_by_id[pid]:
                    entry[f] = cached_by_id[pid][f]
        else:
            to_triage.append(entry)
        enriched.append(entry)

    if to_triage:
        schema_path = root / "prompts" / "schemas" / "daily_triage.schema.json"
        payload_for_llm = [
            {
                "paper_id": it["paper_id"],
                "title": it["title"],
                "abstract": it["abstract"],
                "github_repo": it.get("github_repo", ""),
                "ai_keywords": it.get("ai_keywords", []),
            }
            for it in to_triage
        ]
        interests = config.user_profile.research_interests
        interests_bullets = "\n".join(f"- {item}" for item in interests) or "- General ML & LLM Systems"
        prompt = render_prompt(
            "daily_triage",
            interests_bullet_list=interests_bullets,
            papers_json=json.dumps(payload_for_llm, ensure_ascii=False, indent=2),
        )
        resp = run_role(
            config,
            "daily_triage",
            prompt,
            work_dir=daily_dir,
            schema_path=schema_path,
        )
        triage_data = resp.structured_output or json.loads(resp.raw_text)
        by_id = {x["paper_id"]: x for x in triage_data.get("items", [])}

        for it in enriched:
            if it["paper_id"] in by_id:
                res = by_id[it["paper_id"]]
                for f in _TRIAGE_FIELDS:
                    if f in res:
                        it[f] = res[f]
                if not it["already_processed"]:
                    store.update_paper_status(it["paper_id"], "triaged")

    output_doc = {
        "date": day,
        "status": "triaged",
        "total": len(enriched),
        "papers": enriched,
    }
    triage_json_path.write_text(json.dumps(output_doc, ensure_ascii=False, indent=2), encoding="utf-8")
    triage_md_path.write_text(_render_triage_markdown(day, enriched), encoding="utf-8")

    drive_files: dict[str, str] = {}
    if upload_drive:
        drive = GoogleDriveStorage()
        daily_folder_id = drive.ensure_folder_path([drive.root_folder_name, "daily", day])
        drive_files["triage_json"] = drive.upsert_file(
            daily_folder_id, triage_json_path, mime_type="application/json"
        )
        drive_files["triage_md"] = drive.upsert_file(
            daily_folder_id, triage_md_path, mime_type="text/markdown"
        )

    return {
        "status": "triaged",
        "date": day,
        "total": len(enriched),
        "newly_triaged": len(to_triage),
        "triage_json": str(triage_json_path),
        "triage_md": str(triage_md_path),
        "drive_files": drive_files,
        "papers": enriched,
    }


def select_and_run_daily(
    *,
    date_str: str = "",
    indices: list[int] | None = None,
    arxiv_ids: list[str] | None = None,
    run_summary: bool = False,
    upload_drive: bool = False,
) -> dict[str, Any]:
    """Mark selected papers from daily triage and optionally run full summary + highlight."""
    root = get_project_root()
    config = load_agents_config()
    store = SQLiteStore(root / "data" / "scholar_agent.db")
    day = date_str[:10] if len(date_str) >= 10 else datetime.now(timezone.utc).strftime("%Y-%m-%d")
    daily_dir = root / "data" / "daily" / day
    triage_json_path = daily_dir / "triage.json"

    selected_ids: list[str] = []
    if indices:
        if not triage_json_path.exists():
            raise FileNotFoundError(f"Daily triage file not found: {triage_json_path}. Run daily-prepare first.")
        triage_doc = json.loads(triage_json_path.read_text(encoding="utf-8"))
        papers = triage_doc.get("papers", [])
        for idx in indices:
            if 1 <= idx <= len(papers):
                selected_ids.append(papers[idx - 1]["canonical_id"].replace("arxiv:", ""))

    for aid in arxiv_ids or []:
        selected_ids.append(normalize_arxiv_id(aid))

    # Deduplicate while preserving order
    unique_ids = list(dict.fromkeys(selected_ids))
    if not unique_ids:
        raise ValueError("No valid papers selected. Provide --index or --arxiv.")

    for aid in unique_ids:
        pid = f"arxiv_{aid}"
        if store.get_paper(pid):
            store.update_paper_status(pid, "selected")

    selection_path = daily_dir / "selection.json"
    daily_dir.mkdir(parents=True, exist_ok=True)
    selection_path.write_text(
        json.dumps({"date": day, "selected_arxiv_ids": unique_ids}, indent=2),
        encoding="utf-8",
    )

    processed: list[dict[str, Any]] = []
    if run_summary:
        ordered_results: dict[str, dict[str, Any]] = {}
        with ThreadPoolExecutor(max_workers=len(unique_ids)) as pool:
            futs = {pool.submit(run_paper_pipeline, aid): aid for aid in unique_ids}
            for fut in as_completed(futs):
                aid = futs[fut]
                ordered_results[aid] = fut.result()

        drive = GoogleDriveStorage() if upload_drive else None
        for aid in unique_ids:
            res = ordered_results[aid]
            if drive is not None:
                paper_dir = Path(res["work_dir"])
                meta = json.loads((paper_dir / "metadata.json").read_text(encoding="utf-8"))
                res["drive_assets"] = drive.sync_paper(
                    paper_dir,
                    paper_id=res["paper_id"],
                    title=meta.get("title", res.get("title", "")),
                    mode="daily",
                    date_str=day,
                    store=store,
                )
            processed.append(res)

    return {
        "status": "completed" if run_summary else "selected",
        "date": day,
        "selected_arxiv_ids": unique_ids,
        "selection_json": str(selection_path),
        "processed": processed,
    }
