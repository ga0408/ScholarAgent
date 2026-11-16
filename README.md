# ScholarAgent — Paper Intelligence Platform

> **개인 연구 지식 플랫폼**: 논문 자동 수집 → Abstract Triage & 5편 선택 → 멀티모달 병렬 Subagent 심층 분석 → 원문 좌표 기반 형광펜 PDF 생성 → Google Drive & `ScholarNote` 태블릿 필기앱 배포.

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
scholar-agent run --arxiv 2410.10450

# 실행과 동시에 Google Drive 업로드
scholar-agent run --arxiv 2410.10450 --upload-drive

# Hugging Face Daily Papers 수집
scholar-agent fetch-daily --limit 10
```

---

## Google Drive 연동 가이드 (Self-Hosted 기준)

`ScholarAgent`(PC)와 `ScholarNote`(Android 태블릿)는 **같은 Google Cloud 프로젝트**의 OAuth 2.0 클라이언트를 공유하며, 보안 심사가 필요 없는 최소 권한(`drive.file` — 앱이 생성한 폴더/파일만 접근)을 사용합니다.

### 1단계: Google Cloud 프로젝트 및 Drive API 활성화 (최초 1회)
1. [Google Cloud Console](https://console.cloud.google.com/)에서 새 프로젝트를 생성합니다.
2. **API 및 서비스 → 라이브러리**에서 **Google Drive API**를 활성화합니다.
3. **OAuth 동의 화면**을 설정합니다.
   - 사용자 유형: **외부(External)**
   - 범위(Scopes): `.../auth/drive.file` 추가
   - 테스트 사용자: 본인 Gmail 계정 등록 (타인에게 공개 배포할 때는 프로덕션 게시)

### 2단계: PC(`ScholarAgent`)용 데스크톱 OAuth 설정
1. **API 및 서비스 → 사용자 인증 정보 → + 사용자 인증 정보 만들기 → OAuth 클라이언트 ID**
2. 애플리케이션 유형: **데스크톱 앱** 선택 후 JSON 다운로드
3. 내려받은 파일을 프로젝트 루트에 `credentials.json`으로 저장합니다 (`Git` 제외됨).
4. 최초 1회 로그인하여 `token.json`을 발급받습니다:
   ```bash
   scholar-agent auth-drive
   # 이후 이미 처리된 논문을 Drive에 업로드
   scholar-agent sync-drive --arxiv 2410.10450
   ```
5. 업로드되면 Drive에 `ScholarAgent/01_Papers/YYYY/MM/DD/{paper_id}/` 폴더가 자동 생성됩니다.

### 3단계: 태블릿(`ScholarNote`)용 Android OAuth 설정
1. 같은 Google Cloud 프로젝트에서 **OAuth 클라이언트 ID → Android**를 추가합니다.
   - 패키지 이름: `com.jjlee.mynote` (`android/app/build.gradle.kts`의 `applicationId`)
   - **SHA-1 인증서 지문**:
     - 개인 테스트(Debug APK): 로컬 `~/.android/debug.keystore`의 SHA-1 등록
     - 타인 배포(Release APK): 배포용 Release Keystore의 SHA-1 등록
   ```bash
   # Debug keystore SHA-1 확인 예시
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep SHA1
   ```
2. [Releases](https://github.com/ga0408/ScholarAgent/releases)에서 `ScholarNote` APK를 설치하거나 직접 빌드합니다:
   ```bash
   cd android && ./gradlew assembleDebug
   ```
3. `ScholarNote` 실행 → Google 로그인 → 소스 폴더를 `ScholarAgent`로 선택 → `highlighted.pdf`를 열면 같은 논문 폴더의 `summary.ko.md`와 `highlights.json`이 상단 **`AI`** 버튼에 자동 로드됩니다.

---

## 문서 & 구성
- **Python 파이프라인 (`src/scholar_agent/`)**: 논문 수집, 멀티모달 병렬 Subagent 분석, 형광펜 PDF 생성
- **Android 태블릿 앱 (`android/` — `ScholarNote`)**: Google Drive 연동, PDF 필기, AI 요약(`summary.ko.md`) 및 형광펜(`highlights.json`) 열람
- [아키텍처](docs/ARCHITECTURE.md)
- [데이터베이스 스키마](docs/DATABASE.md)
- [Agent & Runtime 명세](docs/AGENT_RUNTIME_SPEC.md)
- [개발 로드맵](docs/ROADMAP.md)
