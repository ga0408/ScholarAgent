# MIRIX-2.0: Multi-Agent Memory System — Research Report

**Paper ID:** `arxiv:2611.12068` (`2026-11-09`)  
**Authors:** Wei-Chieh Huang, Weizhi Zhang, Jiayan Nan, Peng Xia, Yu Wang  
**PDF:** [https://arxiv.org/pdf/2611.12068.pdf](https://arxiv.org/pdf/2611.12068.pdf)

---

## 1. Overview
**MIRIX-2.0** is a multi-agent long-term memory system designed to achieve **practicality, accuracy, and efficiency** simultaneously without model fine-tuning (p. 1). Operating out-of-the-box with both closed models (`gpt-4.1-mini`, `GPT-6 Luna`, `GPT-5.6 Luna`, `GPT-5.2`) and open models (`Qwen3.6-35B-A3B`), it achieves the highest accuracy among compared systems on four of five QA benchmarks: **91.4%** on LoCoMo, **72.6%** on LongMemEval-S (+14.3 points over the strongest baseline), and—with `AutoDream` disabled (`M1`)—gains of **+31.0** and **+22.0** points over `HyperMem` on single-hop (`SHDocQA`, 83.0%) and multi-hop (`MHDocQA`, 71.0%) document QA (pp. 1, 3, 10–13, 27). As procedural memory, it achieves the highest mean success rates on ALFWorld (**76.4%** with `GPT-6 Luna`) and WebShop (**37.4%**), and reaches **49.2%** accuracy on MetaClaw-Bench Part I (`GPT-5.2`, vs. 44.0% for MetaClaw Skills) (pp. 1, 3, 12–13, 28). Its core architecture pairs six typed memory agents acting as a verbatim system of record with a provenance-preserving $n$-ary hypergraph index, explicit query-time retrieval budgets, and asymmetric out-of-path offline consolidation (`AutoDream`), enabling `M1` to answer faster than `HyperMem` across all five timed benchmarks with public code (pp. 1–4, 14, 29).

## 2. Background
LLM agents operate over long horizons far exceeding a single context window, making cross-session long-term memory essential for personalization, consistency, and reuse of prior experience (p. 2). Recent memory research separates **user-centric memory**—which personalizes an agent to a user and depends on broad factual retention and associative linking—from **agent-centric memory**—which accumulates an agent's own operational experience and relies on precise refinement and skill/strategy distillation (p. 2).

## 3. Problem
Existing memory architectures force a trade-off that sacrifices at least one of accuracy, practicality, or efficiency (p. 2):
- **Parametric memories** (e.g., `Titans`) require weight or latent-state training, excluding closed-weight API models (pp. 2, 15).
- **Summary- and extraction-based consolidation** (`mem0`, `MemoryBank`, `Auto-Dreamer`) reduces storage footprint but permanently discards fine-grained details (names, numbers, dates, counts) and risks hallucination accumulation during iterative LLM rewrites (pp. 2, 16).
- **Flat full-record retrieval** (`MIRIX-1.0 (M0)`, `RAG`) accumulates search noise as history grows and struggles to compose multi-hop links (pp. 2, 10).
- **Binary triple graphs** (`Graphiti`) split $n$-ary facts across disconnected binary edges, losing co-participant bindings (pp. 2, 7).
- **Topic-tree hypergraphs and file-system memories** (`HyperMem`, `Claude Code Auto Memory`) depend on write-time summaries and top-down pruning, degrading fine-grained recall and failing to resolve entities across files (p. 2).

## 4. Motivation
Production agent deployment requires a unified memory architecture that runs on arbitrary closed or open LLMs without training while simultaneously guaranteeing (pp. 2, 4, 5):
1. **Source Fidelity:** Answers must trace back to verbatim stored source records rather than lossy summaries.
2. **Temporal Identity:** Distinct occurrences with similar phrasing must remain separable rather than collapsing into a single record.
3. **Bounded Access:** The evidence inspected per query must remain bounded by explicit budgets rather than scaling with total history length.
4. **Unified User & Agent Support:** Both user-centric personalization and agent-centric procedural refinement must operate within a single memory system.

## 5. Contribution
1. **Layered Storage–Index Separation (`Typed Evidence Plane` + `Provenance Hypergraph`):** Six typed memory agents commit complete, verbatim records to a Vector Database as the system of record, while an $n$-ary hypergraph stored in Neo4j serves purely as an index with fail-soft ingestion and occurrence-aware frame identity (pp. 2, 4–7).
2. **Single-Store Dual Memory:** MIRIX-2.0 is the first memory system to support both user-centric memory (personalization) and agent-centric procedural memory (skill distillation) within one repository (pp. 2, 4).
3. **Budgeted Retrieval & Non-Destructive Offline Consolidation:** A deterministic query planner enforces per-operator row budgets during graph traversal, and asymmetric out-of-path `AutoDream` merges duplicate semantic entity anchors without modifying any source record (+3.0 points on LoCoMo) while distilling task sessions into quote-verified skills (pp. 3–4, 8–9, 11).

## 6. Method
MIRIX-2.0 models persistent state at time $t$ for user $u$ as the pair of a verbatim storage plane and an index graph (Eq. (1), p. 5):
$$\mathcal{S}_u^t = (P_u^t, G_u^t)$$

### 6.1 Typed Evidence Plane ($P_u$, System of Record)
A `Meta Memory Manager` $R_\theta^\star(x_t)$ routes each incoming input $x_t$ to a subset of six specialized memory agents $\mathcal{K}_t \subseteq \{1,\dots,6\}$: `Core`, `Episodic` ($P_u^E$), `Semantic` ($P_u^S$), `Procedural` ($P_u^P$), `Resource`, and `Knowledge Vault` (pp. 5–6, Table 5 on p. 23). Each selected writer $W_k^\star$ preserves exact names, lists, numbers, and dates and commits a record to the Vector Database prior to graph indexing (Algorithm 1 lines 2–4, p. 6; Eq. (A.1), p. 22):
$$m = (i, k, y, \tau_e, \tau_m, \rho, \mathbf{z})$$
where $i$ is an immutable row ID, $y$ is stored text, $\tau_e$/$\tau_m$ separate event time from mention/write time, $\rho$ records source provenance, and $\mathbf{z}$ is the search embedding (Eq. (2), p. 6). Because `Commit(P_u, m)` precedes fail-soft graph hooks, indexing failures never roll back stored memory (pp. 2, 6, 22). Only `Episodic` and `Semantic` rows are hypergraph-indexed; the other four types use type-specific direct lookup (pp. 5, 22–23).

### 6.2 Provenance-Preserving $N$-ary Hypergraph ($G_u$)
In Neo4j, `Episodic` and `Semantic` records are indexed into $G_u = (\mathcal{A}_u, \mathcal{F}_u, \mathcal{E}_u)$ (Eq. (3), p. 6):
- **Anchors** $a = (i_a, n_a, t_a, \mathbf{e}_a, I_a^E, I_a^S)$ store canonical names $n_a$, entity types $t_a$, name embeddings $\mathbf{e}_a$, and cited row sets $I_a^E, I_a^S$ (Eq. (4), p. 6). Deterministic key normalization $\kappa(n)$ and an admission gate block generic terms/dialogue roles and cap anchors at $d \le 16$ (`Episodic`) or $18$ (`Semantic`) per record (pp. 6, 22, 25).
- **Reified $N$-ary Fact Frames** bind open-vocabulary roles directly to anchors while keeping dates, prices, quantities, and durations as frame literals $\ell_f$ alongside cited Vector DB row IDs $C_f$ (Eq. (5), p. 7):
  $$f = \left(p, \{(r_j, a_j)\}_{j=1}^{d_f}, \ell_f, \tau_e, \tau_m, C_f\right)$$
  Extraction `Extract^\star(m)` is checked by a deterministic audit; if content-word coverage falls below $45\%$, `Repair^\star(m)` supplements up to 12 missing items before registry-guided `Canon^\star(\Phi)` (top-4 ANN name lookup, $\cos \ge 0.72$, or $(1+\cos)/2 \ge 0.86$ in code) and frame materialization ($|A_f| \ge 2$) (pp. 6–7, 22–23).
- **Occurrence Identity:** `Semantic` frames accumulate citations under $\operatorname{id}_S(f) = H(u, p, \sigma(f))$ (Eq. (6), p. 7), whereas `Episodic` frames include an occurrence key $\chi(f)$ (Eqs. (7)–(8), p. 7) to prevent undated repeated events from collapsing:
  $$\operatorname{id}_E(f) = H(u, p, \sigma(f), \chi(f)), \quad \chi(f) = \begin{cases} \text{explicit event time}, & \text{if extracted}, \\ \text{immutable memory id}, & \text{otherwise}. \end{cases}$$

### 6.3 Graph-Owned Retrieval & Answer-Time Verification
A deterministic planner sets operator-specific row budgets across five operators (`Single`, `Temporal`, `Intersection`, `List-union`, `Count`), with $B_q^B \in \{6, 7, 8\}$ and $B_q^R \in \{4, 6, 7, 9, 12\}$ (Eq. (9), p. 7; Table 6, p. 23):
$$\Pi(q) = \left(o_q, \mathcal{P}_q, \mathcal{C}_q, \mathcal{Y}_q, \mathcal{T}_q, B_q^B, B_q^R\right)$$
Entry anchors (top $K_A = 15$ from embedding recall $\cup$ exact relation mentions) are ranked by $s_A(a,q) = s_{\text{vec}} + s_{\text{exact}} + s_{\text{lex}} + s_{\text{pred}} + s_{\text{spec}} - s_{\text{hub}}$ (Eq. (10), p. 7). Two parallel lanes retrieve evidence: (1) a **Base-recall lane** collecting anchor-linked and co-participant rows, and (2) a **Relation-citation lane** filtering adjacent frames in Neo4j (capped at $K_F = 240$), scoring them via the 9-term additive score $s_F(f, q)$ (Eq. (11), p. 8, penalizing planned vs. completed state mismatches), and dereferencing the top $B_q^R$ frames' citations $C_f$. Citation-balanced fusion preserves episodic/semantic separation. The answering agent issues up to 5 `Read` calls; if rule-based `Check(\hat{y})` detects uncertainty, ledger mismatch, or `Intersection` queries, it triggers at most one `Rewrite^\star` round (Algorithm 1 lines 16–20, pp. 6, 8, 24). Direct Vector DB search acts solely as a graph-read-failure fallback (pp. 7–8).

### 6.4 Asymmetric Out-of-Path `AutoDream`
- **`SemanticDream`:** Triggered outside the write path every 5 chunks (and at ingestion end), it satisfies the lossless contract $\operatorname{Dream}(P_u, G_u; \Delta_u) = (P_u, \widetilde{G}_u)$ (Eq. (12), p. 8)—leaving $P_u$ untouched. For anchors $a \in \mathcal{A}_\Delta$ supported by newly written semantic frontier $\Delta_u$, candidate pairs $\mathcal{C}_\Delta$ (Eq. (13), p. 8) select $b \in \operatorname{NN}_4(a) \setminus \{a\}$ with $\cos(\mathbf{e}_a, \mathbf{e}_b) \ge 0.86$ and $t_a = t_b \in \mathcal{T}_{\text{safe}} = \{\text{person}, \text{organization}, \text{location}, \text{object}, \text{concept}\}$. Bounded LLM verification `Same^\star(a,b)` (max 80 pairs intermediate, 120 final + 60 repair; rejections cached in $\mathcal{N}$) merges approved pairs by unioning $I^E, I^S$, summing mention counts, taking max admission scores, and rewiring edges (pp. 8–9, 24–25).
- **`ProceduralDream`:** Runs asynchronously after every 5 task sessions. `Distill^\star(S_u)` extracts reuse/avoid experiences $e_{1:m}$ (verified against transcript quotes, else labeled `unclear`), and `Curate^\star(P_u^P, e_{1:m})` creates, edits, or deletes skills in $P_u^P$ with quote verification (pp. 9, 25).

### 6.5 Complexity Bounds (Hard Invariants)
Excluding LLM calls and indexed DB lookups, graph write cost per memory is $O(d + h)$ ($d \le 16/18$); post-retrieval ranking/fusion is bounded at $O(K_A + K_F + K_M)$ ($K_A = 15$, $K_F = 240$, $K_M = B_q^R \le 12$); and `SemanticDream` ANN candidate generation scales with frontier size as $O(|\Delta_u| \log |\mathcal{A}_u|)$ (Section A.6, p. 25).

## 7. Experiment

### 7.1 Experimental Setup
User-memory benchmarks compare `M0` (`MIRIX-1.0`, flat vector), `M1` (`MIRIX-2.0` graph only), and `M2` (`MIRIX-2.0` graph + `SemanticDream`) using `gpt-4.1-mini` (temperature 0, seed 42) for extraction/answering, `gpt-4.1-nano` for topic extraction, and `text-embedding-3-small` (pp. 9–10, 26). Conversational QA uses a `gpt-4o-mini` majority-of-3 judge; `MemoryAgentBench` (`SHDocQA`, `MHDocQA` via substring match; `DetectiveQA` via letter exact match) evaluates `M1` only (pp. 10, 26–27). Procedural memory is evaluated over 3 runs (mean $\pm$ std) on ALFWorld (39 train / 134 held-out, max 50 steps) and WebShop (40 train / 140 held-out, max 15 steps), plus MetaClaw-Bench Part I (346 questions over 30 days, 224 file-check questions) (pp. 12–13, 27–28).

### 7.2 User-Centric Memory Accuracy
| Benchmark | Metric / Setting | `M0` (Flat) | `M1` (Graph) | `M2` (Graph+Dream) | Strongest Baseline (`HyperMem` / Other) |
| :--- | :--- | :---: | :---: | :---: | :---: |
| **LoCoMo** (1,540 Qs, Table 1, p. 11) | Overall Acc. (%) | 85.7 | 88.4 | **91.4** | `HyperMem` 91.0* (`mem0` 82.9) |
| — *Multi-hop / Temporal* | Sub-category Acc. (%) | 83.3 / 85.0 | 89.0 / 86.0 | **91.8** / **89.4** | `HyperMem` 91.1 / 84.4 |
| — *Open / Single-hop* | Sub-category Acc. (%) | 70.8 / 88.3 | 71.9 / 91.1 | 74.0 / 94.1 | `HyperMem` **76.0** / **95.2** |
| **LongMemEval-S** (300 Qs, Table 7, p. 27) | Overall Acc. (%) | 58.8 | 70.0 | **72.6** | `HyperMem` 58.3 (`mem0` 56.3) |
| **SHDocQA** (100 Qs, Table 8, p. 27) | Substring Acc. (%) | — | **83.0** | — | `HyperMem` 52.0 (`Graphiti` 50.0) |
| **MHDocQA** (100 Qs, Table 8, p. 27) | Substring Acc. (%) | — | **71.0** | — | `HyperMem` 49.0 (`Graphiti` 43.0) |
| **DetectiveQA** (71 Qs, Table 8, p. 27) | Letter EM (%) | — | 64.8 | — | `MemoryBank` **67.6** (`HyperMem` 66.2) |

*\*`HyperMem` re-run under original configuration scored 91.0% on LoCoMo (original paper reported 92.7%) (p. 10–11).*

On **MemoryCD** (100 `Home_and_Kitchen` users; Table 2, p. 12; Table 9, p. 27), MIRIX-2.0 ranks first on all four metrics for cross-domain review summarization (`ROUGE-1` `.1603`, `ROUGE-L` `.1510`, `BLEU-4` `.0209`, `BERTScore-F1` `.8754`) and on all four metrics for both cross-domain (`R-1` `.2846`, `R-L` `.1491`, `B-4` `.0108`, `BERT` `.8544`) and single-domain (`R-1` `.2868`, `R-L` `.1487`, `B-4` `.0106`, `BERT` `.8539`) review generation, though margins over `RAG` and `Long-context` are modest (p. 12, 27).

### 7.3 Agent-Centric Procedural Memory
- **ALFWorld** (Tables 10–11, p. 28): With `GPT-6 Luna`, MIRIX-2.0 achieves **76.4 $\pm$ 6.2%** success (`21.9` mean steps overall / `13.2` on successes) vs. `AWM` `73.6 ± 1.9%`, `No memory` `69.4 ± 0.7%`, and `ReasoningBank` `66.9 ± 2.8%`. With `GPT-5.6 Luna`, it reaches **61.7 $\pm$ 14.0%** (`26.8` / `12.4` steps) vs. `AWM` `58.0 ± 2.8%`, `No memory` `54.2 ± 1.1%`, and `ReasoningBank` `52.2 ± 0.7%`.
- **WebShop** (`GPT-6 Luna`, Table 11, p. 28): MIRIX-2.0 achieves **37.4 $\pm$ 2.1%** (`8.2` / `6.3` steps) vs. `AWM` `36.0 ± 4.3%`, `No memory` `31.9 ± 1.8%`, and `ReasoningBank` `25.7 ± 3.8%`.
- **MetaClaw-Bench Part I** (Table 3, p. 13): MIRIX-2.0 attains **49.2%** accuracy / **27.7%** file-check completion with `GPT-5.2` (vs. `MetaClaw Skills` `44.0% / 17.1%`, `Baseline` `41.1% / 14.7%`) and **52.7%** / **30.8%** with `Qwen3.6-35B-A3B` (vs. `MetaClaw Skills` `51.8% / 30.8%`, `Baseline` `40.3% / 12.1%`).

### 7.4 Efficiency & Token Cost
- **User-Memory Timing & Tokens (`M1`, AutoDream disabled; Figure 8, p. 14; Table 12, p. 29):** `M1` answers faster than `HyperMem` on all five benchmarks (`SHDocQA` `5.45s` vs. `10.55s`, `MHDocQA` `9.01s` vs. `10.65s`, `DetectiveQA` `7.24s` vs. `13.05s`, `LoCoMo` `5.46s` vs. `9.70s`, `LongMemEval-S` `10.10s` vs. `10.73s`), though it is slower than flat `M0` on the three document QA tasks (`3.76s`, `7.46s`, `5.11s`). `M1` uses fewer answer tokens than `HyperMem` on four of five tasks (`SHDocQA` `1.40M` vs. `3.79M`, `MHDocQA` `1.33M` vs. `3.06M`, `LoCoMo` `15.7M` vs. `16.5M`, `LongMemEval-S` `4.02M` vs. `4.10M`), but consumes more on `DetectiveQA` (`1.16M` vs. `0.35M`; `M0` `706K`). Conversely, document ingestion in `M1` is **3.8–4.0$\times$ slower** than `M0` and **3.4–5.0$\times$ slower** than `HyperMem` (`SHDocQA` `23,784s` vs. `6,227s`/`5,665s`; `MHDocQA` `55,301s` vs. `13,792s`/`11,031s`; `DetectiveQA` `39,491s` vs. `10,321s`/`11,653s`), while conversational ingestion is faster than `HyperMem` (`LoCoMo` `21,416s` vs. `69,467s`; `LongMemEval-S` `70,626s` vs. `170,224s`).
- **Agent-Memory Tokens (`GPT-6 Luna`, Table 4, p. 14):** On WebShop, MIRIX-2.0 uses the fewest training (`1.815M ± 0.054`) and test (`4.975M ± 0.340`) tokens among memory baselines. On ALFWorld, despite taking the fewest mean steps, per-step skill injection yields the highest test tokens among memory methods (`3.813M ± 0.490`, plus `2.253M ± 0.100` training tokens, vs. `2.147M ± 0.048` for `No memory`).

## 8. Limitation
- **Coarse-Grained Ablations:** Experiments ablate only `M0`, `M1`, and `M2` bundles without isolating individual contributions of $n$-ary frames, cited source rows, occurrence identity, answer-time verification, `Distill^\star`/`Curate^\star`, or 6-writer routing; `MemoryCD` and `MetaClaw-Bench` lack component ablations entirely (pp. 9, 12–13, 15–16).
- **Ingestion Bottleneck & Missing `M2` Cost Measurements:** Graph construction slows document ingestion 3.8–4.0$\times$ over `M0` (p. 13). All timed user-memory metrics reflect `M1` (`AutoDream` off), leaving `M2` latency/token overhead, graph compaction, history-length latency scaling, and user-memory ingestion tokens unmeasured (pp. 4, 14–15, 28).
- **Variance & Comparison Rigor:** User-memory benchmarks are single runs relying on a `gpt-4o-mini` judge for LoCoMo/LongMemEval-S (pp. 10, 16). High run-to-run variance on ALFWorld ($\pm 6.2$ on `GPT-6 Luna`; $\pm 14.0$ on `GPT-5.6 Luna`) weakens claims of consistent superiority over `AWM` (`73.6 ± 1.9%`), and `GPT-5.2` MetaClaw-Bench baselines are cited from prior work rather than re-run (pp. 13–14).
- **Task-Specific Regressions & Overhead:** `M1` trails `MemoryBank` (67.6%) and `HyperMem` (66.2%) on `DetectiveQA` (64.8%) while using ~3.3$\times$ `HyperMem`'s answer tokens (`1.16M` vs. `0.35M`) (pp. 11, 14). On ALFWorld (`GPT-6 Luna`), `Pick`, `Look`, and `Pick Two` underperform `No memory` (pp. 12, 28), and varying-horizon training-cost amortization is untested (p. 14).
- **Security & Governance:** Separating `Knowledge Vault` does not replace user consent or access control, and untrusted inputs injected mid-dialogue remain retrievable (p. 16).

## 9. Takeaways
- **Separate System of Record from Index:** Committing verbatim records to a Vector Database before fail-soft hypergraph indexing prevents data loss on write failures and enables clean fallback on graph read failures (pp. 2, 5–6).
- **Non-Destructive Consolidation Works:** Merging only duplicate entity anchors in the index (`SemanticDream`) while caching negative decisions and bounding frontier LLM checks improves conversational QA (LoCoMo `M1` 88.4% $\to$ `M2` 91.4%) without risking summary-induced detail loss (pp. 8–9, 11).
- **Procedural Memory Requires Selective Gating & Cost Awareness:** Naive experience accumulation can hurt performance (`ReasoningBank` drops below `No memory` on ALFWorld and WebShop), and reducing agent steps via retrieved skills can still increase total test tokens when skills are injected at every step (pp. 13–14).
- **Model-Agnostic Practicality:** Decoupling LLM semantic roles from deterministic structural invariants allows immediate deployment across both closed and open models without fine-tuning (pp. 1, 4, 10, 13, 25).

## 10. Critical Analysis
MIRIX-2.0 presents a well-engineered layered design that directly addresses the detail-loss failure mode of summary-based memory systems (`mem0`, `MemoryBank`, `HyperMem`), as evidenced by its +14.3-point lead on LongMemEval-S (`M2`) and +22.0/+31.0-point leads on multi-hop/single-hop document QA (`M1`) (pp. 1, 11, 27). However, three critical gaps remain between its architectural claims and empirical proof:
1. **Bounded Evidence vs. End-to-End Scaling:** While query-time graph traversal enforces hard row budgets ($K_A=15, K_F=240, K_M \le 12$), true latency invariance with growing history is never empirically plotted (p. 4), `M2`'s offline consolidation overhead is omitted from timing tables (pp. 14, 28), and graph indexing introduces a 3.8–4.0$\times$ ingestion slowdown on document corpora (p. 13).
2. **Loose Coupling of "One Memory":** Although user-centric and agent-centric memories reside in one system, procedural skill retrieval runs independently of the semantic hypergraph (p. 10), leaving cross-plane synergy between episodic/semantic facts and procedural skills unverified.
3. **Limits on Complex Reasoning & Open-Domain Recall:** Trailing `MemoryBank` and `HyperMem` on `DetectiveQA` (64.8%, p. 11) and `HyperMem` on LoCoMo Open/Single-hop (`74.0%/94.1%` vs. `76.0%/95.2%`, Table 1, p. 11) shows that verbatim bounded evidence retrieval alone does not resolve complex multi-step deduction or broad open-domain recall. Future work should prioritize `M2` full-cost profiling, adaptive skill gating (to avoid interfering on strong base behaviors like `Pick`/`Look`), fine-grained provenance audits, and memory poisoning defenses (pp. 12–16).
