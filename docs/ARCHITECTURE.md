# Architecture (`ScholarAgent v2`)

## 1. 전체 데이터 흐름

```text
[Sources: HF Daily Papers / arXiv / User PDF]
        │
        ▼
[Collector] ──► canonical_id / sha256 중복 검사 ──► SQLite (status: discovered)
        │
        ▼
[Triage Agent] (경량 모델) ──► Abstract 3줄 요약 & 태그 (status: triaged)
        │
        ▼
[ChannelAdapter] (Telegram / MyNote Drive / Kakao 실험)
        │  사용자 5편 선택 (status: selected)
        ▼
[PDF Preprocessor (PyMuPDF)]
        ├── extracted.md (섹션 구조화 텍스트)
        ├── segments.json (page, block_id, bbox, text)
        └── pages/*.png (수식·그래프 멀티모달 분석용)
        │
        ▼
[Parallel Subagents] (Semaphore 동시성 제어, 단계별 캐시/재시도)
        ├── MethodDeepDive     (아키텍처, 텐서 흐름, 기존 대비 차이)
        ├── EquationExplainer  (핵심 수식 LaTeX, 변수 사전, 설계 의도)
        ├── FigureTableAnalyzer(표·차트 수치 검증 및 벤치마크 분석)
        ├── CriticalReviewer   (한계점, 실무 시사점, 후속 연구)
        └── HighlightSelector  (segments.json 기반 원문 문구 선정)
        │
        ▼
[Synthesis & Deterministic Highlighter]
        ├── ReportSynthesis ──► summary.ko.md, summary.en.md
        └── PDF Highlighter ──► 좌표 매칭 + annot.update() ──► highlighted.pdf + highlights.json
        │
        ▼
[Storage & Sync]
        └── Google Drive (papers/{Paper Title}/ 또는 daily/YYYY-MM-DD/{Paper Title}/) ──► ScholarNote 태블릿 앱 열람
```

## 2. Main Orchestrator 상위 확장 대비 설계
- **Process/Daemon 분리**: Core 파이프라인은 `scholar-agent <cmd> --json` 순수 CLI로 실행되며, Telegram Long Polling이나 Cron 데몬은 별도 진입점으로 분리됩니다.
- **SQLite WAL + Single-Writer 패턴**: `PRAGMA journal_mode=WAL; busy_timeout=5000;`으로 동시 읽기/쓰기 락을 방지합니다.
- **무제한 병렬 DAG 실행**: 인위적인 `max_concurrent_runs` 병목 없이 `Triage` 완료 직후 본문 분석·멀티모달 검증·비평·하이라이트 추출 서브에이전트들이 일제히 동시 실행되어 지연 시간을 최소화합니다.
- **표준 JSON 계약**: 모든 파이프라인 실행은 `result.json` 및 stdout JSON을 반환합니다.
