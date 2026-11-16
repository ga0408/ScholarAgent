# Database Schema (SQLite + WAL)

`data/paper_agent.db`에 저장되며, 초기화 시 `PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;`을 적용합니다.

## 주요 테이블

| 테이블 | 역할 | 핵심 컬럼 |
| :--- | :--- | :--- |
| `papers` | 논문 메타데이터 및 상태 머신 | `paper_id`, `canonical_id`, `content_sha256`, `title`, `authors_json`, `abstract`, `status`, `work_dir` |
| `jobs` | Subagent/단계별 실행 및 부분 재시도 추적 | `id`, `paper_id`, `stage`, `runtime`, `model`, `status`, `artifact_path`, `error_message` |
| `summaries` | 생성된 요약본 및 버전/모델 이력 | `id`, `paper_id`, `lang`, `runtime`, `model`, `version`, `file_path` |
| `highlights` | 선정 문구 및 매칭된 PDF 좌표 | `id`, `paper_id`, `block_id`, `page`, `category`, `importance`, `quote_text`, `rects_json`, `matched` |
| `paper_assets` | Google Drive 파일 ID 매핑 | `id`, `paper_id`, `asset_type`, `local_path`, `drive_file_id` |

## `papers.status` 상태 전이
`discovered` → `triaged` → `notified` → `selected` (또는 `skipped`) → `processing` → `completed` (또는 `failed`)
