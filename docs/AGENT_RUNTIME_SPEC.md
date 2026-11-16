# Agent, Runtime & Storage Specifications

## 1. RuntimeAdapter (`src/paper_agent/agents/base.py`)
모든 Agent Runtime(`AntigravityAdapter`, `ClaudeAdapter`, `CodexAdapter`)은 아래 공통 인터페이스를 구현합니다.
- `run(prompt: str, *, work_dir: Path, schema_path: Path | None, images: list[Path] | None, model: str | None, effort: str | None, timeout: int) -> AgentResponse`
- **Antigravity (`agy`)**: `agy -p <prompt> --output-format json [--json-schema <schema>] [--model <model>] [--effort <effort>] [--add-dir <work_dir>] --dangerously-skip-permissions`
- **Claude (`claude`)**: `claude -p <prompt> --output-format json [--json-schema <schema>] [--model <model>]` (Opus 등 모델 테스트용)
- **Codex (`codex`)**: `codex exec ...`

## 2. ChannelAdapter (`Telegram` / `MyNote Drive` / `Kakao`)
3가지 알림·선택 채널을 동일 인터페이스로 실험합니다.
- `send_triage(date_str, papers)`
- `poll_selections(date_str) -> list[str]`

## 3. Google Drive 폴더 규격
```text
PaperAgent/
├── 00_Inbox/
├── 01_Papers/YYYY/MM/DD/{paper_id}/
│   ├── original.pdf
│   ├── extracted.md
│   ├── metadata.json
│   ├── summary/
│   │   ├── summary.ko.md
│   │   └── summary.en.md
│   ├── highlights/
│   │   ├── highlighted.pdf
│   │   └── highlights.json
│   └── discussion/
├── 02_Comparisons/
├── 03_DailyReports/
├── 04_Podcasts/
└── 05_YouTube/
```
