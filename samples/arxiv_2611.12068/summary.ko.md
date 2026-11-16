# MIRIX-2.0: Multi-Agent Memory System 연구 분석 보고서

- **논문 제목**: MIRIX-2.0: Multi-Agent Memory System
- **저자**: Wei-Chieh Huang, Weizhi Zhang, Jiayan Nan, Peng Xia, Yu Wang
- **Paper ID**: `arXiv:2611.12068` (Published: 2026-11-09)
- **PDF**: https://arxiv.org/pdf/2611.12068.pdf

---

## 1. Overview

**MIRIX-2.0**은 LLM agent의 Long-Term Memory 시스템이 직면한 **실용성(Practicality), 정확성(Accuracy), 효율성(Efficiency)** 의 세 가지 핵심 요구사항을 동시에 달성하도록 설계된 **Multi-Agent Memory System**이다 (p. 1). 별도의 Fine-tuning 없이 Closed Model(`gpt-4.1-mini`, `GPT-6 Luna`, `GPT-5.2`)과 Open Model(`Qwen3.6-35B-A3B`) 모두에서 즉시 동작하며, 단일 메모리 아키텍처 안에서 **User-centric Memory(개인화·사실 회상)** 와 **Agent-centric Procedural Memory(경험·Skill 정제)** 를 통합 지원한다 (p. 1, p. 4, p. 10, p. 13).

시스템은 Vector Database 기반의 **Typed Evidence Plane**(원본 레코드를 보존하는 System of Record)과 Neo4j 기반의 **Provenance Hypergraph**(n-ary fact frame과 원문 row 인용을 관리하는 인덱스 계층)를 분리하고, Write Path 외부에서 비동기적으로 동작하는 **AutoDream**을 결합한다 (p. 2, p. 5–8). 정량 평가에서 비교된 5개 QA 벤치마크 중 4개에서 최고 정확도를 기록하여 `LoCoMo` **91.4%**, `LongMemEval-S` **72.6%**(최강 baseline인 `HyperMem` 대비 **+14.3 points**)를 달성했으며, AutoDream을 비활성화한 구성(`M1`)만으로도 `MemoryAgentBench`의 `SHDocQA`(83.0%)와 `MHDocQA`(71.0%)에서 `HyperMem` 대비 각각 **+31.0 points**, **+22.0 points** 높은 성능을 보였다 (p. 1, p. 11, p. 27). 또한 Procedural Memory 평가에서 `ALFWorld`(76.4% with `GPT-6 Luna`)와 `WebShop`(37.4%)에서 비교된 메모리 기법 중 최고 평균 성공률을 기록했고, `MetaClaw-Bench`에서 `GPT-5.2` 기준 **49.2%** 정확도(vs `MetaClaw Skills` 44.0%)를 달성했다 (p. 1, p. 3, p. 13, p. 28).

---

## 2. Background

LLM agent는 단일 Context Window의 한계를 훨씬 초과하는 장기간(long-horizon) 상호작용 환경에서 동작하므로, 다수의 세션에 걸쳐 정보를 유지하며 개인화(Personalization), 상태 일관성(Consistency), 과거 성공 경험의 재사용을 가능하게 하는 **Long-Term Memory**가 필수적이다 (p. 1–2).

최근 Long-Term Memory 연구는 활용 목적에 따라 크게 두 갈래로 발전해 왔다 (p. 2):
1. **User-centric Memory**: 사용자와의 대화, 선호, 사건 이력, 문서 맥락을 폭넓게 저장하고 상호 연결하여 사용자 맞춤형 응답과 장기 회상을 지원하는 메모리이다. 이 영역에서는 세밀한 사실(이름, 날짜, 수치 등)을 누락 없이 보존하고 다중 홉(multi-hop) 및 시간 순서 관계를 추적하는 능력이 중요하다 (p. 2).
2. **Agent-centric Memory (Procedural Memory)**: 에이전트가 환경과 상호작용하며 축적한 실행 궤적(trajectory)에서 재사용 가능한 워크플로우, 전략, 회피 규칙을 정제(refinement)하여 Skill 라이브러리 형태로 축적하는 메모리이다 (p. 2).

그러나 기존 접근법들은 두 메모리 패러다임을 별개의 시스템으로 분리하여 다루거나, 저장·인덱싱·통합 방식의 구조적 제약으로 인해 실제 프로덕션 배포 시 정확성과 효율성을 동시에 만족시키지 못하는 한계를 보였다 (p. 2, p. 4).

---

## 3. Problem

기존 Long-Term Memory 시스템들은 **Accuracy, Practicality, Efficiency 중 최소 하나를 포기하는 구조적 트레이드오프**를 가진다 (p. 2):

1. **Parametric Memory의 실용성 한계 (`Titans` 등)**: 모델 가중치나 잠재 상태(latent state)에 메모리를 내재화하는 방식은 별도 학습(Fine-tuning)을 요구하므로 가중치 접근이 불가능한 Closed Model 환경에 적용할 수 없다 (p. 2, p. 15).
2. **Consolidation / 요약 기반 메모리의 정보 영구 소실 (`mem0`, `MemoryBank`, `Auto-Dreamer` 등)**: 저장 시점에 대화를 요약하거나 핵심 사실만 추출·재작성하여 저장소를 작게 유지하는 방식은 요약에서 탈락한 세부 정보(고유명사, 수치, 날짜, 횟수 등)를 영구적으로 유실하며, LLM이 기존 메모리를 반복적으로 덮어쓰는 과정에서 오류나 환각이 누적될 위험이 있다 (p. 2, p. 16).
3. **Flat Vector / Unbounded Retrieval의 효율성·구조화 한계 (`MIRIX-1.0`, `RAG`, `Long-context`)**: 모든 레코드를 평면적으로 저장하고 직접 검색하는 방식은 이력이 길어질수록 검색 노이즈가 커지며, 엔티티 간 다중 홉 관계를 구조적으로 연결하는 데 취약하다 (p. 2, p. 10).
4. **기존 Graph 및 File-system Memory의 표현력·회상률 한계**:
   - `Graphiti` 등의 **Binary Triple Graph**는 다수의 참여자(예: 항공편의 예약자, 출발지, 도착지, 항공사, 동행자)가 얽힌 n-ary fact를 분리된 이진 엣지들로 쪼개어 참여자 간 결속을 파괴한다 (p. 2, p. 7).
   - `HyperMem` 등의 **Topic-Tree Hypergraph**는 Topic 아래에 Episode, 그 아래에 Fact를 묶는 계층 구조를 취하고 저장 시점의 Topic Summary에서 하향식(top-down)으로 검색하기 때문에 요약에 포함되지 않은 세부 사실의 Recall이 급격히 떨어진다 (p. 2).
   - `Claude Code Auto Memory` 등 **File-system Memory**는 크기 상한이 있는 1줄 요약 인덱스를 매 세션 로드하므로, 요약 밖의 세부 사항은 단순 문자열 검색에 의존해야 하며 파일 간 Entity Resolution이나 관계 추적이 불가능하다 (p. 2).

---

## 4. Motivation

실제 프로덕션 배포 환경에서 신뢰할 수 있는 장기 메모리 시스템을 구현하기 위해 MIRIX-2.0은 다음 네 가지 기술적 요구사항을 충족하는 것을 목표로 삼는다 (p. 2, p. 5):

1. **Model-Agnostic Practicality**: 모델 파라미터 학습이나 Fine-tuning 없이, 상용 Closed Model과 Open-weight Model 모두에서 일관된 안전성과 동작을 보장해야 한다 (p. 1–2).
2. **Source Fidelity**: 메모리 추출·통합 과정에서 원문 세부 사항을 파괴하지 않고, 모든 답변과 그래프 인덱스가 저장된 원천 레코드로 직접 역추적(provenance citation) 가능해야 한다 (p. 2, p. 5).
3. **Temporal Identity**: 언급 시점($\tau_m$)과 사건 발생 시점($\tau_e$)을 구분하고, 표현이 유사하더라도 서로 다른 시점·맥락에서 일어난 사건들이 하나로 붕괴(collapse)되지 않도록 사건 고유의 정체성을 보존해야 한다 (p. 5, p. 7).
4. **Bounded Access & Unified Architecture**: 대화·문서 이력이 누적되더라도 질의 시 검사하는 증거 집합 크기를 명시적 Budget 하에서 유계(bounded)로 제한하여 효율성을 유지해야 하며, 단일 저장소 내에서 User-centric Memory와 Agent-centric Procedural Memory를 함께 지원해야 한다 (p. 2, p. 4–5).

---

## 5. Contribution

본 논문의 핵심 기여는 다음 세 가지로 요약된다 (p. 2–5):

1. **Accurate and Practical Layered Design (저장소와 인덱스의 분리)**:
   6개의 특화된 Typed Memory Agent가 Vector Database의 **Typed Evidence Plane(System of Record)** 에 완전한 원본 레코드를 먼저 커밋하고, **Provenance-Preserving N-ary Hypergraph**가 순수 인덱스 계층으로만 동작한다 (p. 2, p. 4–6). Fail-soft indexing으로 그래프 구축 실패 시에도 원본 메모리 유실을 막고, **Occurrence Identity**를 도입해 유사 표현 사건의 오병합을 방지한다 (p. 2, p. 5, p. 7).
2. **One Memory for Users and Agents (통합 메모리 아키텍처)**:
   동일한 6-Agent 시스템 내에서 사용자 개인화를 담당하는 User-centric Memory(`Core`, `Episodic`, `Semantic`, `Resource`, `Knowledge Vault`)와 에이전트의 과업 수행 능력을 향상시키는 Agent-centric `Procedural` Memory를 단일 저장소 구조로 통합 지원한다 (p. 2, p. 4, p. 23).
3. **Efficient Bounded Retrieval & Safe Out-of-Path AutoDream**:
   질의 유형별 결정론적 Planner $\Pi(q)$가 명시적 Row Budget 하에서 Hypergraph를 탐색하여 유계된 증거만 Vector Database에서 역참조한다 (p. 7–8). 또한 Write Path 외부에서 실행되는 **AutoDream**을 통해 User-centric `Semantic` 메모리는 원본 레코드 수정 없이 중복 Entity Anchor만 무손실 병합(`LoCoMo` +3.0 points 향상)하고, Agent-centric `Procedural` 메모리는 누적 Task Session으로부터 원문 인용구(quote) 검증을 거친 Skill로 증류·정제한다 (p. 3, p. 8–9, p. 11).

---

## 6. Method

### 6.1 전체 아키텍처 및 영속 상태 정의

MIRIX-2.0은 저장소와 색인의 분리를 핵심 원칙으로 삼는다 (p. 4–5, Figure 1). 사용자 $u$의 입력 스트림 $\mathcal{X}_u = (x_1, \dots, x_T)$가 유입될 때 시점 $t$에서의 영속 상태(Persistent State)는 다음과 같이 정의된다 (p. 5):

$$\mathcal{S}_u^t = (P_u^t, G_u^t) \tag{1}$$

- $\mathcal{S}_u^t$: 시점 $t$에서의 사용자 $u$에 대한 영속 메모리 상태 (p. 5)
- $P_u^t$: **Typed Evidence Plane**의 전체 저장소 (`Episodic` store $P_u^E$, `Semantic` store $P_u^S$, `Procedural` store $P_u^P$ 등 6종 포함) (p. 5)
- $G_u^t$: 시점 $t$의 **Provenance Hypergraph** 색인 (p. 5)

아키텍처 전반에서 **LLM과 Deterministic Code의 역할을 엄격히 분리(Hard Invariants)** 한다 (p. 1, Section A.6 on p. 25). Routing, record writing, frame extraction/repair, same-entity 판정, experience distillation/curation, answer generation 등 의미 해석이 필요한 작업은 LLM이 담당하는 반면, admissible anchor, canonical key, frame identity, provenance attachment, query budget, merge algebra, mutation order 등 시스템 안전성과 직결된 경계는 결정론적 코드가 통제하여 Fine-tuning 없는 모델에서도 일관성을 확보한다 (p. 1, p. 25).

---

### 6.2 Write Path Phase 1: Typed Evidence Plane & Multi-Agent Routing

입력 $x_t$(대화 또는 멀티모달 데이터)가 들어오면 `Meta Memory Manager` $R_\theta^\star(x_t)$가 업데이트할 메모리 타입 집합 $\mathcal{K}_t \subseteq \{1, \dots, 6\}$을 결정하고, 선택된 각 Memory Writer $W_k^\star(x_t, P_u^{t-1})$가 고유 스키마에 따라 레코드를 생성한다 (p. 6, Eq. (A.1) on p. 22). 6개의 Typed Memory Agent는 기능별로 분리되어 있다 (Table 5 on p. 23):
- **`Core`**: 사용자 및 에이전트의 지속적 핵심 상태(이름, 선호, 역할 등)
- **`Episodic`**: 시각 정보가 포함된 사건(event) 기록 ($P_u^E$)
- **`Semantic`**: 지속적 사실(durable claim), 개념, 관계 지식 ($P_u^S$)
- **`Procedural`**: 실행 가능한 워크플로우 및 Skill ($P_u^P$)
- **`Resource`**: 문서 및 멀티모달 아티팩트
- **`Knowledge Vault`**: 민감한 원문 값(credential, 정확 수치 등)

Writer 프롬프트는 이름, 목록, 수치, 날짜 등의 세부 정보를 요약으로 뭉개지 않고 원형 그대로 보존하도록 지시한다 (p. 6, p. 22). `Episodic`과 `Semantic` 레코드는 다음 스키마로 표현된다 (p. 6):

$$m = (i, k, y, \tau_e, \tau_m, \rho, \mathbf{z}) \tag{2}$$

- $i$: 불변 행 식별자 (Immutable row identifier)
- $k \in \{\text{episodic}, \text{semantic}\}$: 메모리 유형
- $y$: 저장된 원문 텍스트 (Stored text)
- $\tau_e$: 사건 발생 시각 (Event time, 해당 시)
- $\tau_m$: 언급 또는 기록 시각 (Mention/write time)
- $\rho$: 출처 및 Chunk provenance 정보
- $\mathbf{z}$: 검색용 임베딩 벡터 (Search embeddings)

생성된 레코드는 **Hypergraph 추출보다 먼저 Vector Database에 `Commit(P_u, m)`으로 커밋**된다 (Algorithm 1 line 3, p. 6). 이 중 $k \in \{\text{episodic}, \text{semantic}\}$인 레코드만 Fail-soft Hook을 통해 Hypergraph 인덱싱 단계로 전달되므로, 그래프 쓰기 실패가 발생하더라도 이미 저장된 메모리는 롤백되지 않는다 (p. 2, p. 6, p. 22). 나머지 `Core`, `Procedural`, `Resource`, `Knowledge Vault`는 각각의 타입별 직접 조회 경로를 사용한다 (p. 5, p. 22–23).

---

### 6.3 Write Path Phase 2: Provenance-Preserving N-ary Hypergraph & Occurrence Identity

커밋된 `Episodic` 및 `Semantic` 레코드 $m$은 Neo4j에 저장되는 **Provenance Hypergraph** $G_u$로 색인된다 (p. 6, p. 25):

$$G_u = (\mathcal{A}_u, \mathcal{F}_u, \mathcal{E}_u) \tag{3}$$

- $\mathcal{A}_u$: Entity 또는 Concept Anchor 집합
- $\mathcal{F}_u$: 구체화된 사실 프레임 (Reified fact frames) 집합
- $\mathcal{E}_u$: 역할 유형이 부여된 연결선 (Role-typed incidence edges) 집합

#### 1) Extraction, Audit Repair & Anchor Admission
레코드 $m$으로부터 $\Phi \leftarrow \mathrm{Extract}^\star(m) \cup \mathrm{Repair}^\star(m)$를 통해 n-ary frame과 entity를 추출한다 (p. 6–7, p. 22–23). 이때 결정론적 감사(Deterministic audit)가 누락된 인용구·숫자·시각 단어(콘텐츠 단어 커버리지 $< 45\%$ 시 최대 12개 누락 항목)를 감지하면 `Repair^\star(m)`가 기존 추출 결과를 삭제하지 않고 보완한다 (p. 7, p. 23). 추출된 entity별 이름 임베딩 $\mathbf{e}$에 대해 사용자 기존 anchor 간 ANN(Approximate Nearest Neighbor) 검색으로 유사 후보를 최대 4개 조회($\cos \ge 0.72$, 코드 구현상 $(1+\cos)/2 \ge 0.86$)한 뒤, 레코드당 1회 `Canon^\star(\Phi)` LLM 호출로 보수적 canonicalization을 수행한다 (p. 22–23). 이어 `Admit` 게이트가 결정론적 정규화 키 $\kappa(n)$(대소문자·문장부호·단복수 정규화, 숫자는 보존)와 일반 용어·대화 역할 의사-엔티티 차단을 적용하며, 레코드당 승인 anchor 수를 **`Episodic` 최대 16개, `Semantic` 최대 18개**로 제한한다 (p. 6, p. 22–23).

#### 2) Anchor 및 N-ary Fact Frame 정의
각 Anchor $a \in \mathcal{A}_u$는 다음과 같이 정의되며 자신을 언급한 원문 행 식별자 집합을 직접 보유한다 (p. 6):

$$a = (i_a, n_a, t_a, \mathbf{e}_a, I_a^E, I_a^S) \tag{4}$$

- $i_a$: Anchor 식별자
- $n_a$: 정규화된 대표 이름 (Canonical name)
- $t_a$: 엔티티 유형 (Entity type)
- $\mathbf{e}_a$: 이름 임베딩 벡터 (Name embedding)
- $I_a^E, I_a^S$: 해당 Anchor를 언급하는 `Episodic` / `Semantic` 행 식별자 집합

복합적인 사건이나 사실은 Binary Triple로 분절하지 않고 최소 2개 이상의 서로 다른 Anchor로 해석될 때($|A_f| \ge 2$) 단일 **N-ary Fact Frame**으로 구체화(materialize)된다 (p. 7):

$$f = \left(p, \{(r_j, a_j)\}_{j=1}^{d_f}, \ell_f, \tau_e, \tau_m, C_f\right) \tag{5}$$

- $p$: 정규화된 술어 (Canonical predicate)
- $\{(r_j, a_j)\}_{j=1}^{d_f}$: Open-vocabulary 역할 $r_j$와 Anchor $a_j$ 쌍의 집합 (arity $d_f$)
- $\ell_f$: 리터럴 속성(날짜, 가격, 수량, 기간, 측정치 등)과 값의 매핑 (별도 Anchor가 아닌 Frame에 직접 부착하여 수치가 문맥과 분리되지 않도록 함)
- $\tau_e, \tau_m$: 사건 시각 및 언급/기록 시각
- $C_f$: 해당 Frame이 인용하는 Vector Database 레코드 식별자 집합 ($C_f \leftarrow C_f \cup \{i_m\}$으로 누적 갱신)

#### 3) Semantic Identity vs. Episodic Occurrence Identity
`Semantic` Frame은 동일한 지속적 사실(durable claim)에 대한 반복 근거를 단일 노드의 인용 집합 $C_f$에 누적하기 위해 시간 키 없이 해시 식별자를 부여한다 (p. 7):

$$\operatorname{id}_S(f) = H(u, p, \sigma(f)) \tag{6}$$

여기서 $H$는 해시 함수이고 $\sigma(f) = \{r_j : \kappa(n_{a_j})\}$는 정렬된 역할-정규화 앵커 키 집합이다 (p. 7). 반면 `Episodic` Frame은 문구가 비슷하더라도 서로 다른 시점의 사건이 하나로 붕괴되지 않도록 발생 키(Occurrence key) $\chi(f)$를 추가한다 (p. 7):

$$\operatorname{id}_E(f) = H(u, p, \sigma(f), \chi(f)) \tag{7}$$

$$\chi(f) = \begin{cases} \text{explicit event time}, & \text{if extracted}, \\ \text{immutable memory id}, & \text{otherwise}. \end{cases} \tag{8}$$

명시적 사건 시각이 추출된 경우에는 동일 날짜·동일 사건의 여러 언급이 하나의 Frame을 공유하지만, 날짜가 미기재된(undated) 사건은 불변 메모리 ID를 사용하여 사건 정체성을 분리한다. 또한 계획된(planned) 사건과 완료된(done) 사건 여부가 Frame 정체성을 바꾸지 않도록 설계되었다 (p. 7).

---

### 6.4 Read Path: Graph-Owned Evidence Retrieval & Answer-Time Verification

MIRIX-2.0의 조회 경로는 전체 테이블 스캔을 배제하고 **Graph Traversal을 유일한 진입점**으로 삼아 유계된(bounded) 증거 집합만을 Vector Database에서 역참조한다 (그래프 읽기 오류가 발생한 경우에만 직접 Vector Database 검색으로 Fallback한다) (p. 7–8, p. 23–24).

#### 1) Deterministic Query Planner & Budgets
질의 $q$가 입력되면 결정론적 플래너 `Plan(q)`가 질의 연산자 $o_q$와 명시적 행 수 상한 $(B_q^B, B_q^R)$을 산출한다 (p. 7, Table 6 on p. 23):

$$\Pi(q) = \left(o_q, \mathcal{P}_q, \mathcal{C}_q, \mathcal{Y}_q, \mathcal{T}_q, B_q^B, B_q^R\right) \tag{9}$$

- $o_q \in \{\text{single}, \text{temporal}, \text{intersection}, \text{list-union}, \text{count}\}$: 5가지 질의 연산자
- $\mathcal{P}_q$: 정규화된 관계 패밀리 및 표면형 집합
- $\mathcal{C}_q$: 비관계형 제약 조건 집합 (Non-relational constraints)
- $\mathcal{Y}_q$: 기대 정답 유형 집합 (Expected answer types)
- $\mathcal{T}_q$: 날짜 및 시간 순서 제약 조건 집합
- $B_q^B \in \{6, 7, 8\}$: Base-recall 레인 행 수 상한 예산
- $B_q^R \in \{4, 6, 7, 9, 12\}$: Relation-cited 레인 행 수 및 렌더링할 그래프 사실 수 상한 예산 (예: `single` 조회는 $B_q^B=6, B_q^R=4$, `count` 질의는 $B_q^B=8, B_q^R=12$)

#### 2) Anchor Entry Scoring & Dual-Lane Retrieval
질의 임베딩으로 초기 회상한 15개의 Anchor와 관계 질의의 정확 일치 Anchor(최대 16개)의 합집합에 대해 다음 점수를 매겨 상위 $K_A = 15$개의 Anchor 집합 $A_q$를 선택한다 (p. 7, p. 23):

$$s_A(a, q) = s_{\text{vec}} + s_{\text{exact}} + s_{\text{lex}} + s_{\text{pred}} + s_{\text{spec}} - s_{\text{hub}} \tag{10}$$

- $s_{\text{vec}}$: 벡터 유사도 점수 (지배 항)
- $s_{\text{exact}}, s_{\text{lex}}, s_{\text{pred}}, s_{\text{spec}}$: 각각 질의 내 정확 일치 보너스, 토큰 어휘 겹침, 술어 호환성, 다중 토큰 구체성(multi-token specificity) 점수
- $s_{\text{hub}}$: 범용 허브 노드의 독점을 억제하기 위한 로그 차수(logarithmic degree) 페널티

선택된 $A_q$로부터 두 개의 병렬 레인이 실행된다 (p. 7–8, p. 23–24):
- **Base-recall Lane**: `Rows(A_q)`를 통해 Anchor에 직결된 행 및 공동 참여자(co-participant) 행 ID를 수집하여 Vector Database에서 역참조한다 (동일 chunk 및 시간 인접 정보는 Vector DB provenance에서 재계산).
- **Relation-citation Lane**: Neo4j 내부에서 술어 필터링($\mathcal{P}_q$)과 결정론적 ID 정렬을 거쳐 인접 Frame을 최대 $K_F = 240$개로 제한(`Adj(A_q, \mathcal{P}_q)`)한 뒤, 9항 가산 점수 함수로 순위를 매겨 상위 $B_q^R$개 Frame $F_q$와 그 인용 행 $\bigcup_{f \in F_q} C_f$를 추출한다 (p. 8, p. 23–24):

$$s_F(f, q) = s_{\text{rel}} + s_{\text{entity}} + s_{\text{constraint}} + s_{\text{type}} + s_{\text{time}} + s_{\text{intersection}} + s_{\text{specificity}} + s_{\text{role}} + s_{\text{state}} \tag{11}$$

여기서 $s_{\text{rel}}, s_{\text{entity}}, s_{\text{constraint}}, s_{\text{type}}, s_{\text{time}}$은 관계·개체명·리터럴 제약·정답 유형·시간 호환성 점수이며, $s_{\text{intersection}}$은 교집합 질의의 공유 목적어 점수, $s_{\text{specificity}}$는 바인딩 인자 수에 따른 구체성 점수, $s_{\text{role}}$은 인자 방향과 역할 유형 일치 보상/페널티, $s_{\text{state}}$는 요청 날짜 일치 보상 및 `count`/`list-union`/`temporal` 질의에서 계획된(planned) 사건이 완료된 사건으로 오인되는 것을 막는 페널티이다 (p. 8).

#### 3) Citation-Balanced Fusion & Answer Verification
`Fuse` 단계는 각 레인을 얕은 vector-rank prior와 lexical coverage로 reranking한 뒤, `Episodic`과 `Semantic`을 분리 유지한 상태에서 Relation-cited 행($\le B_q^R$)을 우선 확보하고 Base-recall 행($\le B_q^B$)을 병합한다 (동일 날짜의 `Episodic` 패러프레이즈만 중복 제거) (p. 8, p. 24). 답변 에이전트 `Agent^\star(q)`는 최대 5회의 `Read` 호출 후 초기 답변 $\hat{y}$를 생성하며, Rule-based `Check(\hat{y})`가 불확실성 표현, Evidence Ledger 불일치, `Intersection` 질의 누락 등을 감지하면 최대 1회의 추가 검색 및 `Rewrite^\star(\hat{y}, \mathrm{Read}(q'))`를 수행하여 최종 답변을 출력한다 (Algorithm 1 lines 16–20, p. 6, p. 8, p. 24).

---

### 6.5 Asymmetric Out-of-Path AutoDream (`SemanticDream` & `ProceduralDream`)

AutoDream은 Write Path 외부에서 비동기·배치로 동작하며, User-centric `Semantic` 메모리와 Agent-centric `Procedural` 메모리를 정반대 방식으로 통합·정제한다 (p. 3, p. 8–9, p. 24–25).

#### 1) Semantic AutoDream (무손실 구조 통합)
`SemanticDream`은 5개 chunk마다(및 종료 시) 실행되며, 원본 저장소 $P_u$를 전혀 수정하지 않고 Hypergraph $G_u$ 내 중복 Anchor만 통합하는 무손실 계약을 따른다 (p. 8):

$$\operatorname{Dream}(P_u, G_u; \Delta_u) = (P_u, \widetilde{G}_u) \tag{12}$$

신규 기록된 Semantic Frontier $\Delta_u$가 뒷받침하는 적격 Anchor 집합 $\mathcal{A}_\Delta$에 대해, 이름 임베딩 색인에서 최근접 이웃 4개($\operatorname{NN}_4(a)$) 중 자기 자신을 제외하고 코사인 유사도 $\ge 0.86$(index score $0.93$) 및 동일하고 정체성이 안정적인 엔티티 유형에 속하는 쌍만 검증 후보 $\mathcal{C}_\Delta$로 생성한다 (p. 8, p. 24):

$$\mathcal{C}_\Delta = \left\{ (a, b) : \begin{array}{l} a \in \mathcal{A}_\Delta,\; b \in \operatorname{NN}_4(a) \setminus \{a\}, \\ \cos(\mathbf{e}_a, \mathbf{e}_b) \ge 0.86, \\ t_a = t_b \in \mathcal{T}_{\text{safe}} \end{array} \right\} \tag{13}$$

여기서 $\mathcal{T}_{\text{safe}} = \{\text{person}, \text{organization}, \text{location}, \text{object}, \text{concept}\}$이며, `Episodic` 전용 Anchor는 사건 정체성 보호를 위해 구조적으로 제외된다 (p. 8–9). 거절된 쌍은 타입별 해시 캐시 $\mathcal{N}$에 기록되어 재검증을 피하고, `Same^\star(a, b)` LLM 호출은 중간 라운드 최대 **80쌍**, 최종 라운드 최대 **120쌍**(병합 발생 시 repair 라운드 **60쌍** 추가, 총 180쌍)으로 상한이 고정된다 (p. 9, p. 24–25). 승인된 쌍 `Merge(a -> b)`는 $I^E, I^S$의 합집합, 언급 횟수 합산, admission score 최댓값, 인접 엣지 rewire를 통해 손실 없이 병합된다 (p. 9, p. 24).

#### 2) Procedural AutoDream (인용 검증 기반 Skill 증류)
`ProceduralDream`은 5개 Task Session이 누적될 때마다 백그라운드에서 실행된다 (p. 9, p. 25). `Distill^\star(S_u)`가 누적 세션에서 재사용(reuse) 및 회피(avoid) 경험 $e_{1:m}$을 추출할 때 반드시 원문 transcript 인용구(quote) 검증을 거치며(검증 실패 시 `unclear` 처리), 이어 `Curate^\star(P_u^P, e_{1:m})`가 편집 인용구 검증과 함께 $P_u^P$ 내 Skill을 생성·편집·삭제한다 (Algorithm 1 lines 27–29, p. 6, p. 9, p. 25).

---

### 6.6 이론적 복잡도 및 Bounded Caps

Section A.6(p. 25)에 정의된 MIRIX-2.0의 연산 복잡도와 결정론적 상한은 다음과 같다:
- **Write Path**: LLM 추론 및 indexed DB lookup을 제외할 때, 메모리 1건의 그래프 기록 비용은 승인 anchor 수 $d$와 추출 frame 수 $h$에 대해 **$O(d + h)$** 이며, Admission Gate에 의해 $d \le 16$ (`Episodic`) 또는 $d \le 18$ (`Semantic`)으로 상한이 고정된다 (p. 22, p. 25).
- **Read Path**: Post-retrieval ranking 및 fusion 비용은 **$O(K_A + K_F + K_M)$** 이다. 여기서 $K_A = 15$(유지 anchor 상한), $K_F = 240$(Neo4j predicate 필터 후 인접 frame 상한), $K_M = B_q^R \le 12$(operator별 인용 row 상한)이므로 Vector Database에서 무제한(unbounded) 증거 집합을 구체화하지 않는다 (p. 23, p. 25).
- **Semantic AutoDream**: Vector index를 이용한 ANN 후보 생성 비용은 **$O(|\Delta_u| \log |\mathcal{A}_u|)$** 로 전체 이력 크기가 아닌 신규 Frontier 크기 $|\Delta_u|$에만 비례하며, LLM 검증 호출 역시 라운드당 상한(80 / 120+60)으로 통제된다 (p. 8, p. 24–25).

---

## 7. Experiment

### 7.1 실험 설정 및 비교 구성 (Section 3, pp. 9–14; Appendix B, pp. 26–29)

- **Ablation Variants (p. 9)**:
  - `MIRIX-1.0 (M0)`: Provenance Hypergraph와 AutoDream 없이 Flat Vector Memory만 사용하는 기준 구성
  - `MIRIX-2.0 (M1, graph)`: Provenance Hypergraph와 Graph-owned Retrieval을 활성화하되 AutoDream은 비활성화한 구성
  - `MIRIX-2.0 (M2, graph + dream)`: Hypergraph와 `SemanticDream`(5 chunk마다 및 종료 시 Write Path 외부에서 호출)을 모두 활성화한 전체 구성
- **User-Memory Backbone & Protocols (p. 10, p. 26–27)**:
  - Answerer/Extractor는 `gpt-4.1-mini`(temperature 0, seed 42), Topic extractor는 `gpt-4.1-nano`, Embedding은 `text-embedding-3-small`을 사용했다 (외부 baseline은 각 기본 embedding 유지).
  - `LoCoMo`(1,540 non-adversarial 문항) 및 `LongMemEval-S`(300문항)는 `gpt-4o-mini` judge의 majority-of-3 voting으로 채점했다.
  - `MemoryAgentBench` Document QA(`SHDocQA` 100문항, `MHDocQA` 100문항은 substring matching; `DetectiveQA` 71문항은 letter exact matching)는 실험적 선택으로 **`M1`만 평가**했다.
  - `MemoryCD`(`Home_and_Kitchen` 100명 대상 Cross-domain 및 Single-domain 리뷰 요약/생성)는 `ROUGE-1`, `ROUGE-L`, `BLEU-4`, `BERTScore-F1`으로 평가했다.
- **Agent-Memory Protocols (p. 12–13, p. 27–28)**:
  - `ALFWorld`(39 train / 134 held-out, max 50 steps; `GPT-6 Luna`, `GPT-5.6 Luna`, 3회 평균±표준편차) 및 `WebShop`(40 train / 140 held-out, max 15 steps; `GPT-6 Luna`, 3회 평균±표준편차)에서 `No memory`, `ReasoningBank`, `AWM`과 비교했다.
  - `MetaClaw-Bench` Part I(30일간 346문항, 중 224 file-check 문항)은 `GPT-5.2`(baseline은 MetaClaw 원 논문 수치) 및 `Qwen3.6-35B-A3B`로 `Baseline`, `MetaClaw Skills`와 비교했다.

---

### 7.2 User-centric Memory: Conversational Recall (`LoCoMo` & `LongMemEval-S`)

`LoCoMo`(Table 1, p. 11) 및 `LongMemEval-S`(Table 7, p. 27; Figure 4, p. 10) 결과, Provenance Hypergraph(`M1`)와 Semantic AutoDream(`M2`)은 각각 단계적인 정확도 상승을 이끌었다.

| System | `LoCoMo` Overall | Multi-hop | Temporal | Open | Single-hop | `LongMemEval-S` |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| `Graphiti` | 58.4% | - | - | - | - | 23.0% |
| `MemoryBank` | 59.5% | - | - | - | - | 39.7% |
| `A-Mem` | 68.3% | - | - | - | - | 45.0% |
| `mem0` | 82.9% | - | - | - | - | 56.3% |
| `HyperMem` (재현)* | 91.0% | 91.1% | 84.4% | **76.0%** | **95.2%** | 58.3% |
| `MIRIX-1.0 (M0)` | 85.7% | 83.3% | 85.0% | 70.8% | 88.3% | 58.8% |
| `MIRIX-2.0 (M1, graph)` | 88.4% | 89.0% | 86.0% | 71.9% | 91.1% | 70.0% (+11.2 pts) |
| **`MIRIX-2.0 (M2, graph + dream)`** | **91.4%** | **91.8%** | **89.4%** | 74.0% | 94.1% | **72.6%** (+2.6 pts) |

*\*주: `HyperMem`은 원본 설정으로 재실행한 결과이며, `HyperMem` 원 논문의 `LoCoMo` 보고치는 92.7%이다 (p. 10–11).*

- `LoCoMo`에서 `M2`는 전체 **91.4%** 로 최고 성능을 기록했으며, 특히 `HyperMem` 대비 **Temporal(+5.0 points, 89.4% vs 84.4%)** 과 **Multi-hop(+0.7 points, 91.8% vs 91.1%)** 에서 우위를 보였다 (p. 11). 반면 `Open`(74.0% vs 76.0%) 및 `Single-hop`(94.1% vs 95.2%)에서는 `HyperMem`이 소폭 높았다 (p. 11).
- `LongMemEval-S`에서는 `M0`(58.8%) $\rightarrow$ `M1`(70.0%, **+11.2 points**) $\rightarrow$ `M2`(72.6%, **+2.6 points**)로 상승하여 최강 baseline인 `HyperMem`(58.3%)을 **+14.3 points**, `Graphiti`(23.0%)를 **+49.6 points** 앞섰다 (p. 10–11, p. 27).

---

### 7.3 User-centric Memory: Document QA (`MemoryAgentBench`) & Personalized Generation (`MemoryCD`)

장문 문서 QA(Figure 5, p. 11; Table 8, p. 27)는 AutoDream이 비활성화된 `M1`으로 평가되었다.

| System | `SHDocQA` (100문항) | `MHDocQA` (100문항) | `DetectiveQA` (71문항) |
| :--- | :---: | :---: | :---: |
| `A-Mem` | 14.0% | 20.0% | 62.0% |
| `MemoryBank` | 34.0% | 28.0% | **67.6%** |
| `mem0` | 48.0% | 22.0% | 53.5% |
| `Graphiti` | 50.0% | 43.0% | 54.9% |
| `HyperMem` | 52.0% | 49.0% | 66.2% |
| **`MIRIX-2.0 (M1)`** | **83.0%** (+31.0 vs HyperMem) | **71.0%** (+22.0 vs HyperMem) | 64.8% |

- `SHDocQA`(83.0%)와 `MHDocQA`(71.0%)에서는 원본 레코드를 유실 없이 보존하고 인용하는 설계 덕분에 `HyperMem`(52.0%, 49.0%) 대비 각각 **+31.0 points**, **+22.0 points**의 큰 격차로 1위를 차지했다 (p. 11, p. 27).
- 반면 다단계 추론 비중이 높은 `DetectiveQA`에서는 `M1`(64.8%)이 `MemoryBank`(67.6%)와 `HyperMem`(66.2%)보다 낮았다 (p. 11, p. 27).

개인화 리뷰 요약 및 생성 벤치마크인 `MemoryCD`(Table 2, p. 12; Table 9, p. 27)에서는 다음과 같은 결과를 보였다:
- **리뷰 요약 (Table 2, p. 12)**: Cross-domain 설정에서 `MIRIX-2.0`은 4개 지표 모두 1위(`ROUGE-1` `.1603`, `ROUGE-L` `.1510`, `BLEU-4` `.0209`, `BERTScore-F1` `.8754`)를 기록했다 (차순위 `RAG` `ROUGE-L` `.1430`, `Long-context` `BLEU-4` `.0162`). Single-domain 요약에서는 `ROUGE-1` `.1715`, `ROUGE-L` `.1617`, `BLEU-4` `.0234`, `BERTScore-F1` `.8779`를 기록했으며 `Long-context`가 `BLEU-4` `.0249`, `BERTScore-F1` `.8779`로 동률 또는 소폭 우위를 보였다.
- **리뷰 생성 (Table 9, p. 27)**: Cross-domain(`ROUGE-1` `.2846`, `ROUGE-L` `.1491`, `BLEU-4` `.0108`, `BERTScore-F1` `.8544`)과 Single-domain(`ROUGE-1` `.2868`, `ROUGE-L` `.1487`, `BLEU-4` `.0106`, `BERTScore-F1` `.8539`) 모두 4개 지표 전부에서 1위를 차지했으나, baseline과의 절대 격차는 작은 편이다 (p. 12, p. 27).

---

### 7.4 Agent-centric Procedural Memory (`ALFWorld`, `WebShop`, `MetaClaw-Bench`)

에이전트 상호작용 환경(Figure 6–7, p. 12–13; Table 3, p. 13; Table 10–11, p. 28)에서의 결과는 다음과 같다.

| Benchmark & Model | `No memory` / `Baseline` | `ReasoningBank` | `AWM` / `MetaClaw Skills` | `MIRIX-2.0` |
| :--- | :---: | :---: | :---: | :---: |
| `ALFWorld` (`GPT-6 Luna`) | 69.4±0.7% | 66.9±2.8% | `AWM`: 73.6±1.9% | **76.4±6.2%** (21.9/13.2 steps) |
| `ALFWorld` (`GPT-5.6 Luna`) | 54.2±1.1% | 52.2±0.7% | `AWM`: 58.0±2.8% | **61.7±14.0%** (26.8/12.4 steps) |
| `WebShop` (`GPT-6 Luna`) | 31.9±1.8% (7.9/6.2) | 25.7±3.8% (10.2/6.9) | `AWM`: 36.0±4.3% (9.1/6.9) | **37.4±2.1%** (8.2/6.3 steps) |
| `MetaClaw-Bench` (`GPT-5.2`) | 41.1% / 14.7% | - | `MetaClaw`: 44.0% / 17.1% | **49.2% / 27.7%** |
| `MetaClaw-Bench` (`Qwen3.6-35B-A3B`) | 40.3% / 12.1% | - | `MetaClaw`: 51.8% / 30.8% | **52.7% / 30.8%** |

*\*주: `ALFWorld`/`WebShop`의 step 수 표기는 `(전체 평균 steps / 성공 에피소드 평균 steps)`이며, `MetaClaw-Bench`는 `(Accuracy / 224문항 File-check Completion Rate)`이다 (p. 13, p. 28).*

- `ALFWorld`와 `WebShop` 모두에서 비교된 메모리 기법 중 최고 평균 성공률과 최소 평균 행동 스텝(메모리 기법 중 `ALFWorld` 21.9/13.2, `WebShop` 8.2/6.3)을 달성했다 (p. 12–13, p. 28). 반면 `ReasoningBank`는 두 환경 모두에서 `No memory`보다 낮은 성능(66.9%, 25.7%)을 기록했다 (p. 13, p. 28).
- `MetaClaw-Bench` Part I(346문항)에서는 `GPT-5.2` 기준 정확도 **49.2%**(vs `MetaClaw Skills` 44.0%) 및 file-check 완료율 **27.7%**(vs 17.1%, **+10.6 points**)를, Open model인 `Qwen3.6-35B-A3B`에서도 정확도 **52.7%**(vs 51.8%)로 최고 성능을 기록했다 (p. 13).

---

### 7.5 효율성, 지연시간 및 토큰 비용 (Figure 8, p. 14; Table 4, p. 14; Table 12, p. 28–29)

#### 1) User-memory Ingestion, Answer Latency, Answer Tokens & Graph Size (`M1` 기준, AutoDream 비활성화)

| Benchmark | `Ingest (s)` (M0 / M1 / HyperMem) | `Ans./q (s)` (M0 / M1 / HyperMem) | `Ans. Tokens` (M0 / M1 / HyperMem) | Neo4j Graph Size (`M1` Nodes / Edges) |
| :--- | :---: | :---: | :---: | :---: |
| `SHDocQA` | 6,227 / 23,784 / 5,665 | 3.76 / **5.45** / 10.55 | - / **1.40M** / 3.79M | 29,341 / 33,526 |
| `MHDocQA` | 13,792 / 55,301 / 11,031 | 7.46 / **9.01** / 10.65 | - / **1.33M** / 3.06M | 49,046 / 55,288 |
| `DetectiveQA` | 10,321 / 39,491 / 11,653 | 5.11 / **7.24** / 13.05 | 706K / 1.16M / **0.35M** | 36,987 / 46,651 |
| `LoCoMo` | - / **21,416** / 69,467 | - / **5.46** / 9.70 | - / **15.7M** / 16.5M | 23,820 / 32,394 |
| `LongMemEval-S` | - / **70,626** / 170,224 | - / **10.10** / 10.73 | - / **4.02M** / 4.10M | 181,131 / 205,428 |

- **Answer Latency 우위**: `M1`은 측정된 5개 벤치마크 전부에서 `HyperMem`보다 빠르게 답변했다(`5.45–10.10s` vs `9.70–13.05s`) (p. 13–14, p. 29). 다만 3개 문서 태스크에서는 Flat Vector Retrieval(`M0`: `3.76s`, `7.46s`, `5.11s`)보다 느렸다 (p. 13–14, p. 29).
- **Ingestion Trade-off**: 대화형 벤치마크(`LoCoMo` `21,416s` vs `69,467s`, `LongMemEval-S` `70,626s` vs `170,224s`)에서는 `HyperMem`보다 훨씬 빠르게 적재되었으나, 장문 문서 벤치마크에서는 그래프 추출 비용으로 인해 `M0` 대비 **3.8~4.0배**, `HyperMem` 대비 **3.4~5.0배** 느리게 적재되었다 (p. 13, p. 28–29).
- **Answer Tokens**: `DetectiveQA`(`M1` 1.16M vs `HyperMem` 0.35M, `M0` 706K)를 제외한 4개 벤치마크에서 `HyperMem`보다 적은 Answer Token을 소모했다 (p. 14, p. 29).

#### 2) Agent Procedural Memory Token Cost (`GPT-6 Luna`, Embedding 제외 3회 평균, Table 4, p. 14)

| Method | `ALFWorld` Train Tokens | `ALFWorld` Test Tokens | `WebShop` Train Tokens | `WebShop` Test Tokens |
| :--- | :---: | :---: | :---: | :---: |
| `No memory` | - | **2.147±0.048M** | - | **4.238±0.463M** |
| `ReasoningBank` | **1.747±0.122M** | 3.351±0.045M | 2.010±0.242M | 6.620±0.674M |
| `AWM` | 2.749±0.059M | 3.674±0.332M | 1.909±0.127M | 5.448±0.356M |
| `MIRIX-2.0` | 2.253±0.100M | 3.813±0.490M | **1.815±0.054M** | **4.975±0.340M** |

- `WebShop`에서는 메모리 기법 중 최소 Training(`1.815M`) 및 Test(`4.975M`) 토큰으로 최고 성공률(37.4%)을 달성했다 (p. 14). 반면 `ALFWorld`에서는 행동 스텝 수는 가장 적었으나(21.9 steps), 매 스텝 프롬프트에 Skill이 주입되면서 Test 토큰(`3.813M`)이 메모리 기법 중 가장 높았다 (p. 14).

---

## 8. Limitation

1. **불완전한 Ablation Study (p. 9, p. 12–13, p. 15–16)**: 실험이 `M0`(flat), `M1`(+hypergraph), `M2`(+AutoDream)의 거친 그룹 단위로만 수행되어, n-ary frame, 인용 원문 row, occurrence identity, answer-time verification, procedural distiller/curator, 6-writer routing 각각의 개별 기여도를 분리 검증하지 못했다. 또한 `MemoryCD`와 `MetaClaw-Bench`에서는 구성 요소별 ablation 자체가 수행되지 않았다.
2. **Ingestion 병목 및 `M2` 전체 비용·Scaling 미측정 (p. 4, p. 13–15, p. 28)**: 장문 문서 QA 적재 시 `M1`의 Ingestion 시간이 `M0` 대비 3.8~4.0배 느리며 Answer Latency도 증가한다. 더욱이 Table 12와 Figure 8의 모든 시간·토큰 수치는 AutoDream이 꺼진 **`M1` 기준**이므로, `M2`의 추가 LLM 검증 비용과 그래프 축소·지연시간 변화가 보고되지 않았다. 히스토리 길이 증가에 따른 실제 Latency Scaling, User-memory Ingestion Token, 다중 태스크 호라이즌에서의 학습 비용 상각(amortization) 곡선 역시 측정되지 않았다.
3. **높은 분산, 단일 실행 및 비통제 Baseline 비교 (p. 10, p. 13–14, p. 16)**: User-memory 평가는 단일 실행(single runs)에 기반하며 `LoCoMo`/`LongMemEval-S`는 `gpt-4o-mini` LLM judge에 의존한다. `ALFWorld` 3회 실행에서는 표준편차가 `GPT-6 Luna`에서 ±6.2 points(`76.4±6.2`), `GPT-5.6 Luna`에서 ±14.0 points(`61.7±14.0`)로 매우 커 `AWM`(`73.6±1.9%`) 대비 안정적 우위를 단정하기 어렵다. `MetaClaw-Bench`의 `GPT-5.2` baseline 수치는 자체 재실행이 아닌 원 논문 보고치를 인용한 비통제 비교이다.
4. **특정 태스크의 성능 역전 및 Token Overhead (p. 11–14, p. 28)**: `DetectiveQA`에서 `M1`(64.8%)은 `MemoryBank`(67.6%)와 `HyperMem`(66.2%)보다 낮았으면서도 Answer Token은 `HyperMem`(0.35M)의 약 3.3배인 1.16M을 소모했다. `ALFWorld`(`GPT-6 Luna`)의 `Pick`, `Look`, `Pick Two` 등 일부 하위 태스크에서는 `No memory`보다 성공률이 낮았으며, Test Token(3.813M)이 메모리 기법 중 최고치를 기록했다.
5. **보안 및 프라이버시 한계 (p. 16)**: `Knowledge Vault` 분리 저장만으로는 사용자 동의(consent) 기반 접근 제어를 대체할 수 없으며, 대화 중 유입된 비신뢰·악성 콘텐츠(memory poisoning)가 저장된 후 재검색될 위험이 남아 있다.

---

## 9. Takeaways

1. **System of Record와 Index의 분리 설계**: 원본 레코드를 먼저 Vector Database(`Typed Evidence Plane`)에 커밋하고 `Provenance Hypergraph`를 색인 계층으로만 두는 구조는, 그래프 쓰기 실패 시 메모리 유실을 막는 Fail-soft 특성과 읽기 실패 시 직접 검색 Fallback을 동시에 확보하는 견고한 엔지니어링 패턴이다 (p. 2, p. 5–6).
2. **비파괴적 Offline Consolidation (`AutoDream`)의 효용**: 원본 텍스트를 LLM으로 요약·덮어쓰기하지 않고 Hypergraph 인덱스 계층에서만 동일 엔티티 Anchor를 병합하며, Frontier 기반 후보 탐색($O(|\Delta_u|\log|\mathcal{A}_u|)$)과 Negative Decision 캐싱 및 라운드별 호출 상한으로 비용을 제어하는 전략이 정보 손실 없이 검색 정확도를 높였다(`LoCoMo` `M1` 88.4% $\rightarrow$ `M2` 91.4%) (p. 8–9, p. 11, p. 24–25).
3. **Procedural Memory의 양날의 검**: `ReasoningBank`가 `ALFWorld`(66.9% vs `No memory` 69.4%)와 `WebShop`(25.7% vs 31.9%)에서 오히려 성능을 하락시킨 사례는 검증 없는 경험 축적이 해가 될 수 있음을 시사한다 (p. 13, p. 28). 또한 MIRIX-2.0이 `ALFWorld`에서 행동 스텝은 줄였으나 매 스텝 Skill 프롬프트 주입으로 Test Token(`3.813M`)이 증가한 점은, 스텝 감소가 곧 토큰 절감을 의미하지 않음을 보여준다 (p. 14).
4. **모델 비종속적 아키텍처의 실용성**: 핵심 안전성 불변량을 결정론적 코드로 강제함으로써 Fine-tuning 없이 다양한 Closed/Open 모델(`gpt-4.1-mini`, `GPT-6 Luna`, `GPT-5.2`, `Qwen3.6-35B-A3B`)에 배포할 수 있어 프로덕션 모델 교체 유연성이 높다 (p. 1, p. 4, p. 10, p. 13, p. 25).

---

## 10. Critical Analysis

MIRIX-2.0은 장기 메모리 시스템의 세 축인 Accuracy, Practicality, Efficiency를 해결하기 위해 **Typed Evidence Plane + Provenance Hypergraph + 비파괴적 AutoDream**이라는 설득력 있는 계층 분리 아키텍처를 제시하고, `LoCoMo`(91.4%), `LongMemEval-S`(72.6%), `SHDocQA`/`MHDocQA`(83.0%/71.0%) 등에서 괄목할 정량 성과를 거두었다 (p. 2, p. 4, p. 11). 그러나 논문의 핵심 주장과 실증 데이터 사이에는 다음과 같은 구조적·실험적 간극이 존재한다.

1. **"Bounded Access"와 실제 효율성 주장 간의 간극**: 논문은 명시적 Row Budget $(B_q^B, B_q^R)$을 통해 히스토리 길이에 관계없이 검사 증거량이 유계임을 강조한다 (p. 1, p. 7). 그러나 이는 Post-retrieval 단계의 증거 개수 상한일 뿐, 히스토리 증가에 따른 실제 End-to-End Latency Scaling 측정이 누락되어 있으며 (p. 4), 장문 문서 적재(Ingestion)에서는 `M0` 대비 **3.8~4.0배**의 심각한 시간 병목이 발생한다 (p. 13, p. 29). 더구나 모든 효율성 수치(Table 12)가 AutoDream을 끈 `M1` 기준이므로, 논문 대표 수치인 `M2`의 전체 비용 대비 효과는 아직 온전히 검증되지 않았다 (p. 4, p. 14, p. 28).
2. **"One Memory for Users and Agents"의 결합 깊이**: 단일 저장소에서 User-centric Memory와 Agent-centric Memory를 모두 지원한다는 점을 주요 기여로 내세우지만 (p. 2, p. 4), 실제 구현에서 `Procedural` Memory의 Skill 검색은 `Semantic` Hypergraph와 독립적으로 동작한다 (p. 5, p. 10). 즉, 두 메모리가 물리적 프레임워크를 공유할 뿐 User-centric 지식과 Agent-centric Skill 간의 상호 보완적 시너지를 검증하는 통합 시나리오는 실험되지 않았다.
3. **고수준 추론 및 적응형 Skill Gating의 한계**: `DetectiveQA`에서 원문 row를 충실히 보존함에도 `MemoryBank`(67.6%)나 `HyperMem`(66.2%)보다 낮은 64.8%에 그치고 토큰은 3.3배 소모한 결과(p. 11, p. 14)는, 정확한 근거 회수가 곧 복잡한 다단계 추론 성능으로 직결되지 않음을 드러낸다. 마찬가지로 `ALFWorld`에서 특정 기본 태스크(`Pick`, `Look`, `Pick Two`) 성능이 `No memory`보다 낮아지고 높은 분산(±6.2 / ±14.0)을 보인 점(p. 12–13)은, 모든 스텝에 일괄적으로 Skill을 주입하기보다 태스크 난이도와 모델 역량에 따라 Skill 검색·주입 여부를 결정하는 **Adaptive Skill Gating** 메커니즘과 경량 인덱싱이 후속 연구로 필수적임을 시사한다 (p. 12–13, p. 15–16).
