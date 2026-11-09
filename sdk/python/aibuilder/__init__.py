"""Thin client for the AIBuilder REST API.

Scripts get the same tools as the MCP server, but block data never passes through Claude's context:
Claude writes a short script, the script expands it into thousands of blocks.

    from aibuilder import Builder, box, SELECTION

    b = Builder.connect()                  # AIBUILDER_URL / AIBUILDER_TOKEN from the environment
    b.walls(SELECTION, "80%stone_bricks,20%andesite")
    b.block(10, 64, 10, "lantern")
    b.flush()                              # everything queued so far = one undo step

No dependencies beyond the standard library.
"""

from __future__ import annotations

import base64
import json
import math
import os
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Iterable, Sequence

__all__ = ["Builder", "AIBuilderError", "box", "circle", "SELECTION"]

SELECTION = "selection"

Vec = Sequence[Any]  # [x, y, z]; parts may be ints or "~n" relative strings


class AIBuilderError(RuntimeError):
    """A tool call failed (unknown block, outside build zone, server unreachable, ...).

    `payload` is the server's full answer when there was one, e.g. {"job": "j7", "changed": 0, "error": "..."}.
    """

    def __init__(self, message: str, payload: dict | None = None):
        super().__init__(message)
        self.payload = payload or {}


def box(a: Vec, b: Vec) -> dict:
    """Region from two corners (any order)."""
    return {"min": list(a), "max": list(b)}


class Builder:
    def __init__(self, url: str | None = None, token: str | None = None,
                 player: str | None = None, world: str | None = None, timeout: float = 120):
        self.url = (url or os.environ.get("AIBUILDER_URL") or "http://127.0.0.1:8765").rstrip("/")
        self.token = token or os.environ.get("AIBUILDER_TOKEN")
        if not self.token:
            raise AIBuilderError("set AIBUILDER_TOKEN (http.token in plugins/AIBuilder/config.yml)")
        self.player = player
        self.world = world
        self.timeout = timeout
        self._ops: list[dict] = []

    @classmethod
    def connect(cls, **kwargs) -> "Builder":
        return cls(**kwargs)

    # ---- raw access ----

    def call(self, tool: str, **args) -> dict:
        """Calls any tool by name, e.g. call("fill", region=..., pattern=...)."""
        if self.player and "player" not in args:
            args["player"] = self.player
        if self.world and "world" not in args:
            args["world"] = self.world
        body = json.dumps({k: v for k, v in args.items() if v is not None}).encode()
        req = urllib.request.Request(f"{self.url}/api/{tool}", data=body, method="POST", headers={
            "Authorization": f"Bearer {self.token}",
            "Content-Type": "application/json",
        })
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return json.loads(resp.read() or b"{}")
        except urllib.error.HTTPError as e:
            payload = {}
            try:
                payload = json.loads(e.read())
                message = payload.get("error", str(e))
            except (ValueError, AttributeError):
                message = str(e)
            raise AIBuilderError(f"{tool}: {message}", payload if isinstance(payload, dict) else {}) from None
        except urllib.error.URLError as e:
            raise AIBuilderError(f"cannot reach {self.url}: {e.reason}") from None

    # ---- reads (run immediately) ----

    def context(self) -> dict:
        """Players, selection and `server` ({minecraft, legacy_blocks, height}); check it before picking blocks."""
        return self.call("get_context")

    def block_info(self, name: str) -> dict:
        """{valid, properties} for a block on THIS server, or {valid: False, closest: [...]}."""
        return self.call("block_info", name=name)

    def search_blocks(self, words: str) -> list[str]:
        return self.call("block_info", search=words)["matches"]

    def selection(self, summary: bool = False) -> dict:
        return self.call("get_selection", summary=summary)

    def read(self, region, mode: str = "summary", heightmap: bool = False) -> dict:
        return self.call("read_region", region=region, mode=mode, heightmap=heightmap)

    def render(self, region, view: str = "both", save_to: str | os.PathLike | None = None) -> bytes:
        png = base64.b64decode(self.call("render_region", region=region, view=view)["png_base64"])
        if save_to:
            Path(save_to).write_bytes(png)
        return png

    def undo(self, steps: int = 1) -> int:
        return self.call("undo", steps=steps)["undone"]

    # ---- edits (queued until flush) ----

    def _queue(self, op: str, **args) -> "Builder":
        self._ops.append({"op": op, **{k: v for k, v in args.items() if v is not None}})
        return self

    def fill(self, region, pattern: str):
        return self._queue("fill", region=region, pattern=pattern)

    def walls(self, region, pattern: str):
        return self._queue("walls", region=region, pattern=pattern)

    def outline(self, region, pattern: str):
        return self._queue("outline", region=region, pattern=pattern)

    def replace(self, region, mask: str, pattern: str):
        return self._queue("replace", region=region, **{"from": mask}, pattern=pattern)

    def cylinder(self, base: Vec, radius: float, height: int, pattern: str,
                 hollow: bool = False, radius_z: float | None = None):
        return self._queue("cylinder", base=list(base), radius=radius, radius_z=radius_z,
                           height=height, pattern=pattern, hollow=hollow)

    def sphere(self, center: Vec, radius: float, pattern: str, hollow: bool = False,
               radius_y: float | None = None, radius_z: float | None = None):
        return self._queue("sphere", center=list(center), radius=radius, radius_y=radius_y,
                           radius_z=radius_z, pattern=pattern, hollow=hollow)

    def pyramid(self, base: Vec, size: int, pattern: str, hollow: bool = False):
        return self._queue("pyramid", base=list(base), size=size, pattern=pattern, hollow=hollow)

    def line(self, start: Vec, end: Vec, pattern: str, thickness: float = 0):
        return self._queue("line", **{"from": list(start)}, to=list(end), pattern=pattern, thickness=thickness)

    def block(self, x, y, z, state: str):
        """Queues one block. Consecutive calls are merged into a single 'blocks' op."""
        if self._ops and self._ops[-1]["op"] == "blocks" and "origin" not in self._ops[-1]:
            self._ops[-1]["blocks"].append([x, y, z, state])
        else:
            self._ops.append({"op": "blocks", "blocks": [[x, y, z, state]]})
        return self

    def blocks(self, items: Iterable[Sequence[Any]], origin: Vec | None = None):
        """Queues [x, y, z, state] items; with origin they are offsets from it."""
        return self._queue("blocks", blocks=[list(i) for i in items], origin=list(origin) if origin else None)

    def paste(self, blueprint: dict | str | os.PathLike, origin: Vec, rotation: int = 0):
        """Queues a blueprint paste. A path is read here, so the file never has to reach the server's disk."""
        if not isinstance(blueprint, dict):
            blueprint = json.loads(Path(blueprint).read_text())
        return self._queue("paste", blueprint=blueprint, origin=list(origin), rotation=rotation)

    def flush(self, wait: bool = True) -> dict | None:
        """Sends everything queued as one batch (= one undo step). Returns the job result.

        On failure the queue is put back, so fix the cause (or `clear()`) and flush again. Blocks are validated
        before anything is placed, so an unknown block fails the whole batch with nothing built.
        """
        if not self._ops:
            return None
        ops, self._ops = self._ops, []
        try:
            result = self.call("batch", ops=ops)
            while wait and result.get("status") == "running":
                time.sleep(1)
                result = self.call("job_status", job=result["job"])
        except AIBuilderError:
            self._ops = ops + self._ops
            raise
        if result.get("status") == "failed":
            self._ops = ops + self._ops
            raise AIBuilderError(result.get("error", "job failed"), result)
        return result

    def clear(self) -> None:
        """Drops everything queued."""
        self._ops.clear()

    def __enter__(self) -> "Builder":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        if exc_type is None:
            self.flush()
        else:
            self._ops.clear()


def circle(center_x: int, center_z: int, radius: float, steps: int | None = None):
    """Yields (x, z) points on a circle, useful for crenellations, pillars and paths."""
    steps = steps or max(8, int(2 * math.pi * radius))
    seen = set()
    for i in range(steps):
        a = 2 * math.pi * i / steps
        p = (center_x + round(radius * math.cos(a)), center_z + round(radius * math.sin(a)))
        if p not in seen:
            seen.add(p)
            yield p
