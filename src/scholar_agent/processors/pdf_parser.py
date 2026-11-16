"""PyMuPDF PDF preprocessor: extracts text, segments with bboxes, and page PNGs."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

import pymupdf as fitz  # PyMuPDF


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
                if len(cleaned) < 20:
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

    return {
        "page_count": page_count,
        "segment_count": len(segments),
        "extracted_md": str(extracted_md_path),
        "segments_json": str(segments_path),
        "page_images": page_images,
    }
