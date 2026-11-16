# KBLaM: Knowledge Base augmented Language Model — 심층 분석 보고서

- **논문 정보**: *KBLaM: Knowledge Base augmented Language Model* (arXiv:2410.10450, 2024-10-14 / ICLR 2025)
- **저자**: Xi Wang, Taketomo Isazawa, Liana Mikaelyan, James Hensman
- **원문 및 코드**: [arXiv PDF](https://arxiv.org/pdf/2410.10450.pdf) · [GitHub Repository (`microsoft/KBLaM`)](https://github.com/microsoft/KBLaM/)

---

## 1. Overview

**KBLaM (Knowledge Base augmented Language Model)**은 외부 문서 코퍼스로부터 구축된 **Knowledge Base (KB)**의 지식 트리플을 Pre-trained Sentence Encoder와 Linear Adapter를 통해 연속적인 Key-Value 벡터 쌍인 **Knowledge Token**으로 변환하고, 이를 **Rectangular Attention** 메커니즘을 통해 Pre-trained Large Language Model (LLM)의 각 어텐션 레이어에 직접 통합하는 외부 지식 증강 방법론이다(p.1–2).

기존의 Retrieval-Augmented Generation (RAG)과 달리 외부 Retriever 모듈을 제거하여 End-to-End로 지식을 참조하며, In-Context Learning (ICL)과 달리 연산 및 메모리 오버헤드가 KB 크기 $M$에 대해 이차적이 아닌 **선형($\mathcal{O}(M)$)**으로 증가한다(p.1, p.5–6). 이를 통해 단일 A100 80GB GPU 환경에서 **8K context window**를 가진 **8B Pre-trained LLM**에 **10K 이상의 KB triples**을 통합할 수 있으며, 모델의 Fine-tuning이나 재학습 없이도 개별 지식의 동적 업데이트와 어텐션 스코어 기반의 **Interpretability**를 제공한다(p.1–2, p.6–7).

---

## 2. Background

LLM에 외부 도메인 지식을 통합하는 기존 접근법은 크게 세 가지 범주로 구분된다(p.1).

1. **Supervised Fine-Tuning (SFT)**: 도메인 데이터를 이용해 모델 파라미터를 직접 수정(또는 LoRA/DoRA 등 PEFT 적용)하여 지식을 가중치 내부에 내재화한다(p.1, p.7, p.16–17).
2. **Retrieval-Augmented Generation (RAG)**: 비정형 문서 코퍼스에서 외부 Retriever 모듈(예: BM25 또는 Dense Retriever)을 이용해 질의와 관련된 텍스트 청크를 검색한 뒤, 이를 프롬프트에 결합하여 LLM에 입력한다(p.1–2, p.15–16).
3. **In-Context Learning (ICL)**: Long-Context LLM의 확장된 컨텍스트 윈도우를 활용하여 전체 외부 코퍼스나 KB를 텍스트 형태로 프롬프트에 직접 입력하고 표준 Causal Self-Attention으로 추론한다(p.1–2).

---

## 3. Problem

기존 세 가지 접근법은 대규모·동적 KB 환경에서 다음과 같은 구조적 한계를 가진다.

- **SFT의 재학습 비용 및 망각 문제**: 지식이 추가되거나 수정될 때마다 모델 가중치를 재학습해야 하며, 기존 일반화 능력을 손상시키는 **Catastrophic Forgetting** 및 학습 데이터 암기(memorization) 위험을 유발한다(p.1, p.7).
- **RAG의 2단계 파이프라인 의존성**: 별도의 외부 Retriever 모듈과 LLM이 분리되어 있어 End-to-End 최적화가 어렵고, 검색된 텍스트 청크 길이만큼 LLM의 입력 컨텍스트 길이 및 Feed-Forward Network (FFN) 연산량이 증가한다(p.1–2, p.16).
- **ICL의 이차 복잡도 및 동적 업데이트 불가**: 전체 KB를 프롬프트에 삽입할 경우 토큰 길이 $N$에 대해 시간 및 메모리 복잡도가 이차적($\mathcal{O}(N^2)$, 평균 길이 $K$인 $M$개 트리플 기준 $(KM)^2$)으로 폭증한다(p.1, p.4, p.6). 실제 80GB A100 GPU에서도 약 **200 triples** 수준에서 Out-Of-Memory (OOM) 한계에 도달한다(p.6, p.8). 또한 표준 KV Cache는 Causal Self-Attention에 따른 토큰 간 의존성으로 인해 앞선 컨텍스트 일부만 변경되어도 전체 KV Cache를 재계산해야 하며, 컨텍스트 순서에 따른 **Positional Bias**, KB 크기 증가 시 답변 가능 질문까지 거절하는 **Over-refusal**, 그리고 어텐션 행렬 해석의 어려움이 발생한다(p.1–3, p.6, p.9–10).

---

## 4. Motivation

KBLaM은 KB를 구성하는 서로 다른 `<name>`과 `<property>`의 트리플이 **상호 독립적인 정보 단위**라는 구조적 특성에 주목한다(p.2).

- **독립성을 활용한 선형 확장 및 동적 업데이트**: KB 트리플 간 Self-Attention을 배제하고 프롬프트 토큰만 KB를 단방향으로 참조하도록 설계하면, 별도의 Retriever 없이도 End-to-End 지식을 통합하면서 계산·메모리 오버헤드를 KB 크기 $M$에 대해 선형($\mathcal{O}(M)$)으로 줄일 수 있다(p.2, p.5–6). 동시에 트리플마다 독립 인코딩된 Knowledge Token을 사용하므로 전체 KV Cache 재계산이나 파인튜닝 없이 개별 트리플의 추가·수정·삭제가 가능하고, 어텐션 스코어를 통한 근거 추적(Interpretability)이 자연스럽게 확보된다(p.2–3, p.6–7).
- **합성 데이터 기반 Projection 학습**: Base LLM의 가중치를 완전히 동결(freeze)한 채 Pre-trained Sentence Encoder 공간($\mathbb{R}^P$)과 LLM 임베딩 공간($\mathbb{R}^D$) 사이의 선형 투영(Projection)만 학습하면, LLM이 학습 데이터를 암기할 위험 없이 순수 **Synthetic KB Instruction Tuning**만으로도 실제 Out-of-Distribution (OOD) 데이터(예: Enron email KB)에 일반화할 수 있다(p.2–3, p.6–8).

---

## 5. Contribution

1. **Knowledge Token 인코딩**: KB의 각 트리플 `(<name>, <property>, <value>)`을 Pre-trained Sentence Encoder와 Linear Adapter를 통해 단일 LLM 토큰의 KV Cache와 동일한 차원 $D$를 갖는 연속적 Key-Value 벡터 쌍으로 변환한다(p.2, p.4–5).
2. **Rectangular Attention 메커니즘**: Knowledge Token 간 Self-Attention을 배제하고 프롬프트 토큰만 Knowledge Token을 참조하도록 하여, 메모리 복잡도 $\mathcal{O}((M+N)N)$ 및 시간 복잡도 $\mathcal{O}((M+N)ND)$를 KB 크기 $M$에 대해 선형으로 유지하고 FFN 오버헤드를 증가시키지 않는다(p.5–6).
3. **동적 지식 업데이트 및 KB 길이 일반화**: Knowledge Token의 독립성 덕분에 KV Cache 재계산이나 파인튜닝 없이 개별 트리플의 추가·수정·삭제가 가능하며, 추론 시 Attention Score Scaling($\log C - \log M$, $C=100$)을 적용하여 학습 시보다 훨씬 큰 대규모 KB로 일반화한다(p.2, p.6).
4. **합성 데이터 기반 KB Instruction Tuning 및 자원 공개**: 순수 합성 데이터(120K 학습 트리플)로 Linear Adapter와 Query Head만 학습하여 실제 OOD 데이터(Enron email KB)로 일반화하며, 8K context window의 8B LLM에 10K 이상의 트리플을 단일 A100 80GB GPU에서 통합함을 입증하고 코드와 데이터셋(`https://github.com/microsoft/KBLaM/`)을 공개했다(p.1, p.3–4, p.8).

---

## 6. Method

### 6.1 전체 아키텍처 파이프라인
KBLaM은 비정형 문서 코퍼스에서 추출된 구조화 KB 트리플 집합 $\{(<\text{name}>_m, <\text{property}>_m, <\text{value}>_m)\}_{m=1}^M$ (Eq. (1), p.3, p.5)을 Pre-trained LLM(실험 기준 instruction fine-tuned Llama3 8B, $L=32$ layers, 8K context window, p.1, p.8)의 각 Self-Attention 레이어에 직접 주입한다(p.1–2, p.4–6). 전체 구조는 오프라인 인코딩 단계(Step 1), 추론 통합 단계(Step 2), 그리고 어댑터 학습 프로토콜로 구성된다(Fig. 2, p.5).

#### 기준 연산: 표준 Self-Attention
표준 Decoder-based Transformer의 $l \in \{1, \dots, L\}$번째 레이어에서 $n$번째 프롬프트 토큰 임베딩 $\boldsymbol{x}_n^l \in \mathbb{R}^D$에 대한 QKV 투영 및 Causal Self-Attention 출력은 다음과 같이 정의된다(p.4).

$$\boldsymbol{q}_n^l = \boldsymbol{W}_Q^l \boldsymbol{x}_n^l, \qquad \boldsymbol{k}_n^l = \boldsymbol{W}_K^l \boldsymbol{x}_n^l, \qquad \boldsymbol{v}_n^l = \boldsymbol{W}_V^l \boldsymbol{x}_n^l \tag{2}$$

$$\boldsymbol{y}_n^l = \frac{\sum_{i=1}^n \exp(w_{n,i}) \boldsymbol{v}_i^l}{\sum_{i=1}^n \exp(w_{n,i})}, \quad w_{n,i} = \langle \boldsymbol{q}_n^l, \boldsymbol{k}_i^l \rangle / \sqrt{D} \tag{3}$$

여기서 $\boldsymbol{W}_Q^l, \boldsymbol{W}_K^l, \boldsymbol{W}_V^l \in \mathbb{R}^{D \times D}$는 Pre-trained 투영 행렬, $D$는 단일 헤드 기준 임베딩 차원, $N$은 프롬프트 토큰 수이며, 표준 Self-Attention은 시간 복잡도 $\mathcal{O}(N^2 D)$, 어텐션 행렬 메모리 복잡도 $\mathcal{O}(N^2)$, FFN 연산량 $\mathcal{O}(N D^2)$를 가진다(p.4).

---

### 6.2 Step 1 — Knowledge Token Encoding (Sentence Encoder + Linear Adapters)
각 KB 트리플의 식별자(`"The <property>_m of <name>_m"`)와 내용(`"<value>_m"`)을 분리하여 동결된 Pre-trained Sentence Encoder $f(\cdot)$ (기본 OpenAI `ada-002`, $P=1536$, p.8)로 $P$차원 Base Embedding을 생성한다(p.4).

$$\mathbf{k}_m = f(\text{The }\texttt{<property>}_m\text{ of }\texttt{<name>}_m) \in \mathbb{R}^P, \qquad \mathbf{v}_m = f(\texttt{<value>}_m) \in \mathbb{R}^P \tag{4}$$

이후 무작위 초기화(random initialization) 후 학습되는 Linear Key/Value Adapter $\tilde{\boldsymbol{W}}_K, \tilde{\boldsymbol{W}}_V \in \mathbb{R}^{L \times D \times P}$를 통해 LLM의 모든 $L$개 레이어에 대응하는 Knowledge Token $(\tilde{\boldsymbol{k}}_m, \tilde{\boldsymbol{v}}_m)$으로 사영하여 디스크에 사전 저장한다(p.4–5, p.8).

$$\tilde{\boldsymbol{W}}_K \in \mathbb{R}^{L \times D \times P}, \qquad \tilde{\boldsymbol{W}}_V \in \mathbb{R}^{L \times D \times P} \tag{5}$$

$$\tilde{\boldsymbol{k}}_m = [\tilde{\boldsymbol{k}}_m^1, \dots, \tilde{\boldsymbol{k}}_m^l, \dots, \tilde{\boldsymbol{k}}_m^L]^\top = \tilde{\boldsymbol{W}}_K \mathbf{k}_m \in \mathbb{R}^{L \times D} \tag{6}$$

$$\tilde{\boldsymbol{v}}_m = [\tilde{\boldsymbol{v}}_m^1, \dots, \tilde{\boldsymbol{v}}_m^l, \dots, \tilde{\boldsymbol{v}}_m^L]^\top = \tilde{\boldsymbol{W}}_V \mathbf{v}_m \in \mathbb{R}^{L \times D} \tag{7}$$

$$\{(\texttt{<name>}_m, \texttt{<property>}_m, \texttt{<value>}_m)\}_{m=1}^M \xrightarrow{\text{Encode}} \{(\tilde{\boldsymbol{k}}_m, \tilde{\boldsymbol{v}}_m)\}_{m=1}^M \tag{8}$$

- **설계 의의**: 각 레이어의 $\tilde{\boldsymbol{k}}_m^l, \tilde{\boldsymbol{v}}_m^l \in \mathbb{R}^D$는 단일 LLM 토큰의 KV Cache와 동일한 차원을 가진다(p.4–5). 트리플들이 서로 독립적으로 인코딩되므로 KB 내 항목 추가·수정·삭제 시 전체 KV Cache를 재계산할 필요 없이 해당 Knowledge Token만 개별적으로 갱신하면 된다(p.3, p.5–6).

---

### 6.3 Step 2 — Rectangular Attention & KB Length Generalization
추론 시 레이어 $l$에서는 로드된 $M$개의 Knowledge Token $\{(\tilde{\boldsymbol{k}}_m^l, \tilde{\boldsymbol{v}}_m^l)\}_{m=1}^M$과 $N$개의 프롬프트 토큰을 단일 Softmax 분모 아래 결합한 **Rectangular Attention**을 수행한다(p.5–6). 이때 프롬프트 토큰이 KB Key를 조회하기 위해 Pre-trained $\boldsymbol{W}_Q^l$에서 초기화된 별도의 학습 가능 Query Head $\tilde{\boldsymbol{W}}_Q^l \in \mathbb{R}^{D \times D}$를 사용한다(p.5, p.8).

$$\tilde{\boldsymbol{y}}_n^l = \frac{\sum_{m=1}^M \exp(\tilde{w}_{n,m}^l) \tilde{\boldsymbol{v}}_m^l + \sum_{i=1}^n \exp(w_{n,i}^l) \boldsymbol{v}_i^l}{\sum_{m=1}^M \exp(\tilde{w}_{n,m}^l) + \sum_{i=1}^n \exp(w_{n,i}^l)} \tag{9}$$

$$\tilde{w}_{n,m}^l = \langle \tilde{\boldsymbol{q}}_n^l, \tilde{\boldsymbol{k}}_m^l \rangle / \sqrt{D}, \quad \tilde{\boldsymbol{q}}_n^l = \tilde{\boldsymbol{W}}_Q^l \boldsymbol{x}_n^l, \quad w_{n,i}^l = \langle \boldsymbol{q}_n^l, \boldsymbol{k}_i^l \rangle / \sqrt{D} \tag{10}$$

- **구조적 특성**: Knowledge Token들은 Query 임베딩을 갖지 않고 상호 어텐션도 수행하지 않으므로 어텐션 행렬이 $(M+N) \times N$ 크기의 직사각형(rectangular) 형태를 띤다(Fig. 2b, p.5–6). 또한 $M=0$일 때 Eq. (9)는 표준 Self-Attention(Eq. (3))으로 정확히 환원되어 원래 LLM의 동작을 보존하며, Knowledge Token 간 위치 순서가 없어 ICL의 Positional Bias가 발생하지 않는다(p.6). 출력 $\tilde{\boldsymbol{y}}^l \in \mathbb{R}^{N \times D}$는 시퀀스 길이가 $N$으로 고정되어 있어 Pre-trained FFN 연산량($\mathcal{O}(ND^2)$)에 추가 오버헤드가 없다(p.5–6, p.16).

#### 추론 시 KB 크기 확장을 위한 Attention Score Scaling
추론 시 KB 크기 $M$이 학습 시 최대 크기(실험에서 $C=100$)를 훨씬 넘어설 때(예: 10K+ triples), Eq. (9)의 분자·분모에서 $M$개 KB 항의 합이 $n$개 프롬프트 항을 압도해 질문 맥락이 소실되는 현상을 막기 위해 추론 시 다음과 같이 로그 오프셋 스케일링을 적용한다(p.6).

$$\tilde{w}_{n,m}^l = \log C - \log M + \langle \tilde{\boldsymbol{q}}_n^l, \tilde{\boldsymbol{k}}_m^l \rangle / \sqrt{D} \tag{11}$$

지수 연산 시 $\exp(\log C - \log M) = C/M$ 배율로 작용하여 KB 규모 $M$이 커져도 KB 전체의 기여 총합이 $C$ 기준으로 일정하게 정규화된다(p.6).

---

### 6.4 KB Instruction Tuning 및 Implicit Retrieval 메커니즘
Base LLM의 모든 가중치 $\phi$(QKV heads, FFN, embedding/output weights)를 완전히 동결(freeze)한 채, Linear Adapter와 KB용 Query Head 파라미터 $\theta$만 Synthetic KB 기반 Q&A 쌍 $(Q, A)$에 대해 Instruction Tuning을 수행한다(p.6–7).

$$\max_{\theta} \log p_{\theta, \phi}(A \mid Q, \text{KB}), \quad \theta = \left\{ \tilde{\boldsymbol{W}}_K, \tilde{\boldsymbol{W}}_V, \{\tilde{\boldsymbol{W}}_Q^l\}_{l=1}^L \right\} \tag{12}$$

- **합성 데이터 설계**: 이름과 내용 간 상관성이 낮도록 설계된 GPT-4 기반 100% Synthetic KB(120K 학습 트리플)에서 샘플당 $M \in [10, 100]$개 트리플을 추출해 4가지 유형의 Q&A(Simple, Multi-entities, Open-ended, Unanswerable)를 생성한다(p.6–8, p.20). Unanswerable Q&A를 포함시켜 KB에 근거가 없을 때 거절 응답을 생성하도록 학습시킨다(p.7–8).
- **계층별 기능 분화**: 별도의 Retrieval 손실 항이나 어텐션 정규화 없이도 중간 레이어(32층 Llama3 8B 기준 **15th layer**, 32개 헤드 평균 Post-softmax score $\tilde{w}_{n,m}^{15}$)가 정확한 **Implicit Retriever**이자 해석 가능한 근거로 기능한다(p.7–9, Fig. 4–5). 반면 초기 레이어의 Knowledge Token은 엔티티 간 분산이 작아 **Soft Instruction Prompt** 역할을 수행한다(p.9, p.15–16, Fig. 9).

---

### 6.5 복잡도 및 기존 연구 대비 비교

| 비교 축 | Standard Self-Attention (ICL) | RAG (External Retriever + LLM) | KBLaM (Rectangular Attention) |
| :--- | :--- | :--- | :--- |
| **Time / Memory Complexity** | Time $\mathcal{O}(N^2 D)$, Memory $\mathcal{O}(N^2)$ (트리플 $M$개 기준 $(KM)^2$, p.4, p.6) | Retriever 비용 + 검색된 Top-$K$ 청크 길이만큼 LLM Self-Attention/FFN 증가 (p.2, p.16) | **Time $\mathcal{O}((M+N)ND)$, Memory $\mathcal{O}((M+N)N)$** ($M \gg N$에서 $\mathcal{O}(M)$ 선형, FFN 불변, p.5–6) |
| **80GB A100 최대 수용량** | 약 200 triples에서 OOM (p.6, p.8) | 인덱스 규모에 의존 (512 triples 기준 $K=5$ RAG와 동등 메모리, p.16–17) | **10K+ triples** 단일 GPU 수용 (~45 GB at $10^4$, Fig. 3, p.1, p.6) |
| **동적 KB 업데이트** | KV Cache 전체 재계산 필요 (p.2–3, p.6) | 인덱스 부분 갱신 가능 | **개별 Knowledge Token만 재계산 없이 추가·수정·삭제** (p.3, p.6) |
| **Positional Bias & 순서 불변성** | 위치에 따른 Positional Bias 존재 (p.6) | 컨텍스트 내 청크 배치 순서 영향 | **Order-invariant** (위치 임베딩 미사용, p.6) |
| **기존 파라미터 학습 여부** | 불필요 (Zero training) | Retriever/LLM 동결 가능 (2단계) | Base LLM $\phi$ 동결, **Adapter $\theta$ 1회 학습 (24–48시간, p.8, p.16)** |

기존 **Cross-Attention** 기반 방법론(Vaswani, 2017; RETRO; CEPE; Yen et al., 2024)이 별도 Cross-Attention 레이어를 추가하는 것과 달리, KBLaM은 새 레이어를 추가하지 않고 기존 Self-Attention 내부에서 단일 Softmax 분모로 통합한다(Eq. (9), p.6, p.14). 또한 모델 전체를 처음부터 학습해야 하는 Memorizing Transformers(Wu et al., 2022)나 LLM Fine-tuning을 요구하는 ICAE(Ge et al., 2024)와 달리 Base LLM을 완전히 동결한 채 대규모 Factual KB 증강을 달성한다(p.14).

---

## 7. Experiment

### 7.1 실험 설정 (Experimental Setup)
- **Backbone LLM & Sentence Encoder**: Instruction fine-tuned **Llama3 8B** (32 attention layers, 32 attention heads, 8K context window)를 백본으로 사용하고, Sentence Encoder로 **OpenAI `ada-002`** ($P=1536$)를 사용한다(p.1, p.8 Sec. 6.1–6.2). Base LLM 가중치는 동결하며 Linear Key/Value Adapters(무작위 초기화)와 각 레이어의 추가 Query Head(Pre-trained $\boldsymbol{W}_Q^l$로 초기화)만 학습한다(p.8).
- **학습 하이퍼파라미터**: `AdamW` Optimizer ($\text{lr}=5 \times 10^{-4} \to 5 \times 10^{-6}$, cosine decay), 20K iterations, mini-batch 크기 400 Q&A pairs (20 micro-batches $\times$ 20 samples: Simple 6, Multi-entities 6, Open-ended 6, Unanswerable 2), 단일 80GB A100 GPU에서 `bfloat16` 정밀도로 24–48시간 학습한다(p.8, p.16). 추론 시 Attention Score Scaling 상수는 학습 시 최대 트리플 수인 $C=100$으로 고정한다(p.6 Eq. (11)).
- **데이터셋**: GPT로 생성한 **Synthetic KB** (총 ~135K triples: 학습 120K, 검증 15,000 triples; 학습 샘플당 10~100 triples 사용)와 실제 기업 이메일 기반의 OOD 데이터셋 **Enron KB**를 사용한다(p.7 Sec. 5, p.8 Sec. 6.1).
- **Baselines & 평가지표**: **ICL** (80GB A100 메모리 한계로 최대 200 triples까지 실험)과 **Zero-shot Learning**을 비교군으로 사용하며, 5 random seeds $\times$ 100 test samples = 총 500개 질문 평균으로 평가한다(p.8–10). 평가지표는 Simple / Two-entity Q&A에 **BERT Score (F1)**, Open-ended Q&A에 **GPT-4 score (0~5점)**, Unanswerable 탐지에 **Precision / Recall** (100개 질문 중 answerable 80개=음성, unanswerable 20개=양성)을 사용한다(p.8–10).

### 7.2 주요 실험 결과 및 검증 수치
논문의 메인 실험 결과는 테이블이 아닌 Figure 3–6 플롯으로 제시되며, 본문 명시 수치와 플롯 판독 수치(plot reading)를 구분하여 정리하면 다음과 같다.

#### Table 1. 메모리 확장성 및 지연시간 비교 (Figure 3, p.6, p.8; Figure 11, p.16–17)

| 평가 항목 | 모델 / 방법론 | KB 규모 ($M$) | 검증 결과 (본문 명시 / 플롯 판독) | 출처 |
| :--- | :--- | :--- | :--- | :--- |
| **GPU 메모리 한계** | Zero-shot | - | ~21–22 GB (일정, 플롯 판독) | Fig. 3 (p.6) |
| **GPU 메모리 한계** | ICL | 최대 **200 triples** | ~75–80 GB에서 OOM 한계 도달 (본문 명시 200 triples) | Fig. 3 (p.6), p.8 |
| **GPU 메모리 확장성** | **KBLaM** | $10^3$ triples | ~21–22 GB (플롯 판독) | Fig. 3 (p.6) |
| **GPU 메모리 확장성** | **KBLaM** | **$10^4$ (10,000) triples** | **~45 GB** (단일 80GB A100 GPU 내 동작, 플롯 판독) | Fig. 3 (p.6) |
| **지연시간 & 메모리** | KBLaM vs. RAG ($K=5$) | 512 triples (40 random queries 평균) | 512 triples 기준 KBLaM과 Top-5 RAG 메모리 동등; 토큰 생성 latency는 테스트된 전 구간에서 KBLaM이 일관되게 더 낮음 | Fig. 11 (p.16–17) |

#### Table 2. Implicit Retrieval 정확도 및 아키텍처 Ablation (Figure 4, p.7; Figure 5, p.9; Figures 7–10, p.15–16)

| 평가 항목 | 데이터셋 / 조건 | KB 규모 ($M$) | 검증 수치 (플롯 판독) | 비고 |
| :--- | :--- | :--- | :--- | :--- |
| **Toy KB Attention 시각화** | 10 triples (index 0–9) | $10$ triples | 15th layer (32 heads 평균) 관련 항목 피크 가중치 **~0.35–0.4** (colorbar 0.0–0.4) | Fig. 4 (p.7) |
| **Synthetic Retrieval** | Top-1 / Top-5 Acc | $10^2 \to 10^3 \to 10^4$ | Top-1: **>0.90 $\to$ ~0.90 $\to$ ~0.82** / Top-5: **~0.98–1.00 $\to$ ~0.96 $\to$ ~0.91** | Fig. 5 (p.9) |
| **Enron (OOD) Retrieval** | Top-1 / Top-5 Acc | $10^2 \to 10^3 \to 10^4$ | Top-1: **~0.83 $\to$ ~0.62 $\to$ ~0.41** / Top-5: **~0.93 $\to$ ~0.84 $\to$ ~0.68** | Fig. 5 (p.9) |
| **vs. BM25 (Perturbed KB)** | 엔티티명 변형 질의 | 전 구간 | 어휘 정확 매칭 시 BM25 Top-1 우위이나, 변형된 Perturbed Synthetic KB에서는 모든 $M$에서 **KBLaM이 BM25 능가** | Fig. 10 (p.15–16) |
| **Encoder 용량 Ablation** | `all-MiniLM-L6-v2` ($P=384$), `all-mpnet-base-v2` ($P=768$) vs. `ada-002` ($P=1536$), `text-embedding-large` ($P=3072$) | - | 오픈소스 소형 인코더 대비 고차원 상용 인코더(`ada-002`, `text-embedding-large`) 성능 우위 | Fig. 7 (p.15) |
| **레이어 주입 간격 ($K$)** | $K=1$ vs. $K=3$ vs. $K=10$ | - | $K=3$은 BERT F1 대등하나 Refusal Precision 하락; $K=10$은 답변 품질 저하 및 거절 응답 생성 완전 실패 | Fig. 8–9 (p.9, p.15–16) |

#### Table 3. 다운스트림 Q&A 및 Unanswerable 탐지 성능 (Figure 6a, 6b, 6c, p.10)

| 태스크 (평가지표) | 데이터셋 | Zero-shot | ICL ($\le 200$ triples) | KBLaM ($M$ 확장 추이, 플롯 판독) |
| :--- | :--- | :--- | :--- | :--- |
| **Simple Q&A (BERT Score F1)** | Synthetic (1 entity) | ~0.61–0.62 | ~0.95–0.96 | **~0.91–0.92 ($10^2$) $\to$ ~0.86 ($10^3$) $\to$ ~0.73 ($10^4$)** |
| **Two-entity Q&A (BERT Score F1)** | Synthetic (2 entities) | ~0.60 | ~0.91–0.92 | **~0.90 ($10^2$) $\to$ ~0.86 ($10^3$) $\to$ ~0.75 ($10^4$)** |
| **Simple Q&A (BERT Score F1)** | Enron (OOD, 1 entity) | ~0.60 | 생략 | **~0.67 ($10^2$) $\to$ ~0.64 ($10^3$) $\to$ ~0.62 ($10^4$)** |
| **Two-entity Q&A (BERT Score F1)** | Enron (OOD, 2 entities) | ~0.50 | 생략 | **~0.66 ($<10^2$) $\to$ ~0.63 ($10^3$) $\to$ ~0.59 ($10^4$)** |
| **Open-ended Q&A (GPT-4 score, 0–5)** | Synthetic | ~0.6–0.7 | ~4.8 | **~4.6–4.7 ($\le 10^2$) $\to$ ~4.2 ($10^3$) $\to$ ~2.8 ($10^4$)** |
| **Open-ended Q&A (GPT-4 score, 0–5)** | Enron (OOD) | ~2.2 | ~4.1–4.3 | **~3.6 ($<10^2$) $\to$ ~2.8 ($10^2$) $\to$ ~2.3 ($10^3$) $\to$ ~1.7 ($10^4$)** |
| **Unanswerable 탐지 (Precision / Recall)** | Synthetic (100 Q: 80 Ans / 20 Unans) | - | Prec: ~0.95 $\to$ **~0.48** (~200 triples)<br>Rec: ~0.88–1.00 ($\le 200$) | Prec: ~0.95 $\to$ **~0.77** (~200) $\to$ **~0.42** (500) $\to$ **~0.34** (1,000)<br>Rec: **~0.83–0.86** (0~1,000 triples 일정 유지) |

---

## 8. Limitation

1. **고정 길이 벡터 압축에 따른 정보 병목(Information Bottleneck)**: 각 트리플 전체를 1-token 크기의 고정 차원 벡터 $(\tilde{\boldsymbol{k}}_m, \tilde{\boldsymbol{v}}_m)$로 압축하므로 정확한 고유명사, 수치, 코드 등 word-by-word 정밀 복원이 어렵다(p.10, p.23). 실제 Synthetic KB에서도 `"sustainable"`이 `"renewable"`로 치환되는 표현 변형이 관찰되었다(p.23).
2. **Out-of-Distribution (OOD) 성능 저하**: 15,000 validation triples의 Synthetic KB 대비 실제 기업 이메일 기반 Enron KB에서는 15th layer Retrieval Accuracy(Fig. 5), BERT Score (F1), GPT-4 open-ended score(Fig. 6a–b)가 일관되게 하락하며, 대규모($10^4$)에서는 Open-ended GPT-4 score(~1.7)가 Zero-shot(~2.2)보다 낮아진다(p.9–10).
3. **트리플 간 독립성 가정 및 다단계 추론 부재**: Rectangular Attention에서 Knowledge Token 간 Self-Attention을 배제하므로 엔티티 간 관계(그래프 또는 트리 구조)를 직접 모델링하지 못하며, multi-hop 또는 Chain-of-Thought (CoT) 추론에 대한 학습 및 평가가 빠져 있다(p.6, p.10, p.17).
4. **사전 KB 구축 의존성 및 초기 학습 비용**: 비정형 원시 텍스트(raw text)를 직접 입력받지 못하고 외부 파이프라인으로 미리 추출된 `(<name>, <property>, <value>)` 트리플 KB가 필요하며(p.3–4, p.8), 학습이 불필요한 ICL·RAG와 달리 단일 80GB A100 GPU 기준 24–48시간의 일회성 어댑터 학습 비용이 발생한다(p.8, p.16).
5. **평가 범위·베이스라인 격차 및 Over-refusal**: 실험이 단일 백본(Llama3 8B)과 3가지 고정 `<property>`(`description`, `objectives`, `purpose`)에 한정되어 있으며(p.8), Q&A 품질 평가(Fig. 6)에 표준 RAG 베이스라인이 포함되지 않았다(ICL은 200 triples까지만 비교, p.8). 또한 KB 크기 증가 시 Precision이 하락하는 Over-refusal 현상(Fig. 6c, p.9–10)과 상용 인코더(`ada-002`, `text-embedding-large`) 의존성(Fig. 7, p.15)이 존재한다.

---

## 9. Takeaways

1. **대규모 KB 서빙의 선형 확장성 확보**: $M \gg N$ 조건에서 Rectangular Attention은 FFN 연산량 증가 없이 $\mathcal{O}((M+N)ND)$ 시간 및 $\mathcal{O}((M+N)N)$ 메모리 복잡도로 10K+ triples를 단일 80GB A100 GPU에서 처리하며, $M=512$ 기준 $K=5$ RAG와 동등한 메모리로 더 낮은 토큰 생성 latency를 달성한다(p.1, p.6, p.16–17 Fig. 11).
2. **무재계산 동적 업데이트 및 순서 불변성**: Knowledge Token이 상호 독립적으로 인코딩되므로 일반 KV Cache와 달리 전체 재계산 없이 특정 트리플만 추가·수정·삭제할 수 있으며, 컨텍스트 위치에 따른 Positional Bias가 발생하지 않는다(p.3, p.6).
3. **별도 정규화 없는 암묵적 검색 및 해석 가능성**: Instruction Tuning만으로 중간 레이어(32층 중 15th layer, 32 heads 평균)의 Post-softmax attention score가 높은 Top-1/Top-5 Retrieval 정확도를 보이며, 어휘가 변형된 Perturbed query에서는 BM25를 상회해 근거 트리플 추적에 활용할 수 있다(p.7–9 Fig. 4–5, p.16 Fig. 10b).
4. **합성 데이터 기반 투영 학습으로 파라미터 암기 방지**: Base LLM 가중치를 동결하고 이름-내용 상관관계를 낮춘 GPT 생성 합성 KB로 선형 어댑터만 학습함으로써, 도메인 지식의 파라미터 암기 위험 없이 OOD 데이터로 전이 가능한 semantic projection을 구축할 수 있다(p.2, p.7).
5. **인코더 용량 및 전 레이어 주입의 중요성**: 소형 오픈소스 인코더(`all-MiniLM-L6-v2` $P=384$, `all-mpnet-base-v2` $P=768$)보다 고차원 인코더(`ada-002` $P=1536$, `text-embedding-large` $P=3072$)가 우수하며(p.15 Fig. 7), 초기 레이어의 Knowledge Token이 'soft prompt' 역할을 하므로 주입 간격을 $K=10$으로 늘리면 거절 지시 이행이 붕괴한다(p.15–16 Fig. 8–9).

---

## 10. Critical Analysis

KBLaM(ICLR 2025)은 Pre-trained LLM 가중치를 동결한 채 외부 KB를 단일 토큰 크기의 연속 벡터로 압축해 Rectangular Attention으로 주입함으로써, 8K context window의 Llama3 8B 모델에 단일 80GB A100 GPU 환경에서 10K 이상의 트리플을 $\mathcal{O}(M)$ 선형 복잡도로 통합하는 독창적 해법을 제시한다(p.1, p.5–6). 그러나 방법론과 실험 설계를 비판적으로 고찰하면 다음 네 가지 핵심 쟁점과 향후 연구 과제가 도출된다.

1. **'단일 컴포넌트 End-to-End' 프레이밍의 이면**: 논문은 RAG의 외부 Retriever를 제거한 단일 아키텍처를 강조하나(p.1, p.2 Fig. 1), 실제로는 오프라인 단계에서 비정형 문서를 `(<name>, <property>, <value>)` 트리플로 추출·클러스터링하는 외부 KB 구축 파이프라인(p.4, p.8)과 `ada-002`($P=1536$) 같은 상용 Sentence Encoder API에 강하게 의존한다(p.8, p.15). 즉, 추론 시점의 retrieval 모듈을 오프라인 전처리 및 어댑터 투영으로 치환한 구조로 이해해야 한다.
2. **압축 효율과 정밀도 간의 근본적 트레이드오프**: 트리플당 1개 토큰 크기로 압축하는 설계(p.4–5)는 `description`, `objectives`, `purpose` 같은 서술적 질의에는 효율적이나, 정확한 수치나 고유명사 복원에서는 정보 손실(예: `"sustainable"` $\to$ `"renewable"`, p.23)을 야기한다(p.10). 향후 정보량이나 정밀도 요구 수준에 따라 토큰 수와 압축률을 동적으로 조절하는 **Adjustable Compression Rate** 도입이 필요하다(p.10).
3. **베이스라인 비교의 비대칭성**: ICL은 메모리 한계로 $M \le 200$ triples까지만 비교되었고(p.8), RAG와는 생성 품질(Q&A 정확도)이 아닌 Latency/Memory($M=512$ 기준 $K=5$ RAG, p.16–17 Fig. 11) 및 BM25 Retrieval Accuracy(p.15–16 Fig. 10)만 비교되었다. 따라서 10K+ 대규모 환경에서 최신 Dense Retriever 기반 RAG 대비 KBLaM이 실제 Q&A 정확도와 총소유비용(TCO) 측면에서 우위를 점하는지는 추가 검증이 요구된다.
4. **OOD 일반화, 스케일링 보정 및 구조적 확장 과제**: 순수 합성 데이터(120K training triples) 학습 모델이 Enron KB로 전이되긴 하나 ICL 대비 BERT Score (F1), GPT-4 score, 15th layer Retrieval Accuracy 모두 뚜렷한 성능 하락을 겪으며(p.8–10 Fig. 5–6), $\log C - \log M$ ($C=100$) 스케일링 적용에도 $M$ 증가에 따른 Over-refusal(Precision 하락)이 발생한다(p.6, p.9–10 Fig. 6c). 이를 극복하기 위해 향후 **(1) 트리/그래프 구조를 반영한 Tree/Graph-aware Rectangular Attention**으로 multi-hop 및 CoT 복합 추론을 지원하고(p.10, p.17), **(2) 실제 KB 시드 기반의 고다양성 합성 데이터**와 오픈소스 백본·고성능 인코더(또는 fine-tuned compressor, p.14)를 결합하며(p.9–10), **(3) 비정형 Raw Text 직접 인코딩**으로 확장하는 연구가 유망하다(p.10).
