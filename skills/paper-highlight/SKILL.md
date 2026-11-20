---
name: paper-highlight
description: Select concise verbatim concept spans and block_ids from segments.json for deterministic PDF highlighting.
---
# Paper Highlight Selector Skill
- Choose 12-18 high-value structural concept spans across `background`, `contribution`, `method`, `experiment`, `limitation`.
- **Short Concept Span Only (4-12 words)**: Highlight only the representative concept phrase or signpost+concept clause on that line (not entire multi-line sentences).
- **Discourse & Enumeration Completeness**: Preserve complete enumeration chains (`First, ...` / `Then/Second, ...` / `Lastly/Finally, ...` or `1. / 2. / 3.`).
- **Concise Concept Label (`reason`)**: Summarize the highlighted concept in 1 short Korean line (15-35 chars).
- Return the **exact verbatim substring** from `segments.json` along with its `block_id` and `page`. Never paraphrase quotes.
