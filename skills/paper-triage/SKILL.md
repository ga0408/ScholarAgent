---
name: paper-triage
description: Screen a research paper from its title, abstract, and table of contents to extract Overview, Background, Problem, Motivation, Contributions, and key page ranges.
---
# Paper Triage Skill
- Extract high-level context (`overview`, `background`, `problem`, `motivation`, `contributions`).
- Identify `method_pages` and `experiment_pages` (1-indexed) so downstream multimodal subagents know which page PNGs to inspect.
- Keep technical terms in English; write explanations in clear academic Korean.
