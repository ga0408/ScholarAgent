"""Deterministic PDF highlighter using PyMuPDF + Appearance Stream generation."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

import pymupdf as fitz  # PyMuPDF

# RGB tuples (0..1) by highlight category
CATEGORY_COLORS: dict[str, tuple[float, float, float]] = {
    "background": (0.85, 0.93, 1.0),    # soft blue
    "contribution": (1.0, 0.93, 0.45),  # warm yellow
    "method": (0.76, 0.96, 0.78),       # mint green
    "experiment": (1.0, 0.82, 0.65),    # soft orange
    "limitation": (1.0, 0.76, 0.82),    # soft pink
}


def _norm(text: str) -> str:
    text = text.replace("\ufb01", "fi").replace("\ufb02", "fl")
    text = re.sub(r"-\s+", "", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def _search_quote_rects(page: fitz.Page, quote: str, clip_bbox: list[float] | None) -> list[fitz.Rect]:
    """Try exact search, then normalized/substring chunks within optional block clip."""
    clip = fitz.Rect(clip_bbox) if clip_bbox else None
    candidates = [quote, _norm(quote)]

    for cand in candidates:
        if not cand:
            continue
        rects = page.search_for(cand, clip=clip)
        if rects:
            return rects

    # Fallback: search without clip if block_id shifted slightly
    if clip is not None:
        for cand in candidates:
            if not cand:
                continue
            rects = page.search_for(cand)
            if rects:
                return rects

    # Fallback 2: anchor on first 8 words and last 6 words if long quote wraps awkwardly
    words = _norm(quote).split()
    if len(words) >= 10:
        head = " ".join(words[:7])
        rects = page.search_for(head, clip=clip) or page.search_for(head)
        if rects:
            return rects

    return []


def apply_highlights(
    pdf_path: Path,
    segments_path: Path,
    highlights_input: list[dict[str, Any]],
    out_pdf_path: Path,
    out_json_path: Path,
) -> list[dict[str, Any]]:
    """Resolve exact PDF coordinates and embed highlight annotations with /AP streams."""
    segments = json.loads(segments_path.read_text(encoding="utf-8"))
    seg_by_id = {s["block_id"]: s for s in segments}

    doc = fitz.open(str(pdf_path))
    enriched: list[dict[str, Any]] = []

    try:
        for item in highlights_input:
            block_id = item.get("block_id", "")
            seg = seg_by_id.get(block_id)
            page_num = int(seg["page"]) if seg else int(item.get("page", 1))
            if page_num < 1 or page_num > len(doc):
                continue

            page = doc[page_num - 1]
            quote = item.get("quote", "").strip()
            clip_bbox = seg["bbox"] if seg else None
            rects = _search_quote_rects(page, quote, clip_bbox)

            category = item.get("category", "method")
            color = CATEGORY_COLORS.get(category, CATEGORY_COLORS["contribution"])

            if rects:
                annot = page.add_highlight_annot(rects)
                annot.set_colors(stroke=color)
                annot.set_opacity(0.45)
                annot.set_info(
                    title="PaperAgent",
                    subject=category,
                    content=item.get("reason", ""),
                )
                # Critical for Android PdfRenderer / MyNote visibility: generate /AP stream
                annot.update()

            enriched.append(
                {
                    "block_id": block_id,
                    "page": page_num,
                    "category": category,
                    "importance": int(item.get("importance", 3)),
                    "quote": quote,
                    "reason": item.get("reason", ""),
                    "matched": bool(rects),
                    "rects": [[round(r.x0, 2), round(r.y0, 2), round(r.x1, 2), round(r.y1, 2)] for r in rects],
                }
            )

        out_pdf_path.parent.mkdir(parents=True, exist_ok=True)
        doc.save(str(out_pdf_path), garbage=3, deflate=True)
    finally:
        doc.close()

    out_json_path.write_text(json.dumps(enriched, ensure_ascii=False, indent=2), encoding="utf-8")
    return enriched
