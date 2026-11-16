# ScholarAgent — Paper Intelligence Platform

> **개인 연구 지식 플랫폼**: 논문 자동 수집 → Abstract Triage & 5편 선택 → 멀티모달 병렬 Subagent 심층 분석 → 원문 좌표 기반 형광펜 PDF 생성 → Google Drive & `MyNote` 태블릿 필기앱 배포.

## 핵심 설계 원칙
1. **Runtime 독립성**: `Antigravity CLI (agy)`를 기본 런타임으로 사용하되 `RuntimeAdapter`(`antigravity`, `claude`, `codex`)를 통해 역할별 모델 라우팅(예: Triage는 경량 모델, Method/Synthesis는 Opus/Pro)을 지원합니다.
2. **결정론적 PDF 하이라이트**: LLM은 `segments.json`의 원문 문구와 `block_id`만 선정하고, 실제 PDF 좌표 탐색 및 `/AP` 스트림 포함 Annotation 생성은 Python(`PyMuPDF`)이 담당합니다.
3. **Main Orchestrator 대비**: 핵심 파이프라인(CLI/Service)과 데몬(Telegram/Cron)을 분리하고, SQLite WAL 모드 + 전역 동시성 제한 + 표준 JSON 출력을 강제합니다.

## 빠른 시작

```bash
cd ~/Code/ScholarAgent
source .venv/bin/activate
pip install -e .

# 단일 arXiv 논문 전체 파이프라인 실행 (요약 + 형광펜 PDF)
scholar-agent run --arxiv 2410.21276

# Hugging Face Daily Papers 수집 및 Abstract Triage
scholar-agent fetch-daily --limit 10
```

## 문서 & 구성
- **Python 파이프라인 (`src/scholar_agent/`)**: 논문 수집, 멀티모달 병렬 Subagent 분석, 형광펜 PDF 생성
- **Android 태블릿 앱 (`android/` — `ScholarNote`)**: Google Drive 연동, PDF 필기, AI 요약(`summary.ko.md`) 및 형광펜(`highlights.json`) 열람
- [아키텍처](docs/ARCHITECTURE.md)
- [데이터베이스 스키마](docs/DATABASE.md)
- [Agent & Runtime 명세](docs/AGENT_RUNTIME_SPEC.md)
- [저장소 & 채널 명세](docs/STORAGE_CHANNEL_SPEC.md)
- [개발 로드맵](docs/ROADMAP.md)
