"""arXiv and Hugging Face Daily Papers collectors with title-similarity verification."""

from __future__ import annotations

import hashlib
import re
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

import httpx
from pydantic import BaseModel

ARXIV_ID_RE = re.compile(r"(\d{4}\.\d{4,5})(v\d+)?")
_STOPWORDS = {"a", "an", "the", "of", "for", "in", "on", "to", "with", "and", "via", "by", "from"}


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


def _tokenize_title(text: str) -> list[str]:
    tokens = re.findall(r"[a-z0-9]+", text.lower())
    return [t for t in tokens if t not in _STOPWORDS]


def score_title_match(query: str, candidate_title: str) -> float:
    """Compute confidence (0..1) that `candidate_title` actually matches `query`.

    Prevents `search_arxiv_by_title` from returning unrelated papers when searching short
    method acronyms (e.g., 'HyperMem', 'CEPE', 'LoCoMo').
    """
    q_clean = " ".join(query.strip().split())
    c_clean = " ".join(candidate_title.strip().split())
    if not q_clean or not c_clean:
        return 0.0

    q_low = q_clean.lower()
    c_low = c_clean.lower()
    if q_low == c_low:
        return 1.0

    q_tokens = _tokenize_title(q_clean)
    c_tokens = set(_tokenize_title(c_clean))
    if not q_tokens or not c_tokens:
        return 0.0

    # Single-token / short acronym query: require exact word or prefix match in candidate title
    if len(q_tokens) <= 2 and len(q_clean) <= 22:
        pattern = re.compile(rf"\b{re.escape(q_low)}\b", re.IGNORECASE)
        if pattern.search(c_clean):
            # Stronger score if it appears at the start before ':' (e.g. "KBLaM: ...")
            if c_low.startswith(q_low + ":") or c_low.startswith(q_low + " "):
                return 0.95
            return 0.75
        return 0.0

    overlap = sum(1 for t in q_tokens if t in c_tokens)
    recall = overlap / len(q_tokens)
    precision = overlap / max(1, len(c_tokens))
    if q_low in c_low:
        return max(0.85, recall)
    return round(0.75 * recall + 0.25 * precision, 4)


def _fetch_hf_paper_metadata(arxiv_id: str) -> PaperMetadata:
    headers = {"User-Agent": "ScholarAgent/0.1 (research platform)"}
    resp = httpx.get(
        f"https://huggingface.co/api/papers/{arxiv_id}",
        headers=headers,
        timeout=30.0,
        follow_redirects=True,
    )
    resp.raise_for_status()
    data = resp.json()
    authors = [
        a.get("name", "").strip()
        for a in data.get("authors", [])
        if isinstance(a, dict) and a.get("name")
    ]
    return PaperMetadata(
        paper_id=f"arxiv_{arxiv_id}",
        canonical_id=f"arxiv:{arxiv_id}",
        source="arxiv",
        title=" ".join((data.get("title") or "").split()),
        authors=authors,
        abstract=" ".join((data.get("summary") or "").split()),
        published_at=(data.get("publishedAt") or "")[:10],
        pdf_url=f"https://arxiv.org/pdf/{arxiv_id}.pdf",
    )


def fetch_arxiv_metadata(arxiv_input: str) -> PaperMetadata:
    arxiv_id = normalize_arxiv_id(arxiv_input)
    url = f"https://export.arxiv.org/api/query?id_list={arxiv_id}"
    headers = {"User-Agent": "ScholarAgent/0.1 (research platform)"}

    last_err: Exception | None = None
    for attempt in range(2):
        try:
            resp = httpx.get(url, headers=headers, timeout=30.0, follow_redirects=True)
            if resp.status_code == 429 and attempt == 0:
                time.sleep(2.0)
                continue
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
        except Exception as exc:
            last_err = exc
            if attempt == 0:
                time.sleep(1.5)

    # Fallback to Hugging Face Papers API when arXiv Atom API rate-limits (429)
    try:
        return _fetch_hf_paper_metadata(arxiv_id)
    except Exception:
        if last_err is not None:
            raise last_err
        raise


def search_arxiv_by_title(query: str, *, min_score: float = 0.55) -> PaperMetadata | None:
    """Search arXiv by paper title/method name and return a verified metadata match."""
    cleaned = " ".join(query.strip().split())
    if not cleaned:
        return None
    headers = {"User-Agent": "ScholarAgent/0.1 (research platform)"}
    ns = {"atom": "http://www.w3.org/2005/Atom"}

    best_meta: PaperMetadata | None = None
    best_score = 0.0

    for prefix in ("ti", "all"):
        resp = httpx.get(
            "https://export.arxiv.org/api/query",
            params={"search_query": f'{prefix}:"{cleaned}"', "max_results": 5},
            headers=headers,
            timeout=20.0,
            follow_redirects=True,
        )
        if resp.status_code != 200:
            continue
        root = ET.fromstring(resp.text)
        for entry in root.findall("atom:entry", ns):
            raw_id = (entry.findtext("atom:id", "", ns) or "").strip()
            match = ARXIV_ID_RE.search(raw_id)
            if not match:
                continue
            arxiv_id = match.group(1)
            title = " ".join((entry.findtext("atom:title", "", ns) or "").split())
            conf = score_title_match(cleaned, title)
            if conf > best_score:
                abstract = " ".join((entry.findtext("atom:summary", "", ns) or "").split())
                published = (entry.findtext("atom:published", "", ns) or "")[:10]
                authors = [
                    (a.findtext("atom:name", "", ns) or "").strip()
                    for a in entry.findall("atom:author", ns)
                ]
                best_score = conf
                best_meta = PaperMetadata(
                    paper_id=f"arxiv_{arxiv_id}",
                    canonical_id=f"arxiv:{arxiv_id}",
                    source="arxiv",
                    title=title,
                    authors=[a for a in authors if a],
                    abstract=abstract,
                    published_at=published,
                    pdf_url=f"https://arxiv.org/pdf/{arxiv_id}.pdf",
                )
        if best_meta is not None and best_score >= min_score:
            return best_meta

    return best_meta if best_score >= min_score else None


def download_pdf(pdf_url: str, dest_path: Path) -> str:
    dest_path.parent.mkdir(parents=True, exist_ok=True)
    if dest_path.exists() and dest_path.stat().st_size > 10_000:
        return hashlib.sha256(dest_path.read_bytes()).hexdigest()

    headers = {"User-Agent": "ScholarAgent/0.1 (research platform)"}
    with httpx.stream("GET", pdf_url, headers=headers, timeout=60.0, follow_redirects=True) as resp:
        resp.raise_for_status()
        data = resp.read()
    dest_path.write_bytes(data)
    return hashlib.sha256(data).hexdigest()


def fetch_hf_daily_papers(limit: int = 10) -> list[dict[str, Any]]:
    """Fetch today's Hugging Face Daily Papers list with code/keyword metadata."""
    resp = httpx.get("https://huggingface.co/api/daily_papers", timeout=30.0, follow_redirects=True)
    resp.raise_for_status()
    items = resp.json()
    results: list[dict[str, Any]] = []
    for item in items[:limit]:
        paper = item.get("paper", {})
        arxiv_id = paper.get("id", "")
        if not arxiv_id:
            continue
        authors = [
            a.get("name", "").strip()
            for a in paper.get("authors", [])
            if isinstance(a, dict) and a.get("name")
        ]
        results.append(
            {
                "paper_id": f"arxiv_{arxiv_id}",
                "canonical_id": f"arxiv:{arxiv_id}",
                "title": " ".join((paper.get("title") or "").split()),
                "authors": authors,
                "abstract": " ".join((paper.get("summary") or "").split()),
                "upvotes": paper.get("upvotes", 0),
                "github_repo": paper.get("githubRepo") or item.get("githubRepo") or "",
                "github_stars": paper.get("githubStars") or 0,
                "ai_keywords": paper.get("ai_keywords") or [],
                "pdf_url": f"https://arxiv.org/pdf/{arxiv_id}.pdf",
            }
        )
    return results
