---
name: paper-triage
description: Screen a research paper from its title, abstract, introduction opening, and extracted section TOC to produce a grounded Korean TL;DR, overview, and 1-indexed key page map.
---
# Paper Triage Skill
- Extract `tldr`, `overview`, `core_problem`, `core_mechanism`, `headline_result`, `domain_tags`, and `key_pages`.
- Map custom Method section headings (between Introduction/Related Work and Experiments) into `method_pages`.
- Keep technical terms in English; write explanations in clear academic Korean (`prompts/templates/triage.md`).
