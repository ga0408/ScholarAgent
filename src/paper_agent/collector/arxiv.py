"""arXiv and Hugging Face Daily Papers collectors."""

from __future__ import annotations

import hashlib
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

import httpx
from pydantic import BaseModel

ARXIV_ID_RE = re.compile(r"(\d{4}\.\d{4,5})(v\d+)?")


class PaperMetadata(BaseModel):
    paper_id: str
    canonical_id: str
    source: str
    title: str
    authors: list[str]
    abstract: str
    published_at: str = ""
    pdf_url: str = ""


def normalize_arxiv_id(arxiv_input: str) -> str:
    match = ARXIV_ID_RE.search(arxiv_input.strip())
    if not match:
        raise ValueError(f"Invalid arXiv ID or URL: {arxiv_input}")
    return match.group(1)


def fetch_arxiv_metadata(arxiv_input: str) -> PaperMetadata:
    arxiv_id = normalize_arxiv_id(arxiv_input)
    url = f"https://export.arxiv.org/api/query?id_list={arxiv_id}"
    resp = httpx.get(url, timeout=30.0, follow_redirects=True)
    resp.raise_for_status()

    ns = {"atom": "http://www.w3.org/2005/Atom"}
    root = ET.fromstring(resp.text)
    entry = root.find("atom:entry", ns)
    if entry is None:
        raise RuntimeError(f"No arXiv entry found for {arxiv_id}")

    title = " ".join((entry.findtext("atom:title", "", ns) or "").split())
    abstract = " ".join((entry.findtext("atom:summary", "", ns) or "").split())
    published = (entry.findtext("atom:published", "", ns) or "")[:10]
    authors = [
        (a.findtext("atom:name", "", ns) or "").strip()
        for a in entry.findall("atom:author", ns)
    ]

    return PaperMetadata(
        paper_id=f"arxiv_{arxiv_id}",
        canonical_id=f"arxiv:{arxiv_id}",
        source="arxiv",
        title=title,
        authors=[a for a in authors if a],
        abstract=abstract,
        published_at=published,
        pdf_url=f"https://arxiv.org/pdf/{arxiv_id}.pdf",
    )


def download_pdf(pdf_url: str, dest_path: Path) -> str:
    dest_path.parent.mkdir(parents=True, exist_ok=True)
    if dest_path.exists() and dest_path.stat().st_size > 10_000:
        return hashlib.sha256(dest_path.read_bytes()).hexdigest()

    headers = {"User-Agent": "PaperAgent/0.1 (research platform)"}
    with httpx.stream("GET", pdf_url, headers=headers, timeout=60.0, follow_redirects=True) as resp:
        resp.raise_for_status()
        data = resp.read()
    dest_path.write_bytes(data)
    return hashlib.sha256(data).hexdigest()


def fetch_hf_daily_papers(limit: int = 10) -> list[dict[str, Any]]:
    """Fetch today's Hugging Face Daily Papers list."""
    resp = httpx.get("https://huggingface.co/api/daily_papers", timeout=30.0, follow_redirects=True)
    resp.raise_for_status()
    items = resp.json()
    results: list[dict[str, Any]] = []
    for item in items[:limit]:
        paper = item.get("paper", {})
        arxiv_id = paper.get("id", "")
        if not arxiv_id:
            continue
        results.append(
            {
                "paper_id": f"arxiv_{arxiv_id}",
                "canonical_id": f"arxiv:{arxiv_id}",
                "title": paper.get("title", ""),
                "abstract": paper.get("summary", ""),
                "upvotes": item.get("paper", {}).get("upvotes", 0),
                "pdf_url": f"https://arxiv.org/pdf/{arxiv_id}.pdf",
            }
        )
    return results
