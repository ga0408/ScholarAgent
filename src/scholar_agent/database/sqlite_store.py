"""SQLite store with WAL mode for ScholarAgent."""

from __future__ import annotations

import json
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class SQLiteStore:
    """Thread-safe SQLite wrapper configured with WAL and busy_timeout."""

    def __init__(self, db_path: Path) -> None:
        self.db_path = db_path
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._init_schema()

    @contextmanager
    def connect(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(str(self.db_path), timeout=10.0)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL;")
        conn.execute("PRAGMA foreign_keys=ON;")
        conn.execute("PRAGMA busy_timeout=5000;")
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    def _init_schema(self) -> None:
        with self.connect() as conn:
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS papers (
                    paper_id TEXT PRIMARY KEY,
                    canonical_id TEXT UNIQUE,
                    content_sha256 TEXT,
                    source TEXT NOT NULL,
                    title TEXT NOT NULL,
                    authors_json TEXT NOT NULL DEFAULT '[]',
                    abstract TEXT NOT NULL DEFAULT '',
                    published_at TEXT DEFAULT '',
                    pdf_url TEXT DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'discovered',
                    work_dir TEXT DEFAULT '',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS jobs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    paper_id TEXT NOT NULL REFERENCES papers(paper_id) ON DELETE CASCADE,
                    stage TEXT NOT NULL,
                    runtime TEXT NOT NULL,
                    model TEXT DEFAULT '',
                    status TEXT NOT NULL,
                    artifact_path TEXT DEFAULT '',
                    error_message TEXT DEFAULT '',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    UNIQUE(paper_id, stage)
                );

                CREATE TABLE IF NOT EXISTS summaries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    paper_id TEXT NOT NULL REFERENCES papers(paper_id) ON DELETE CASCADE,
                    lang TEXT NOT NULL,
                    runtime TEXT NOT NULL,
                    model TEXT DEFAULT '',
                    version INTEGER NOT NULL DEFAULT 1,
                    file_path TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS highlights (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    paper_id TEXT NOT NULL REFERENCES papers(paper_id) ON DELETE CASCADE,
                    block_id TEXT DEFAULT '',
                    page INTEGER NOT NULL,
                    category TEXT NOT NULL,
                    importance INTEGER NOT NULL,
                    quote_text TEXT NOT NULL,
                    reason TEXT DEFAULT '',
                    rects_json TEXT NOT NULL DEFAULT '[]',
                    matched INTEGER NOT NULL DEFAULT 0
                );

                CREATE TABLE IF NOT EXISTS paper_assets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    paper_id TEXT NOT NULL REFERENCES papers(paper_id) ON DELETE CASCADE,
                    asset_type TEXT NOT NULL,
                    local_path TEXT NOT NULL,
                    drive_file_id TEXT DEFAULT '',
                    updated_at TEXT NOT NULL,
                    UNIQUE(paper_id, asset_type)
                );

                CREATE TABLE IF NOT EXISTS triage_cache (
                    query_key TEXT PRIMARY KEY,
                    arxiv_id TEXT DEFAULT '',
                    title TEXT NOT NULL DEFAULT '',
                    abstract TEXT NOT NULL DEFAULT '',
                    triage_json TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS benchmark_cache (
                    name_key TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    info_json TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                """
            )

    def upsert_paper(
        self,
        *,
        paper_id: str,
        canonical_id: str,
        source: str,
        title: str,
        authors: list[str],
        abstract: str,
        published_at: str = "",
        pdf_url: str = "",
        content_sha256: str = "",
        status: str = "discovered",
        work_dir: str = "",
    ) -> None:
        now = _utc_now()
        with self.connect() as conn:
            conn.execute(
                """
                INSERT INTO papers (
                    paper_id, canonical_id, content_sha256, source, title,
                    authors_json, abstract, published_at, pdf_url, status,
                    work_dir, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(paper_id) DO UPDATE SET
                    title = excluded.title,
                    authors_json = CASE
                        WHEN excluded.authors_json != '[]' THEN excluded.authors_json
                        ELSE papers.authors_json
                    END,
                    abstract = COALESCE(NULLIF(excluded.abstract, ''), papers.abstract),
                    published_at = COALESCE(NULLIF(excluded.published_at, ''), papers.published_at),
                    pdf_url = COALESCE(NULLIF(excluded.pdf_url, ''), papers.pdf_url),
                    content_sha256 = COALESCE(NULLIF(excluded.content_sha256, ''), papers.content_sha256),
                    status = excluded.status,
                    work_dir = COALESCE(NULLIF(excluded.work_dir, ''), papers.work_dir),
                    updated_at = excluded.updated_at
                """,
                (
                    paper_id,
                    canonical_id,
                    content_sha256,
                    source,
                    title,
                    json.dumps(authors, ensure_ascii=False),
                    abstract,
                    published_at,
                    pdf_url,
                    status,
                    work_dir,
                    now,
                    now,
                ),
            )

    def update_paper_status(self, paper_id: str, status: str) -> None:
        with self.connect() as conn:
            conn.execute(
                "UPDATE papers SET status = ?, updated_at = ? WHERE paper_id = ?",
                (status, _utc_now(), paper_id),
            )

    def get_paper(self, paper_id: str) -> dict[str, Any] | None:
        with self.connect() as conn:
            row = conn.execute(
                "SELECT * FROM papers WHERE paper_id = ?",
                (paper_id,),
            ).fetchone()
            return dict(row) if row is not None else None

    def upsert_job(
        self,
        *,
        paper_id: str,
        stage: str,
        runtime: str,
        model: str,
        status: str,
        artifact_path: str = "",
        error_message: str = "",
    ) -> None:
        now = _utc_now()
        with self.connect() as conn:
            conn.execute(
                """
                INSERT INTO jobs (paper_id, stage, runtime, model, status, artifact_path, error_message, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(paper_id, stage) DO UPDATE SET
                    runtime = excluded.runtime,
                    model = excluded.model,
                    status = excluded.status,
                    artifact_path = excluded.artifact_path,
                    error_message = excluded.error_message,
                    updated_at = excluded.updated_at
                """,
                (paper_id, stage, runtime, model, status, artifact_path, error_message, now, now),
            )

    def record_summary(
        self, *, paper_id: str, lang: str, runtime: str, model: str, file_path: str
    ) -> None:
        with self.connect() as conn:
            row = conn.execute(
                "SELECT COALESCE(MAX(version), 0) AS v FROM summaries WHERE paper_id = ? AND lang = ?",
                (paper_id, lang),
            ).fetchone()
            next_v = int(row["v"]) + 1 if row else 1
            conn.execute(
                """
                INSERT INTO summaries (paper_id, lang, runtime, model, version, file_path, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (paper_id, lang, runtime, model, next_v, file_path, _utc_now()),
            )

    def replace_highlights(self, paper_id: str, items: list[dict[str, Any]]) -> None:
        with self.connect() as conn:
            conn.execute("DELETE FROM highlights WHERE paper_id = ?", (paper_id,))
            for it in items:
                conn.execute(
                    """
                    INSERT INTO highlights (
                        paper_id, block_id, page, category, importance,
                        quote_text, reason, rects_json, matched
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        paper_id,
                        it.get("block_id", ""),
                        int(it.get("page", 1)),
                        it.get("category", "method"),
                        int(it.get("importance", 3)),
                        it.get("quote", ""),
                        it.get("reason", ""),
                        json.dumps(it.get("rects", [])),
                        1 if it.get("matched") else 0,
                    ),
                )

    def upsert_asset(self, paper_id: str, asset_type: str, local_path: str, drive_file_id: str = "") -> None:
        with self.connect() as conn:
            conn.execute(
                """
                INSERT INTO paper_assets (paper_id, asset_type, local_path, drive_file_id, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(paper_id, asset_type) DO UPDATE SET
                    local_path = excluded.local_path,
                    drive_file_id = COALESCE(NULLIF(excluded.drive_file_id, ''), paper_assets.drive_file_id),
                    updated_at = excluded.updated_at
                """,
                (paper_id, asset_type, local_path, drive_file_id, _utc_now()),
            )

    def get_triage_cache(self, query_key: str) -> dict[str, Any] | None:
        with self.connect() as conn:
            row = conn.execute(
                "SELECT arxiv_id, title, abstract, triage_json, updated_at FROM triage_cache WHERE query_key = ?",
                (query_key,),
            ).fetchone()
            if not row:
                return None
            return {
                "arxiv_id": row["arxiv_id"],
                "title": row["title"],
                "abstract": row["abstract"],
                "triage": json.loads(row["triage_json"]),
                "updated_at": row["updated_at"],
                "cached": True,
            }

    def upsert_triage_cache(
        self,
        *,
        query_key: str,
        arxiv_id: str,
        title: str,
        abstract: str,
        triage: dict[str, Any],
    ) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                INSERT INTO triage_cache (query_key, arxiv_id, title, abstract, triage_json, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(query_key) DO UPDATE SET
                    arxiv_id = excluded.arxiv_id,
                    title = excluded.title,
                    abstract = excluded.abstract,
                    triage_json = excluded.triage_json,
                    updated_at = excluded.updated_at
                """,
                (query_key, arxiv_id, title, abstract, json.dumps(triage, ensure_ascii=False), _utc_now()),
            )

    def get_benchmark_cache(self, name_key: str) -> dict[str, Any] | None:
        with self.connect() as conn:
            row = conn.execute(
                "SELECT name, info_json, updated_at FROM benchmark_cache WHERE name_key = ?",
                (name_key,),
            ).fetchone()
            if not row:
                return None
            return {
                "name": row["name"],
                "info": json.loads(row["info_json"]),
                "updated_at": row["updated_at"],
                "cached": True,
            }

    def upsert_benchmark_cache(self, *, name_key: str, name: str, info: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                INSERT INTO benchmark_cache (name_key, name, info_json, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(name_key) DO UPDATE SET
                    name = excluded.name,
                    info_json = excluded.info_json,
                    updated_at = excluded.updated_at
                """,
                (name_key, name, json.dumps(info, ensure_ascii=False), _utc_now()),
            )
