"""Reusable research tools for on-demand paper triage, benchmark lookup, and section reading."""

from scholar_agent.tools.paper_tools import (
    deep_dive_paper,
    lookup_benchmark,
    read_paper_section,
    triage_paper,
)

__all__ = [
    "triage_paper",
    "lookup_benchmark",
    "read_paper_section",
    "deep_dive_paper",
]
