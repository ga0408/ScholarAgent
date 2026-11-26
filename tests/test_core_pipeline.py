"""Unit tests for ScholarAgent core modules (SQLiteStore, prompt loader, sandbox, title matcher, 2-col parser)."""

from __future__ import annotations

import json
import sys
import tempfile
import types
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

# Provide lightweight stdlib stubs when running outside `.venv` so pure-Python logic tests always run
for _pkg, _attrs in (
    ("pymupdf", {"open": None, "Rect": None, "Page": None}),
    ("httpx", {"get": None, "stream": None}),
):
    if _pkg not in sys.modules:
        try:
            __import__(_pkg)
        except ModuleNotFoundError:
            mod = types.ModuleType(_pkg)
            for k, v in _attrs.items():
                setattr(mod, k, v)
            sys.modules[_pkg] = mod

if "pydantic" not in sys.modules:
    try:
        import pydantic  # noqa: F401
    except ModuleNotFoundError:
        pyd = types.ModuleType("pydantic")

        class _BaseModel:
            def __init__(self, **kwargs: object) -> None:
                for k, v in kwargs.items():
                    setattr(self, k, v)

        def _field(*, default: object = None, default_factory: object = None) -> object:
            return default_factory() if callable(default_factory) else default

        setattr(pyd, "BaseModel", _BaseModel)
        setattr(pyd, "Field", _field)
        sys.modules["pydantic"] = pyd

from scholar_agent.agents.base import isolated_stage_dir, prepare_cli_prompt_delivery  # noqa: E402
from scholar_agent.collector.arxiv import score_title_match  # noqa: E402
from scholar_agent.database.sqlite_store import SQLiteStore  # noqa: E402
from scholar_agent.processors.pdf_parser import (  # noqa: E402
    _sort_blocks_reading_order,
    build_sections_index,
)
from scholar_agent.prompts import load_prompt_template, render_prompt  # noqa: E402


class TestSQLiteStoreAndPrompts(unittest.TestCase):
    def test_sqlite_upsert_updates_status_and_preserves_authors(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            db = SQLiteStore(Path(tmp) / "test.db")
            db.upsert_paper(
                paper_id="arxiv_2410.10450",
                canonical_id="arxiv:2410.10450",
                source="hf_daily",
                title="KBLaM",
                authors=["Xi Wang"],
                abstract="Initial abstract",
                status="discovered",
            )
            row1 = db.get_paper("arxiv_2410.10450")
            self.assertIsNotNone(row1)
            assert row1 is not None
            self.assertEqual(row1["status"], "discovered")

            # Subsequent pipeline upsert must transition status to processing & keep non-empty authors
            db.upsert_paper(
                paper_id="arxiv_2410.10450",
                canonical_id="arxiv:2410.10450",
                source="arxiv",
                title="KBLaM: Knowledge Base augmented Language Model",
                authors=[],
                abstract="Updated abstract",
                published_at="2024-10-14",
                pdf_url="https://arxiv.org/pdf/2410.10450.pdf",
                status="processing",
            )
            row2 = db.get_paper("arxiv_2410.10450")
            assert row2 is not None
            self.assertEqual(row2["status"], "processing")
            self.assertEqual(row2["published_at"], "2024-10-14")
            self.assertEqual(json.loads(row2["authors_json"]), ["Xi Wang"])

    def test_all_prompt_templates_and_schemas_valid_json(self) -> None:
        templates_dir = ROOT / "prompts" / "templates"
        schemas_dir = ROOT / "prompts" / "schemas"

        template_files = list(templates_dir.glob("*.md"))
        self.assertGreaterEqual(len(template_files), 10)
        for tf in template_files:
            raw = load_prompt_template(tf.stem)
            self.assertTrue(len(raw) > 50, f"Template too short: {tf.name}")

        # Verify LaTeX braces inside templates are preserved intact
        rendered = render_prompt(
            "synthesis_ko",
            title="Test Paper",
            canonical_id="arxiv:2410.10450",
            authors_str="Author A",
            published_at="2024-10-14",
            bundle_json='{"ok": true}',
            paper_excerpts="Sample $\\frac{a}{b}$ text",
        )
        self.assertIn("Test Paper", rendered)
        self.assertIn("\\frac{a}{b}", rendered)
        self.assertIn("CRITICAL TERMINOLOGY", rendered)
        self.assertNotIn("{{", rendered)

        schema_files = list(schemas_dir.glob("*.json"))
        self.assertGreaterEqual(len(schema_files), 10)
        for sf in schema_files:
            parsed = json.loads(sf.read_text(encoding="utf-8"))
            self.assertEqual(parsed.get("type"), "object", f"Invalid root type in {sf.name}")


class TestColumnSortingAndSectionInference(unittest.TestCase):
    def test_two_column_band_sorting_and_custom_method_heading(self) -> None:
        # Page width 600, height 800: Full-width title, then 2-column body (L1, R1, L2, R2)
        blocks = [
            (70.0, 50.0, 530.0, 80.0, "Paper Title Across Columns", 0, 0),
            (70.0, 120.0, 285.0, 180.0, "Left col paragraph 1", 1, 0),
            (315.0, 118.0, 530.0, 175.0, "Right col paragraph 1 (slightly higher y0!)", 2, 0),
            (70.0, 200.0, 285.0, 260.0, "Left col paragraph 2", 3, 0),
            (315.0, 195.0, 530.0, 255.0, "Right col paragraph 2", 4, 0),
        ]
        sorted_blocks = _sort_blocks_reading_order(blocks, page_width=600.0, page_height=800.0)
        texts = [b[4] for b in sorted_blocks]
        self.assertEqual(
            texts,
            [
                "Paper Title Across Columns",
                "Left col paragraph 1",
                "Left col paragraph 2",
                "Right col paragraph 1 (slightly higher y0!)",
                "Right col paragraph 2",
            ],
        )

        # Custom Method headings ("3 Knowledge Base Augmented Language Model", "4 Rectangular Attention")
        segments = [
            {"block_id": "p1_b0", "page": 1, "bbox": [0, 0, 100, 20], "text": "Abstract"},
            {"block_id": "p1_b1", "page": 1, "bbox": [0, 25, 500, 120], "text": "This paper proposes KBLaM " * 8},
            {"block_id": "p1_b2", "page": 1, "bbox": [0, 130, 200, 150], "text": "1 Introduction"},
            {"block_id": "p1_b3", "page": 1, "bbox": [0, 160, 500, 240], "text": "Intro text body here."},
            {"block_id": "p2_b0", "page": 2, "bbox": [0, 50, 200, 70], "text": "2 Related Work"},
            {"block_id": "p2_b1", "page": 2, "bbox": [0, 80, 500, 160], "text": "Related work discussion."},
            {"block_id": "p3_b0", "page": 3, "bbox": [0, 50, 420, 70], "text": "3 Knowledge Base Augmented Language Model"},
            {"block_id": "p3_b1", "page": 3, "bbox": [0, 80, 500, 200], "text": "Core architecture and knowledge tokens."},
            {"block_id": "p5_b0", "page": 5, "bbox": [0, 50, 320, 70], "text": "4 Rectangular Attention"},
            {"block_id": "p5_b1", "page": 5, "bbox": [0, 80, 500, 200], "text": "Attention matrix formulation in Figure 2."},
            {"block_id": "p8_b0", "page": 8, "bbox": [0, 50, 220, 70], "text": "6 Experiments"},
            {"block_id": "p8_b1", "page": 8, "bbox": [0, 80, 500, 200], "text": "Benchmark results in Table 1."},
            {"block_id": "p10_b0", "page": 10, "bbox": [0, 50, 220, 70], "text": "References"},
            {"block_id": "p10_b1", "page": 10, "bbox": [0, 80, 500, 200], "text": "[1] Prior citation."},
            {"block_id": "p14_b0", "page": 14, "bbox": [0, 50, 320, 70], "text": "A.1 Ablation on Sentence Encoders"},
            {"block_id": "p14_b1", "page": 14, "bbox": [0, 80, 500, 200], "text": "Encoder dimension comparison in Figure 7."},
        ]
        idx = build_sections_index(segments, page_count=15)
        secs = idx["sections"]
        self.assertIn("method", secs)
        self.assertEqual(secs["method"]["start_page"], 3)
        self.assertEqual(secs["method"]["end_page"], 5)
        self.assertIn("3 Knowledge Base Augmented Language Model", secs["method"]["headings"])
        self.assertIn("4 Rectangular Attention", secs["method"]["headings"])
        self.assertIn("appendix", secs)
        self.assertIn(5, idx["visual_pages"])
        self.assertIn(8, idx["visual_pages"])


class TestTitleSimilarityAndSandboxDelivery(unittest.TestCase):
    def test_title_match_guard(self) -> None:
        self.assertGreaterEqual(
            score_title_match("KBLaM", "KBLaM: Knowledge Base augmented Language Model"),
            0.9,
        )
        self.assertEqual(
            score_title_match("KBLaM", "Quantum Spin Dynamics in Magnetic Lattices"),
            0.0,
        )
        self.assertGreaterEqual(
            score_title_match(
                "Parallel Context Windows for Large Language Models",
                "Parallel Context Windows for Large Language Models",
            ),
            0.95,
        )

    def test_oversized_prompt_delivery_splits_argv(self) -> None:
        huge_prompt = "Header instruction line\n" + ("논문 본문 테스트 텍스트 Self-Attention " * 2000)
        with isolated_stage_dir() as (stage_dir, staged_imgs):
            self.assertEqual(staged_imgs, [])
            argv_prompt, stdin_payload = prepare_cli_prompt_delivery(huge_prompt, stage_dir)
            self.assertLess(len(argv_prompt.encode("utf-8")), 5_000)
            self.assertEqual(stdin_payload, huge_prompt)
            self.assertTrue((stage_dir / "prompt_input.md").exists())


if __name__ == "__main__":
    unittest.main()
