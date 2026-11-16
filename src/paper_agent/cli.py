"""Typer CLI entrypoint for PaperAgent (Main Orchestrator compatible JSON output)."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Optional

import typer

from paper_agent.collector.arxiv import fetch_hf_daily_papers
from paper_agent.orchestrator.pipeline import run_paper_pipeline

app = typer.Typer(help="PaperAgent — Personal Research Intelligence CLI")


@app.command("run")
def run_cmd(
    arxiv: str = typer.Option(..., "--arxiv", help="arXiv ID or URL (e.g. 2410.21276)"),
    out_dir: Optional[Path] = typer.Option(None, "--out-dir", help="Custom output directory"),
    force: bool = typer.Option(False, "--force", help="Re-run cached stages"),
) -> None:
    """Run the full summary + highlight PDF pipeline for a single arXiv paper."""
    result = run_paper_pipeline(arxiv, out_base_dir=out_dir, force=force)
    typer.echo(json.dumps(result, ensure_ascii=False, indent=2))


@app.command("fetch-daily")
def fetch_daily_cmd(
    limit: int = typer.Option(10, "--limit", help="Max number of HF Daily Papers to list"),
) -> None:
    """Fetch today's Hugging Face Daily Papers list as JSON."""
    papers = fetch_hf_daily_papers(limit=limit)
    typer.echo(json.dumps(papers, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    app()
