"""Deterministic PDF highlighter using PyMuPDF with full-span anchor matching & rect merging."""

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
    text = (
        text.replace("\ufb00", "ff")
        .replace("\ufb01", "fi")
        .replace("\ufb02", "fl")
        .replace("\ufb03", "ffi")
        .replace("\ufb04", "ffl")
        .replace("’", "'")
        .replace("‘", "'")
        .replace("“", '"')
        .replace("”", '"')
        .replace("–", "-")
        .replace("—", "-")
    )
    text = re.sub(r"-\s+", "", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def _merge_line_rects(rects: list[fitz.Rect]) -> list[fitz.Rect]:
    """Merge overlapping or horizontally adjacent rects on the same text line.

    Prevents double-opacity blotches when drawing background highlight bars.
    """
    valid = [fitz.Rect(r) for r in rects if not r.is_empty and r.width > 0.5 and r.height > 0.5]
    if not valid:
        return []

    valid.sort(key=lambda r: (round(r.y0, 1), round(r.x0, 1)))
    merged: list[fitz.Rect] = [valid[0]]

    for cur in valid[1:]:
        prev = merged[-1]
        same_line = abs(cur.y0 - prev.y0) <= 3.5 and abs(cur.y1 - prev.y1) <= 4.5
        horiz_touch = cur.x0 <= prev.x1 + 8.0 and cur.x1 >= prev.x0 - 2.0
        if same_line and horiz_touch:
            merged[-1] = fitz.Rect(
                min(prev.x0, cur.x0),
                min(prev.y0, cur.y0),
                max(prev.x1, cur.x1),
                max(prev.y1, cur.y1),
            )
        else:
            merged.append(cur)
    return merged


def _span_between_anchors(
    page: fitz.Page,
    head_rects: list[fitz.Rect],
    tail_rects: list[fitz.Rect],
    clip: fitz.Rect | None,
) -> list[fitz.Rect]:
    """Collect full line rects from `head_rects[0]` through `tail_rects[-1]` within block/column."""
    start_r = head_rects[0]
    end_r = tail_rects[-1]
    if end_r.y1 < start_r.y0 - 2.0:
        return []

    # Bound horizontal span to clip (block bbox) if available, otherwise infer from anchors
    search_box = fitz.Rect(clip) if clip is not None else fitz.Rect(
        min(start_r.x0, end_r.x0) - 40.0,
        start_r.y0 - 2.0,
        max(start_r.x1, end_r.x1) + 180.0,
        end_r.y1 + 2.0,
    )
    words = page.get_text("words", clip=search_box)
    if not words:
        return _merge_line_rects([*head_rects, *tail_rects])

    selected_rects: list[fitz.Rect] = []
    for w in words:
        wx0, wy0, wx1, wy1 = float(w[0]), float(w[1]), float(w[2]), float(w[3])
        w_cy = (wy0 + wy1) / 2.0
        if w_cy < start_r.y0 - 1.5 or w_cy > end_r.y1 + 1.5:
            continue
        # On the first line, skip words strictly to the left of start_r
        if abs(wy0 - start_r.y0) <= 4.0 and wx1 <= start_r.x0 + 1.0:
            continue
        # On the last line, skip words strictly to the right of end_r
        if abs(wy0 - end_r.y0) <= 4.0 and wx0 >= end_r.x1 - 1.0:
            continue
        selected_rects.append(fitz.Rect(wx0, wy0, wx1, wy1))

    return _merge_line_rects(selected_rects) if selected_rects else _merge_line_rects([*head_rects, *tail_rects])


def _search_quote_rects(page: fitz.Page, quote: str, clip_bbox: list[float] | None) -> list[fitz.Rect]:
    """Resolve exact sentence highlight rects via exact search, normalized search, or head-to-tail span."""
    clip = fitz.Rect(clip_bbox) if clip_bbox else None
    # Slightly pad clip bbox so boundary ascenders/descenders never clip search_for
    if clip is not None:
        clip = fitz.Rect(clip.x0 - 3.0, clip.y0 - 3.0, clip.x1 + 3.0, clip.y1 + 3.0)

    candidates: list[str] = []
    for c in (quote.strip(), _norm(quote)):
        if c and c not in candidates:
            candidates.append(c)

    # 1. Exact / normalized match inside segment clip
    for cand in candidates:
        rects = page.search_for(cand, clip=clip)
        if rects:
            return _merge_line_rects(rects)

    # 2. Exact / normalized match across full page if block_id drifted slightly
    if clip is not None:
        for cand in candidates:
            rects = page.search_for(cand)
            if rects:
                return _merge_line_rects(rects)

    # 3. Head-to-Tail full-sentence span anchor (handles inline math / hyphenated line wraps)
    words = _norm(quote).split()
    if len(words) >= 8:
        for anchor_len in (6, 4):
            if len(words) < anchor_len * 2:
                continue
            head = " ".join(words[:anchor_len])
            tail = " ".join(words[-anchor_len:])
            head_rects = page.search_for(head, clip=clip) or page.search_for(head)
            tail_rects = page.search_for(tail, clip=clip) or page.search_for(tail)
            if head_rects and tail_rects:
                spanned = _span_between_anchors(page, head_rects, tail_rects, clip)
                if spanned:
                    return spanned

    # 4. Contiguous sliding-window union inside clip (when trailing math symbol breaks tail)
    if len(words) >= 6 and clip is not None:
        chunk_rects: list[fitz.Rect] = []
        step = 4
        win = 5
        for i in range(0, max(1, len(words) - win + 1), step):
            phrase = " ".join(words[i : i + win])
            found = page.search_for(phrase, clip=clip)
            if found:
                chunk_rects.extend(found)
        if chunk_rects:
            return _merge_line_rects(chunk_rects)

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
    seen_quotes: set[tuple[int, str]] = set()

    try:
        for item in highlights_input:
            block_id = item.get("block_id", "")
            seg = seg_by_id.get(block_id)
            page_num = int(seg["page"]) if seg else int(item.get("page", 1))
            if page_num < 1 or page_num > len(doc):
                continue

            quote = item.get("quote", "").strip()
            if not quote:
                continue
            dedup_key = (page_num, _norm(quote).lower())
            if dedup_key in seen_quotes:
                continue
            seen_quotes.add(dedup_key)

            page = doc[page_num - 1]
            clip_bbox = seg["bbox"] if seg else None
            rects = _search_quote_rects(page, quote, clip_bbox)

            category = item.get("category", "method")
            color = CATEGORY_COLORS.get(category, CATEGORY_COLORS["contribution"])

            if rects:
                # Draw directly into the page content stream behind text (overlay=False)
                # so Android PdfRenderer (ScholarNote) renders highlights reliably without overlap blotches.
                for r in rects:
                    page.draw_rect(
                        r,
                        color=None,
                        fill=color,
                        fill_opacity=0.38,
                        overlay=False,
                    )
                annot = page.add_highlight_annot(rects)
                annot.set_colors(stroke=color)
                annot.set_opacity(0.01)
                annot.set_info(
                    title="ScholarAgent",
                    subject=category,
                    content=item.get("reason", ""),
                )
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
