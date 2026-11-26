"""PyMuPDF PDF preprocessor: column-aware block sorting, rich section indexing, and page PNGs."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

import pymupdf as fitz  # PyMuPDF

_EXACT_SECTION_RE = re.compile(
    r"^(?:(?:\d+(?:\.\d+)*|[IVX]+)\.?\s+)?"
    r"(abstract|introduction|overview|background(?:\s+and\s+related\s+works?)?|"
    r"related\s+works?|prior\s+works?|preliminaries|"
    r"method(?:s|ology)?|proposed\s+(?:method|approach|framework|system|architecture)|"
    r"approach|architecture|framework|formulation|model(?:\s+architecture)?|"
    r"experiments?(?:\s+and\s+results?)?|experimental\s+(?:setup|results?)|"
    r"evaluation|empirical\s+(?:evaluation|results?|study)|results?(?:\s+and\s+(?:discussion|analysis))?|"
    r"ablation(?:\s+stud(?:y|ies))?|analysis|"
    r"discussion|conclusions?(?:\s+and\s+(?:future\s+work|limitations?))?|"
    r"limitations?(?:\s+and\s+future\s+work)?|broader\s+impacts?|"
    r"references|bibliography|acknowledg(?:e)?ments?|appendix|appendices|supplementary(?:\s+material)?)\s*:?$",
    re.IGNORECASE,
)

# Top-level numbered section heading (e.g., "3 Knowledge Base Augmented Language Model", "4. Rectangular Attention")
_NUMBERED_TOP_HEADING_RE = re.compile(
    r"^(?:section\s+)?([1-9]\d?|[IVX]{1,4})\.?\s+([A-Z][A-Za-z0-9\s\-:,/()&'+]{2,85})$"
)

# Sub-section numbered heading (e.g., "3.1 Rectangular Attention", "A.2 Ablation on Encoder") — excludes plot ticks like 0.7 / 1.00
_NUMBERED_SUB_HEADING_RE = re.compile(
    r"^([1-9]\d?\.[1-9]\d?|[A-F](?:\.[1-9]\d?)?)\.?\s+([A-Z][A-Za-z0-9\s\-:,/()&'+]{2,85})$"
)

_NON_HEADING_PREFIX_RE = re.compile(
    r"^(?:table|figure|fig\.|algorithm|alg\.|theorem|lemma|proposition|corollary|eq\.|equation)\b",
    re.IGNORECASE,
)

_FIG_TABLE_MENTION_RE = re.compile(r"\b(?:Figure|Fig\.|Table|Algorithm)\s+\d+", re.IGNORECASE)


def _keyword_canonical(text: str) -> str | None:
    low = text.lower().strip().rstrip(":")
    m = _EXACT_SECTION_RE.match(text.strip())
    target = m.group(1).lower() if m else low

    if target == "abstract" or low.startswith("abstract"):
        return "abstract"
    if any(k in target for k in ("introduction", "overview")):
        return "introduction"
    if any(k in target for k in ("background", "related work", "prior work", "preliminar")):
        return "related_work"
    if any(
        k in target
        for k in ("experiment", "evaluation", "empirical", "benchmark", "ablation")
    ) or target.startswith("result"):
        return "experiment"
    if any(
        k in target
        for k in ("conclusion", "limitation", "future work", "broader impact")
    ) or target == "discussion":
        return "conclusion"
    if any(k in target for k in ("reference", "bibliograph", "acknowledg")):
        return "references"
    if any(k in target for k in ("appendix", "appendices", "supplementary")):
        return "appendix"
    if any(
        k in target
        for k in ("method", "approach", "architecture", "framework", "formulation", "model")
    ):
        return "method"
    return None


def _detect_heading(text: str) -> tuple[bool, bool, str | None]:
    """Return `(is_heading, is_top_level, keyword_canonical_or_none)`."""
    stripped = text.strip()
    if not stripped or len(stripped) > 95 or stripped.endswith("."):
        return False, False, None
    if _NON_HEADING_PREFIX_RE.match(stripped):
        return False, False, None

    exact_m = _EXACT_SECTION_RE.match(stripped)
    if exact_m:
        has_sub_dot = bool(re.match(r"^\d+\.\d+", stripped))
        return True, not has_sub_dot, _keyword_canonical(stripped)

    top_m = _NUMBERED_TOP_HEADING_RE.match(stripped)
    if top_m:
        sec_num_str = top_m.group(1)
        if sec_num_str.isdigit() and int(sec_num_str) > 18:
            return False, False, None
        title_body = top_m.group(2)
        return True, True, _keyword_canonical(title_body)

    sub_m = _NUMBERED_SUB_HEADING_RE.match(stripped)
    if sub_m:
        prefix, title_body = sub_m.group(1), sub_m.group(2)
        if re.match(r"^[A-F](?:\.\d+)?$", prefix):
            return True, prefix.isalpha(), "appendix"
        return True, False, _keyword_canonical(title_body)

    return False, False, None


def _sort_blocks_reading_order(
    blocks: list[tuple[Any, ...]],
    page_width: float,
    page_height: float,
) -> list[tuple[Any, ...]]:
    """Sort PDF text blocks in human reading order, preserving 2-column band structure."""
    text_blocks = [b for b in blocks if len(b) >= 7 and b[6] == 0]
    if not text_blocks:
        return []

    mid_x = page_width / 2.0
    col_max_width = page_width * 0.54
    gutter_tol = page_width * 0.035

    def classify_col(b: tuple[Any, ...]) -> str:
        x0, _y0, x1, _y1 = float(b[0]), float(b[1]), float(b[2]), float(b[3])
        w = x1 - x0
        if w <= col_max_width and x1 <= mid_x + gutter_tol:
            return "L"
        if w <= col_max_width and x0 >= mid_x - gutter_tol:
            return "R"
        return "F"

    # Initial vertical sort to detect spanning (full-width) band boundaries
    by_y = sorted(text_blocks, key=lambda b: (round(float(b[1]), 1), round(float(b[0]), 1)))
    ordered: list[tuple[Any, ...]] = []
    left_band: list[tuple[Any, ...]] = []
    right_band: list[tuple[Any, ...]] = []

    def flush_band() -> None:
        if left_band:
            left_band.sort(key=lambda b: (round(float(b[1]), 1), round(float(b[0]), 1)))
            ordered.extend(left_band)
            left_band.clear()
        if right_band:
            right_band.sort(key=lambda b: (round(float(b[1]), 1), round(float(b[0]), 1)))
            ordered.extend(right_band)
            right_band.clear()

    for b in by_y:
        y0, y1 = float(b[1]), float(b[3])
        raw = str(b[4]).strip()
        # Skip isolated page numbers / tiny running footers at extreme margins
        if (y0 < page_height * 0.035 or y1 > page_height * 0.965) and len(raw) < 35 and raw.isdigit():
            continue

        col = classify_col(b)
        if col == "F":
            flush_band()
            ordered.append(b)
        elif col == "L":
            left_band.append(b)
        else:
            right_band.append(b)

    flush_band()
    return ordered


def build_sections_index(segments: list[dict[str, Any]], page_count: int) -> dict[str, Any]:
    """Group extracted segments into canonical paper sections, inferring custom Method headings."""
    raw_headings: list[dict[str, Any]] = []
    for idx, seg in enumerate(segments):
        is_h, is_top, kw_canon = _detect_heading(seg["text"])
        if is_h:
            raw_headings.append(
                {
                    "seg_idx": idx,
                    "title": seg["text"].strip(),
                    "is_top": is_top,
                    "canonical": kw_canon,
                    "page": seg["page"],
                    "block_id": seg["block_id"],
                }
            )

    # Locate structural anchors to infer creative Method section titles
    seen_intro_or_rel = False
    seen_exp = False
    seen_refs = False

    for h in raw_headings:
        c = h["canonical"]
        if c in ("introduction", "related_work"):
            seen_intro_or_rel = True
        elif c == "experiment":
            seen_exp = True
        elif c == "references":
            seen_refs = True
        elif c is None:
            if seen_refs:
                h["canonical"] = "appendix"
            elif seen_exp:
                # Sub-analyses after Experiments (e.g. "6.4 Qualitative Case Study") stay in experiment
                h["canonical"] = "experiment"
            elif seen_intro_or_rel:
                # Custom section titles between Intro/Related Work and Experiments are the paper's Method!
                h["canonical"] = "method"
            else:
                h["canonical"] = "introduction"

    toc: list[dict[str, Any]] = [
        {
            "title": h["title"],
            "canonical": h["canonical"],
            "is_top_level": h["is_top"],
            "page": h["page"],
            "block_id": h["block_id"],
        }
        for h in raw_headings
    ]

    sections: dict[str, dict[str, Any]] = {}
    for pos_i, h in enumerate(raw_headings):
        canon = h["canonical"]
        if canon == "references":
            continue
        seg_idx = h["seg_idx"]
        next_idx = raw_headings[pos_i + 1]["seg_idx"] if pos_i + 1 < len(raw_headings) else len(segments)
        slice_segs = segments[seg_idx:next_idx]
        if not slice_segs:
            continue
        start_page = h["page"]
        end_page = slice_segs[-1]["page"]
        block_ids = [s["block_id"] for s in slice_segs]
        joined = "\n".join(f"[{s['block_id']}] {s['text']}" for s in slice_segs)

        if canon not in sections:
            sections[canon] = {
                "start_page": start_page,
                "end_page": end_page,
                "headings": [h["title"]],
                "block_ids": block_ids,
                "text": joined,
            }
        else:
            sections[canon]["end_page"] = max(sections[canon]["end_page"], end_page)
            if h["title"] not in sections[canon]["headings"]:
                sections[canon]["headings"].append(h["title"])
            sections[canon]["block_ids"].extend(block_ids)
            sections[canon]["text"] += "\n" + joined

    # Fallback for unheaded Abstract on page 1: pick page-1 blocks before Introduction
    if "abstract" not in sections:
        intro_idx = next(
            (h["seg_idx"] for h in raw_headings if h["canonical"] == "introduction"),
            len(segments),
        )
        p1_candidates = [
            s
            for idx, s in enumerate(segments)
            if s["page"] == 1 and idx < intro_idx and len(s["text"]) >= 120
        ]
        if p1_candidates:
            best = max(p1_candidates, key=lambda s: len(s["text"]))
            sections["abstract"] = {
                "start_page": 1,
                "end_page": 1,
                "headings": ["Abstract"],
                "block_ids": [best["block_id"]],
                "text": f"[{best['block_id']}] {best['text']}",
            }

    # Track pages containing explicit Figure/Table captions or dense equations for multimodal selection
    visual_pages: list[int] = sorted(
        {
            int(s["page"])
            for s in segments
            if _FIG_TABLE_MENTION_RE.search(s["text"])
        }
    )

    return {
        "toc": toc,
        "sections": sections,
        "visual_pages": visual_pages,
    }


def _clean_text(text: str) -> str:
    text = (
        text.replace("\ufb00", "ff")
        .replace("\ufb01", "fi")
        .replace("\ufb02", "fl")
        .replace("\ufb03", "ffi")
        .replace("\ufb04", "ffl")
    )
    text = re.sub(r"-\s*\n\s*", "", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def preprocess_pdf(
    pdf_path: Path,
    out_dir: Path,
    *,
    render_dpi: int = 180,
) -> dict[str, Any]:
    """Extract extracted.md, segments.json, sections.json, and pages/page_NNN.png from a PDF."""
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

            # Render page PNG at 180 DPI for accurate equation subscript & benchmark table reading
            img_path = pages_dir / f"page_{page_num:03d}.png"
            if not img_path.exists():
                pix = page.get_pixmap(dpi=render_dpi)
                pix.save(str(img_path))
            page_images.append(str(img_path))

            md_parts.append(f"\n<!-- PAGE {page_num} -->\n")
            blocks = page.get_text("blocks")
            blocks_sorted = _sort_blocks_reading_order(
                blocks,
                page_width=float(page.rect.width),
                page_height=float(page.rect.height),
            )

            for b_idx, block in enumerate(blocks_sorted):
                x0, y0, x1, y1, raw_text, _, _block_type = block
                cleaned = _clean_text(raw_text)
                is_heading, _, _ = _detect_heading(cleaned)
                if len(cleaned) < 20 and not is_heading:
                    continue
                block_id = f"p{page_num}_b{b_idx}"
                segments.append(
                    {
                        "block_id": block_id,
                        "page": page_num,
                        "bbox": [round(float(x0), 2), round(float(y0), 2), round(float(x1), 2), round(float(y1), 2)],
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
