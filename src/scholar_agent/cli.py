"""Typer CLI entrypoint for ScholarAgent (Main Orchestrator compatible JSON output)."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Optional

import typer

from scholar_agent.collector.arxiv import fetch_hf_daily_papers, normalize_arxiv_id
from scholar_agent.config import get_project_root
from scholar_agent.database.sqlite_store import SQLiteStore
from scholar_agent.orchestrator.pipeline import run_paper_pipeline
from scholar_agent.storage.google_drive import GoogleDriveStorage

app = typer.Typer(help="ScholarAgent — Personal Research Intelligence CLI")


@app.command("run")
def run_cmd(
    arxiv: str = typer.Option(..., "--arxiv", help="arXiv ID or URL (e.g. 2410.10450)"),
    out_dir: Optional[Path] = typer.Option(None, "--out-dir", help="Custom output directory"),
    force: bool = typer.Option(False, "--force", help="Re-run cached stages"),
    upload_drive: bool = typer.Option(False, "--upload-drive", help="Upload outputs to Google Drive"),
) -> None:
    """Run the full summary + highlight PDF pipeline for a single arXiv paper."""
    result = run_paper_pipeline(arxiv, out_base_dir=out_dir, force=force)
    if upload_drive:
        paper_dir = Path(result["work_dir"])
        meta = json.loads((paper_dir / "metadata.json").read_text(encoding="utf-8"))
        store = SQLiteStore(get_project_root() / "data" / "scholar_agent.db")
        drive = GoogleDriveStorage()
        result["drive_assets"] = drive.sync_paper(
            paper_dir,
            paper_id=result["paper_id"],
            published_at=meta.get("published_at", ""),
            store=store,
        )
    typer.echo(json.dumps(result, ensure_ascii=False, indent=2))


@app.command("fetch-daily")
def fetch_daily_cmd(
    limit: int = typer.Option(10, "--limit", help="Max number of HF Daily Papers to list"),
) -> None:
    """Fetch today's Hugging Face Daily Papers list as JSON."""
    papers = fetch_hf_daily_papers(limit=limit)
    typer.echo(json.dumps(papers, ensure_ascii=False, indent=2))


@app.command("auth-drive")
def auth_drive_cmd() -> None:
    """Authenticate Google Drive OAuth2 and save token.json locally."""
    drive = GoogleDriveStorage()
    drive.authenticate(interactive=True)
    typer.echo(json.dumps({"status": "authenticated", "token_path": str(drive.token_path)}, indent=2))


@app.command("sync-drive")
def sync_drive_cmd(
    arxiv: str = typer.Option(..., "--arxiv", help="arXiv ID of already processed paper"),
) -> None:
    """Upload an already processed paper's outputs to Google Drive."""
    arxiv_id = normalize_arxiv_id(arxiv)
    paper_id = f"arxiv_{arxiv_id}"
    root = get_project_root()
    paper_dir = root / "data" / "papers" / paper_id
    if not paper_dir.exists():
        raise typer.BadParameter(f"Processed paper directory not found: {paper_dir}")

    meta_path = paper_dir / "metadata.json"
    published_at = ""
    if meta_path.exists():
        published_at = json.loads(meta_path.read_text(encoding="utf-8")).get("published_at", "")

    store = SQLiteStore(root / "data" / "scholar_agent.db")
    drive = GoogleDriveStorage()
    uploaded = drive.sync_paper(
        paper_dir,
        paper_id=paper_id,
        published_at=published_at,
        store=store,
    )
    typer.echo(json.dumps({"status": "synced", "paper_id": paper_id, "drive_assets": uploaded}, indent=2))


if __name__ == "__main__":
    app()
