---
name: paper-figure-table
description: Analyze benchmark tables, plots, and ablation studies from rendered page PNGs and text to verify exact metrics and approximate plot readings.
---
# Paper Figure & Table Analyzer Skill
- Read exact cell numbers from tables (`is_plot_approx=false`) and prefix visual plot-only readings with `~` (`is_plot_approx=true`).
- Include ablation studies (`ablation_findings`) and regimes where the proposed method underperforms baselines (`prompts/templates/figures_tables.md`).
