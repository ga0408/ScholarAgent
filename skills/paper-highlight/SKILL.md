---
name: paper-highlight
description: Select 20-28 verbatim quotes distributed across all paper sections with Korean academic commentary for deterministic PDF highlighting.
---
# Paper Highlight Selector Skill
- Enforce section quotas (`background`: 3-5, `contribution`: 3-4, `method`: 6-8, `experiment`: 5-7, `limitation`: 3-4) with at most 2 quotes from the Abstract.
- Return exact verbatim substrings from `[block_id]` blocks and a 1-sentence Korean `reason` (`prompts/templates/highlights.md`).
