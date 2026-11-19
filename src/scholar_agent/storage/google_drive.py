"""Google Drive OAuth & folder-hierarchy storage sync for ScholarAgent."""

from __future__ import annotations

import json
import mimetypes
import os
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

from scholar_agent.config import get_project_root
from scholar_agent.database.sqlite_store import SQLiteStore

SCOPES = ["https://www.googleapis.com/auth/drive.file"]
FOLDER_MIME = "application/vnd.google-apps.folder"
TOKEN_URI = "https://oauth2.googleapis.com/token"


def _load_dotenv(env_path: Path) -> None:
    if not env_path.exists():
        return
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


def _safe_folder_name(title: str, fallback: str) -> str:
    cleaned = re.sub(r'[\\/:*?"<>|]+', " ", title or "").strip()
    cleaned = re.sub(r"\s+", " ", cleaned)
    if not cleaned:
        return fallback
    return cleaned[:90].rstrip(" .")


class GoogleDriveStorage:
    """Manages OAuth2 credentials and idempotent uploads to Google Drive."""

    def __init__(
        self,
        *,
        credentials_path: Path | None = None,
        token_path: Path | None = None,
        root_folder_name: str = "ScholarAgent",
    ) -> None:
        root = get_project_root()
        self.credentials_path = credentials_path or (root / "credentials.json")
        self.token_path = token_path or (root / "token.json")
        self.root_folder_name = root_folder_name
        self._service: Any = None

    def authenticate(self, *, interactive: bool = True) -> Credentials:
        _load_dotenv(get_project_root() / ".env")
        creds: Credentials | None = None

        # 1. Reuse existing token.json if available
        if self.token_path.exists():
            creds = Credentials.from_authorized_user_file(str(self.token_path), SCOPES)

        # 2. Or build credentials from .env (HomeAgent-compatible client_id/secret/refresh_token)
        if creds is None:
            client_id = os.getenv("GOOGLE_DRIVE_CLIENT_ID", "").strip()
            client_secret = os.getenv("GOOGLE_DRIVE_CLIENT_SECRET", "").strip()
            refresh_token = os.getenv("GOOGLE_DRIVE_REFRESH_TOKEN", "").strip()
            if client_id and client_secret and refresh_token:
                creds = Credentials(
                    token=None,
                    refresh_token=refresh_token,
                    token_uri=TOKEN_URI,
                    client_id=client_id,
                    client_secret=client_secret,
                )

        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
            if self.token_path.exists():
                self.token_path.write_text(creds.to_json(), encoding="utf-8")
            return creds

        if creds and (creds.valid or (creds.refresh_token and not creds.token)):
            if not creds.token and creds.refresh_token:
                creds.refresh(Request())
            return creds

        if not interactive:
            raise RuntimeError(
                f"Google Drive token missing or invalid at {self.token_path}. "
                "Run `scholar-agent auth-drive` first."
            )

        if not self.credentials_path.exists():
            raise FileNotFoundError(
                f"OAuth credentials not found at {self.credentials_path}. "
                "Download Desktop OAuth client JSON from Google Cloud Console and save it there."
            )

        flow = InstalledAppFlow.from_client_secrets_file(str(self.credentials_path), SCOPES)
        creds = flow.run_local_server(port=0)
        self.token_path.write_text(creds.to_json(), encoding="utf-8")
        return creds

    @property
    def service(self) -> Any:
        if self._service is None:
            creds = self.authenticate(interactive=False)
            self._service = build("drive", "v3", credentials=creds, cache_discovery=False)
        return self._service

    def _find_child(self, parent_id: str, name: str, *, mime_type: str | None = None) -> str | None:
        safe_name = name.replace("'", "\\'")
        q = f"'{parent_id}' in parents and name = '{safe_name}' and trashed = false"
        if mime_type:
            q += f" and mimeType = '{mime_type}'"
        res = (
            self.service.files()
            .list(q=q, spaces="drive", fields="files(id, name)", pageSize=5)
            .execute()
        )
        files = res.get("files", [])
        return files[0]["id"] if files else None

    def ensure_folder(self, parent_id: str, name: str) -> str:
        existing = self._find_child(parent_id, name, mime_type=FOLDER_MIME)
        if existing:
            return existing
        meta = {"name": name, "mimeType": FOLDER_MIME, "parents": [parent_id]}
        created = self.service.files().create(body=meta, fields="id").execute()
        return created["id"]

    def ensure_folder_path(self, parts: list[str]) -> str:
        parent = "root"
        for part in parts:
            parent = self.ensure_folder(parent, part)
        return parent

    def upsert_file(
        self,
        folder_id: str,
        local_path: Path,
        *,
        remote_name: str | None = None,
        mime_type: str | None = None,
    ) -> str:
        name = remote_name or local_path.name
        guessed_mime, _ = mimetypes.guess_type(str(local_path))
        mt = mime_type or guessed_mime or "application/octet-stream"
        media = MediaFileUpload(str(local_path), mimetype=mt, resumable=True)

        existing_id = self._find_child(folder_id, name)
        if existing_id:
            updated = (
                self.service.files()
                .update(fileId=existing_id, media_body=media, fields="id")
                .execute()
            )
            return updated["id"]

        meta = {"name": name, "parents": [folder_id]}
        created = (
            self.service.files()
            .create(body=meta, media_body=media, fields="id")
            .execute()
        )
        return created["id"]

    def sync_paper(
        self,
        paper_dir: Path,
        *,
        paper_id: str,
        title: str = "",
        mode: str = "paper",
        date_str: str = "",
        store: SQLiteStore | None = None,
    ) -> dict[str, str]:
        """Upload curated paper artifacts to Drive.

        - mode='paper': ScholarAgent/papers/{Paper Title}/
        - mode='daily': ScholarAgent/daily/YYYY-MM-DD/{Paper Title}/
        """
        folder_title = _safe_folder_name(title, paper_id)
        if mode == "daily":
            day = date_str[:10] if len(date_str) >= 10 else datetime.now(timezone.utc).strftime("%Y-%m-%d")
            folder_parts = [self.root_folder_name, "daily", day, folder_title]
        else:
            folder_parts = [self.root_folder_name, "papers", folder_title]

        paper_folder_id = self.ensure_folder_path(folder_parts)

        upload_plan: list[tuple[str, str, Path, str]] = [
            ("highlighted_pdf", paper_folder_id, paper_dir / "highlights" / "highlighted.pdf", "application/pdf"),
            ("summary_ko", paper_folder_id, paper_dir / "summary" / "summary.ko.md", "text/markdown"),
            ("summary_en", paper_folder_id, paper_dir / "summary" / "summary.en.md", "text/markdown"),
            ("highlights_json", paper_folder_id, paper_dir / "highlights" / "highlights.json", "application/json"),
            ("original_pdf", paper_folder_id, paper_dir / "original.pdf", "application/pdf"),
            ("extracted_md", paper_folder_id, paper_dir / "extracted.md", "text/markdown"),
            ("metadata_json", paper_folder_id, paper_dir / "metadata.json", "application/json"),
            ("presentation_mp4", paper_folder_id, paper_dir / "presentation" / "presentation.mp4", "video/mp4"),
            ("subtitles_srt", paper_folder_id, paper_dir / "presentation" / "subtitles.srt", "application/x-subrip"),
            ("presentation_json", paper_folder_id, paper_dir / "presentation" / "presentation.json", "application/json"),
        ]

        uploaded_ids: dict[str, str] = {}
        for asset_type, folder_id, local_path, mime in upload_plan:
            if not local_path.exists():
                continue
            file_id = self.upsert_file(folder_id, local_path, mime_type=mime)
            uploaded_ids[asset_type] = file_id
            if store is not None:
                store.upsert_asset(paper_id, asset_type, str(local_path), file_id)

        return uploaded_ids
