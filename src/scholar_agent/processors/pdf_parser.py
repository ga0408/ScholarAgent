"""PyMuPDF PDF preprocessor: extracts text, segments with bboxes, and page PNGs."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

import pymupdf as fitz  # PyMuPDF


_SECTION_HEADING_RE = re.compile(
    r"^(?:(?:\d+(?:\.\d+)*|[IVX]+)\.?\s+)?"
    r"(abstract|introduction|background(?:\s+and\s+related\s+works?)?|related\s+works?|"
    r"preliminaries|method(?:s|ology)?|proposed\s+(?:method|approach|framework|system)|"
    r"approach|architecture|framework|experiments?(?:\s+and\s+results?)?|"
    r"experimental\s+setup|evaluation|results?(?:\s+and\s+discussion)?|"
    r"discussion|conclusions?(?:\s+and\s+future\s+work)?|limitations?|"
    r"references|bibliography|appendix)\s*:?$",
    re.IGNORECASE,
)


def _canonical_section(heading_text: str) -> str | None:
    m = _SECTION_HEADING_RE.match(heading_text.strip())
    if not m:
        return None
    label = m.group(1).lower()
    if label == "abstract":
        return "abstract"
    if label == "introduction":
        return "introduction"
    if any(k in label for k in ("background", "related", "preliminaries")):
        return "related_work"
    if any(k in label for k in ("method", "approach", "architecture", "framework")):
        return "method"
    if any(k in label for k in ("experiment", "evaluation", "result")):
        return "experiment"
    if any(k in label for k in ("conclusion", "limitation", "discussion")):
        return "conclusion"
    return "references"


def build_sections_index(segments: list[dict[str, Any]], page_count: int) -> dict[str, Any]:
    """Group extracted segments into canonical paper sections based on detected headings."""
    toc: list[dict[str, Any]] = []
    heading_positions: list[tuple[int, str, str, int, str]] = []

    for idx, seg in enumerate(segments):
        text = seg["text"].strip()
        if len(text) <= 80:
            canon = _canonical_section(text)
            if canon is not None:
                toc.append(
                    {
                        "title": text,
                        "canonical": canon,
                        "page": seg["page"],
                        "block_id": seg["block_id"],
                    }
                )
                heading_positions.append((idx, canon, text, seg["page"], seg["block_id"]))

    sections: dict[str, dict[str, Any]] = {}
    for pos_i, (seg_idx, canon, _title, start_page, _bid) in enumerate(heading_positions):
        if canon == "references":
            continue
        next_idx = heading_positions[pos_i + 1][0] if pos_i + 1 < len(heading_positions) else len(segments)
        slice_segs = segments[seg_idx:next_idx]
        if not slice_segs:
            continue
        end_page = slice_segs[-1]["page"]
        block_ids = [s["block_id"] for s in slice_segs]
        joined = "\n".join(f"[{s['block_id']}] {s['text']}" for s in slice_segs)

        if canon not in sections:
            sections[canon] = {
                "start_page": start_page,
                "end_page": end_page,
                "block_ids": block_ids,
                "text": joined,
            }
        else:
            sections[canon]["end_page"] = max(sections[canon]["end_page"], end_page)
            sections[canon]["block_ids"].extend(block_ids)
            sections[canon]["text"] += "\n" + joined

    # Fallback for unheaded Abstract on page 1: pick longest page-1 block before Introduction
    if "abstract" not in sections:
        intro_start = sections.get("introduction", {}).get("start_page", page_count + 1)
        p1_candidates = [
            s for s in segments if s["page"] == 1 and s["page"] <= intro_start and len(s["text"]) >= 120
        ]
        if p1_candidates:
            best = max(p1_candidates, key=lambda s: len(s["text"]))
            sections["abstract"] = {
                "start_page": 1,
                "end_page": 1,
                "block_ids": [best["block_id"]],
                "text": f"[{best['block_id']}] {best['text']}",
            }

    return {"toc": toc, "sections": sections}


def _clean_text(text: str) -> str:
    text = text.replace("\ufb01", "fi").replace("\ufb02", "fl")
    text = re.sub(r"-\s*\n\s*", "", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def preprocess_pdf(
    pdf_path: Path,
    out_dir: Path,
    *,
    render_dpi: int = 140,
) -> dict[str, Any]:
    """Extract extracted.md, segments.json, and pages/page_NNN.png from a PDF."""
    out_dir.mkdir(parents=True, exist_ok=True)
    pages_dir = out_dir / "pages"
    pages_dir.mkdir(parents=True, exist_ok=True)

    doc = fitz.open(str(pdf_path))
    segments: list[dict[str, Any]] = []
    md_parts: list[str] = []
    page_images: list[str] = []

    try:
        for page_idx in range(len(doc)):
            page_num = page_idx + 1
            page = doc[page_idx]

            # Render page PNG for multimodal equation/figure subagents
            img_path = pages_dir / f"page_{page_num:03d}.png"
            if not img_path.exists():
                pix = page.get_pixmap(dpi=render_dpi)
                pix.save(str(img_path))
            page_images.append(str(img_path))

            md_parts.append(f"\n<!-- PAGE {page_num} -->\n")
            blocks = page.get_text("blocks")
            # Sort top-to-bottom, left-to-right
            blocks_sorted = sorted(blocks, key=lambda b: (round(b[1], 1), round(b[0], 1)))

            for b_idx, block in enumerate(blocks_sorted):
                x0, y0, x1, y1, raw_text, _, block_type = block
                if block_type != 0:
                    continue
                cleaned = _clean_text(raw_text)
                is_heading = len(cleaned) <= 80 and _canonical_section(cleaned) is not None
                if len(cleaned) < 20 and not is_heading:
                    continue
                block_id = f"p{page_num}_b{b_idx}"
                segments.append(
                    {
                        "block_id": block_id,
                        "page": page_num,
                        "bbox": [round(x0, 2), round(y0, 2), round(x1, 2), round(y1, 2)],
                        "text": cleaned,
                    }
                )
                md_parts.append(f"[{block_id}] {cleaned}\n")
    finally:
        page_count = len(doc)
        doc.close()

    extracted_md_path = out_dir / "extracted.md"
    extracted_md_path.write_text("\n".join(md_parts), encoding="utf-8")

    segments_path = out_dir / "segments.json"
    segments_path.write_text(json.dumps(segments, ensure_ascii=False, indent=2), encoding="utf-8")

    sections_index = build_sections_index(segments, page_count)
    sections_path = out_dir / "sections.json"
    sections_path.write_text(json.dumps(sections_index, ensure_ascii=False, indent=2), encoding="utf-8")

    return {
        "page_count": page_count,
        "segment_count": len(segments),
        "extracted_md": str(extracted_md_path),
        "segments_json": str(segments_path),
        "sections_json": str(sections_path),
        "page_images": page_images,
    }
