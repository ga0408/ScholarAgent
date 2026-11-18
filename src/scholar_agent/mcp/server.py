"""Minimal zero-dependency JSON-RPC 2.0 stdio MCP server for ScholarAgent tools."""

from __future__ import annotations

import json
import sys
from typing import Any, Callable

from scholar_agent.tools.paper_tools import (
    deep_dive_paper,
    lookup_benchmark,
    read_paper_section,
    triage_paper,
)

SERVER_INFO = {"name": "scholar-agent-mcp", "version": "0.1.0"}
PROTOCOL_VERSION = "2024-11-05"

TOOLS_SPEC: list[dict[str, Any]] = [
    {
        "name": "triage_paper",
        "description": "Fast abstract-only triage of a related/cited paper with TL;DR and SQLite caching.",
        "inputSchema": {
            "type": "object",
            "required": ["query_or_arxiv_id"],
            "properties": {
                "query_or_arxiv_id": {
                    "type": "string",
                    "description": "arXiv ID, URL, or paper title to triage via abstract.",
                }
            },
        },
    },
    {
        "name": "lookup_benchmark",
        "description": "Look up a benchmark's task type, description, and evaluation metrics (cached in SQLite).",
        "inputSchema": {
            "type": "object",
            "required": ["name"],
            "properties": {
                "name": {"type": "string", "description": "Benchmark name (e.g. LoCoMo, SWE-bench)."}
            },
        },
    },
    {
        "name": "read_paper_section",
        "description": "Read a canonical section (abstract, introduction, related_work, method, experiment, conclusion) or page range (e.g. '4-6') from a processed paper.",
        "inputSchema": {
            "type": "object",
            "required": ["paper_id", "section_or_pages"],
            "properties": {
                "paper_id": {"type": "string", "description": "e.g. arxiv_2611.12068"},
                "section_or_pages": {"type": "string", "description": "Section name or page range."},
            },
        },
    },
    {
        "name": "deep_dive_paper",
        "description": "Fetch cached full pipeline summary or run full deep-dive analysis for an arXiv paper.",
        "inputSchema": {
            "type": "object",
            "required": ["arxiv_input"],
            "properties": {
                "arxiv_input": {"type": "string", "description": "arXiv ID or URL"},
                "force": {"type": "boolean", "default": False},
            },
        },
    },
]

TOOL_HANDLERS: dict[str, Callable[..., Any]] = {
    "triage_paper": lambda args: triage_paper(args["query_or_arxiv_id"]),
    "lookup_benchmark": lambda args: lookup_benchmark(args["name"]),
    "read_paper_section": lambda args: read_paper_section(
        args["paper_id"], args["section_or_pages"]
    ),
    "deep_dive_paper": lambda args: deep_dive_paper(
        args["arxiv_input"], force=bool(args.get("force", False))
    ),
}


def handle_request(req: dict[str, Any]) -> dict[str, Any] | None:
    req_id = req.get("id")
    method = req.get("method", "")
    params = req.get("params") or {}

    if req_id is None:
        # JSON-RPC notification (e.g. notifications/initialized) -> no response
        return None

    if method == "initialize":
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {}},
                "serverInfo": SERVER_INFO,
            },
        }
    if method == "ping":
        return {"jsonrpc": "2.0", "id": req_id, "result": {}}
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": req_id, "result": {"tools": TOOLS_SPEC}}
    if method == "tools/call":
        name = params.get("name", "")
        args = params.get("arguments") or {}
        handler = TOOL_HANDLERS.get(name)
        if handler is None:
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "error": {"code": -32601, "message": f"Unknown tool: {name}"},
            }
        try:
            res = handler(args)
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "content": [
                        {"type": "text", "text": json.dumps(res, ensure_ascii=False, indent=2)}
                    ]
                },
            }
        except Exception as exc:
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "content": [{"type": "text", "text": f"Error: {exc}"}],
                    "isError": True,
                },
            }

    return {
        "jsonrpc": "2.0",
        "id": req_id,
        "error": {"code": -32601, "message": f"Method not found: {method}"},
    }


def main() -> None:
    for line in sys.stdin:
        raw = line.strip()
        if not raw:
            continue
        try:
            req = json.loads(raw)
        except json.JSONDecodeError as exc:
            sys.stdout.write(
                json.dumps({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": str(exc)}})
                + "\n"
            )
            sys.stdout.flush()
            continue
        resp = handle_request(req)
        if resp is not None:
            sys.stdout.write(json.dumps(resp, ensure_ascii=False) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
