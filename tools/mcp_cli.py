#!/usr/bin/env python3
"""Talk to an AIBuilder server's MCP endpoint from a shell, and log what each call costs.

    python3 tools/mcp_cli.py list                      # instructions + tools, as an MCP client sees them
    python3 tools/mcp_cli.py call fill '{"region":[0,64,0,4,64,4],"pattern":"stone"}'
    python3 tools/mcp_cli.py cost                      # sizes of instructions and tools/list
    python3 tools/mcp_cli.py report [log.jsonl]        # per-tool totals of a logged session

The server is AIBUILDER_URL (its address on the panel, https://<panel>/s/<server>) and AIBUILDER_TOKEN (your access token
for that server, from Connect Claude on its page in the panel); the plugin itself listens on nothing.
With AIBUILDER_LOG=<file.jsonl> every call appends {tool, arg_chars, result_chars, image_px, tokens, args, result}.
Images are written to AIBUILDER_IMAGES (default: the system temp directory) and their path is printed.

Token figures are estimates: characters / 3.5 for text, pixels / 750 for images.
"""

from __future__ import annotations

import base64
import json
import os
import struct
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path


def _server() -> tuple[str, str]:
    url, token = os.environ.get("AIBUILDER_URL"), os.environ.get("AIBUILDER_TOKEN")
    if not url or not token:
        sys.exit("set AIBUILDER_URL (https://<panel>/s/<server>) and AIBUILDER_TOKEN (that server's access token, from Connect Claude in the panel)")
    return url.rstrip("/"), token


def rpc(method: str, params: dict | None = None) -> dict:
    url, token = _server()
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}}).encode()
    req = urllib.request.Request(url + "/mcp", data=body, method="POST", headers={
        "Authorization": "Bearer " + token, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            answer = json.loads(resp.read())
    except urllib.error.URLError as e:
        sys.exit(f"cannot reach {url}: {e}")
    if "error" in answer:
        sys.exit("MCP error: " + answer["error"].get("message", "?"))
    return answer["result"]


def tokens(chars: int, pixels: int = 0) -> int:
    return round(chars / 3.5 + pixels / 750)


def _png_pixels(png: bytes) -> int:
    width, height = struct.unpack(">II", png[16:24])
    return width * height


def _log(entry: dict) -> None:
    path = os.environ.get("AIBUILDER_LOG")
    if path:
        with open(path, "a") as f:
            f.write(json.dumps(entry) + "\n")


def cmd_list() -> None:
    print(rpc("initialize", {"protocolVersion": "2025-06-18"}).get("instructions", ""))
    print()
    for tool in rpc("tools/list")["tools"]:
        print(f"## {tool['name']}\n{tool['description']}\nschema: {json.dumps(tool['inputSchema'], separators=(',', ':'))}\n")


def cmd_cost() -> None:
    instructions = rpc("initialize", {"protocolVersion": "2025-06-18"}).get("instructions", "")
    listing = rpc("tools/list")
    chars = len(json.dumps(listing, separators=(",", ":")))
    print(f"instructions: {len(instructions)} chars (~{tokens(len(instructions))} tokens)")
    print(f"tools/list:   {chars} chars (~{tokens(chars)} tokens), {len(listing['tools'])} tools")
    for tool in sorted(listing["tools"], key=lambda t: -len(json.dumps(t))):
        print(f"  {tool['name']:<16} {len(json.dumps(tool, separators=(',', ':'))):>5}")


def cmd_call(name: str, raw_args: str) -> None:
    try:
        args = json.loads(raw_args) if raw_args.strip() else {}
    except ValueError as e:
        sys.exit(f"arguments are not valid JSON: {e}")
    result = rpc("tools/call", {"name": name, "arguments": args})
    text, pixels = "", 0
    for part in result.get("content", []):
        if part["type"] == "text":
            text += part["text"]
        elif part["type"] == "image":
            png = base64.b64decode(part["data"])
            pixels += _png_pixels(png)
            directory = Path(os.environ.get("AIBUILDER_IMAGES") or tempfile.gettempdir())
            directory.mkdir(parents=True, exist_ok=True)
            path = directory / f"render-{int(time.time() * 1000)}.png"
            path.write_bytes(png)
            print(f"[image {path}]")
    print(text)
    arg_chars = len(json.dumps(args, separators=(",", ":")))
    _log({"tool": name, "arg_chars": arg_chars, "result_chars": len(text), "image_px": pixels,
          "tokens": tokens(arg_chars + len(text), pixels), "error": bool(result.get("isError")),
          "args": args, "result": text[:2000]})
    if result.get("isError"):
        sys.exit(1)


def cmd_report(path: str | None) -> None:
    path = path or os.environ.get("AIBUILDER_LOG")
    if not path or not Path(path).exists():
        sys.exit("no log: pass a file or set AIBUILDER_LOG")
    rows: dict[str, dict] = {}
    for line in Path(path).read_text().splitlines():
        e = json.loads(line)
        row = rows.setdefault(e["tool"], {"calls": 0, "errors": 0, "arg_chars": 0, "result_chars": 0, "image_px": 0})
        row["calls"] += 1
        row["errors"] += 1 if e.get("error") else 0
        for key in ("arg_chars", "result_chars", "image_px"):
            row[key] += e[key]
    print(f"{'tool':<16} {'calls':>5} {'errors':>6} {'arg ch':>8} {'result ch':>9} {'image px':>9} {'~tokens':>8}")
    total = {"calls": 0, "errors": 0, "arg_chars": 0, "result_chars": 0, "image_px": 0}
    for name, r in sorted(rows.items(), key=lambda kv: -tokens(kv[1]["arg_chars"] + kv[1]["result_chars"], kv[1]["image_px"])):
        print(f"{name:<16} {r['calls']:>5} {r['errors']:>6} {r['arg_chars']:>8} {r['result_chars']:>9} {r['image_px']:>9} "
              f"{tokens(r['arg_chars'] + r['result_chars'], r['image_px']):>8}")
        for key in total:
            total[key] += r[key]
    print(f"{'total':<16} {total['calls']:>5} {total['errors']:>6} {total['arg_chars']:>8} {total['result_chars']:>9} "
          f"{total['image_px']:>9} {tokens(total['arg_chars'] + total['result_chars'], total['image_px']):>8}")
    print(f"written by the model (args): ~{tokens(total['arg_chars'])} tokens; "
          f"read by the model (results + images): ~{tokens(total['result_chars'], total['image_px'])} tokens")


def main(argv: list[str]) -> None:
    if len(argv) >= 2 and argv[1] == "list":
        cmd_list()
    elif len(argv) >= 2 and argv[1] == "cost":
        cmd_cost()
    elif len(argv) >= 3 and argv[1] == "call":
        cmd_call(argv[2], argv[3] if len(argv) > 3 else "{}")
    elif len(argv) >= 2 and argv[1] == "report":
        cmd_report(argv[2] if len(argv) > 2 else None)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
