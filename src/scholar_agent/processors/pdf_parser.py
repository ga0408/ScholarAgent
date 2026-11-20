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
    r"references|bibliography|appendix|acknowledgm?ents?)\s*:?$",
    re.IGNORECASE,
)

# Inline heading merged with paragraph text by PDF layout (e.g. "3 BACKGROUND Knowledge base...")
_INLINE_HEADING_RE = re.compile(
    r"^([1-9])\s+(INTRODUCTION|RELATED\s+WORKS?|BACKGROUND|PRELIMINARIES|"
    r"METHOD(?:S|OLOGY)?|EXPERIMENTS?|EVALUATION|RESULTS?|DISCUSSION|CONCLUSIONS?|LIMITATIONS?)\b"
)

# Standalone numbered top-level section with custom domain title (e.g. "4 AUGMENTING LLM WITH THE KB")
_CUSTOM_NUMBERED_HEADING_RE = re.compile(
    r"^([1-9])\s+([A-Z][A-Za-z0-9\-\s:()/,&]{3,68})$"
)


def _canonical_from_keyword(label: str) -> str:
    low = label.lower()
    if low == "abstract":
        return "abstract"
    if low == "introduction":
        return "introduction"
    if any(k in low for k in ("background", "related", "preliminar")):
        return "related_work"
    if any(k in low for k in ("method", "approach", "architecture", "framework")):
        return "method"
    if any(k in low for k in ("experiment", "evaluation", "result", "ablation")):
        return "experiment"
    if any(k in low for k in ("conclusion", "limitation", "discussion")):
        return "conclusion"
    return "references"


def _canonical_section(heading_text: str) -> str | None:
    stripped = heading_text.strip()
    m = _SECTION_HEADING_RE.match(stripped)
    if m:
        return _canonical_from_keyword(m.group(1))
    m_inline = _INLINE_HEADING_RE.match(stripped)
    if m_inline:
        return _canonical_from_keyword(m_inline.group(2))
    return None


def _detect_heading(
    text: str,
    *,
    seen_intro: bool,
    seen_experiment: bool,
    seen_conclusion: bool,
) -> tuple[str, str] | None:
    """Return (canonical_section, display_title) if block starts a top-level section."""
    stripped = text.strip()
    if len(stripped) <= 80:
        m = _SECTION_HEADING_RE.match(stripped)
        if m:
            return _canonical_from_keyword(m.group(1)), stripped

        m_custom = _CUSTOM_NUMBERED_HEADING_RE.match(stripped)
        if m_custom and not stripped.endswith((".", ";", ",")):
            sec_num = int(m_custom.group(1))
            title_body = m_custom.group(2).strip()
            # Accept ALL-CAPS numbered headings or clean Title Case top-level headings
            words = title_body.split()
            is_all_caps = title_body.isupper()
            is_title_like = len(words) <= 8 and sum(1 for w in words if w[0].isupper()) >= max(1, len(words) - 2)
            if (is_all_caps or is_title_like) and 1 <= sec_num <= 9:
                low = title_body.lower()
                if any(k in low for k in ("background", "related", "preliminar", "prior")):
                    return "related_work", stripped
                if any(k in low for k in ("experiment", "evaluation", "result", "benchmark", "ablation")):
                    return "experiment", stripped
                if any(k in low for k in ("conclusion", "limitation", "discussion", "future")):
                    return "conclusion", stripped
                if any(k in low for k in ("reference", "bibliograph", "appendix", "acknowledg")):
                    return "references", stripped
                if seen_conclusion:
                    return "references", stripped
                if seen_experiment:
                    return "experiment", stripped
                if seen_intro or sec_num >= 2:
                    return "method", stripped

    m_inline = _INLINE_HEADING_RE.match(stripped)
    if m_inline:
        title = f"{m_inline.group(1)} {m_inline.group(2)}"
        return _canonical_from_keyword(m_inline.group(2)), title

    return None


def build_sections_index(segments: list[dict[str, Any]], page_count: int) -> dict[str, Any]:
    """Group extracted segments into canonical paper sections based on detected headings."""
    toc: list[dict[str, Any]] = []
    heading_positions: list[tuple[int, str, str, int, str]] = []
    seen_intro = False
    seen_experiment = False
    seen_conclusion = False

    for idx, seg in enumerate(segments):
        text = seg["text"].strip()
        detected = _detect_heading(
            text,
            seen_intro=seen_intro,
            seen_experiment=seen_experiment,
            seen_conclusion=seen_conclusion,
        )
        if detected is not None:
            canon, title = detected
            if canon == "introduction":
                seen_intro = True
            elif canon == "experiment":
                seen_experiment = True
            elif canon == "conclusion":
                seen_conclusion = True
            toc.append(
                {
                    "title": title,
                    "canonical": canon,
                    "page": seg["page"],
                    "block_id": seg["block_id"],
                }
            )
            heading_positions.append((idx, canon, title, seg["page"], seg["block_id"]))

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
                is_heading = len(cleaned) <= 80 and (
                    _canonical_section(cleaned) is not None
                    or _detect_heading(
                        cleaned,
                        seen_intro=True,
                        seen_experiment=False,
                        seen_conclusion=False,
                    )
                    is not None
                )
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
