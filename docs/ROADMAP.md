# Roadmap (`ScholarAgent`)

- [x] **Phase 0**: 설계 확정, `AGENTS.md` / `CLAUDE.md` / `skills/` 심볼릭 링크 구조, JSON Schemas
- [ ] **Phase 1 (MVP)**: `AntigravityAdapter` (`agy`) + `ClaudeAdapter`, SQLite WAL, arXiv/HF 수집, PyMuPDF 전처리(텍스트+좌표+페이지 PNG), 병렬 Subagent 요약 + 형광펜 PDF 생성 → **논문 1편 End-to-End 검증 & Git 배포**
- [ ] **Phase 2**: Google Drive OAuth 연동 + `ChannelAdapter` 3종(`telegram`, `mynote_drive`, `kakao`) 실사용 비교 실험
- [ ] **Phase 3**: `MyNote` 태블릿 실기기 연동 검증 (`highlighted.pdf` 렌더링, `summary.ko.md` / `highlights.json` 연동)
- [ ] **Phase 4**: SQLite FTS5 / FAISS 검색, 다중 논문 비교(`paper-comparison`), 논문 토론(`paper-discussion`)
- [ ] **Phase 5**: 팟캐스트 대본/TTS, YouTube 영상/자막 생성 및 Main Orchestrator 통합
