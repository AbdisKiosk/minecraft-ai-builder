"""Thin client for the AIBuilder REST API.

Scripts get the same tools as the MCP server, but block data never passes through Claude's context:
Claude writes a short script, the script expands it into thousands of blocks.

    from aibuilder import Builder, box, SELECTION

    b = Builder.connect()                  # AIBUILDER_URL / AIBUILDER_TOKEN from the environment
    b.walls(SELECTION, "80%stone_bricks,20%andesite")
    b.block(10, 64, 10, "lantern")
    b.flush()                              # everything queued so far = one undo step

Keep scripts short, they are what Claude pays for: `b.at(origin)` makes coordinates small offsets, and
`Vox` (see vox.py) collects blocks locally with box/ring/weighted patterns and connects fences and panes.

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

from .vox import Vox, pick

__all__ = ["Builder", "AIBuilderError", "Vox", "box", "circle", "decode", "expand_layers", "pick", "SELECTION"]

SELECTION = "selection"

Vec = Sequence[Any]  # [x, y, z]; parts may be ints or "~n" relative strings


class AIBuilderError(RuntimeError):
    """A tool call failed (unknown block, outside build zone, server unreachable, ...).

    `payload` is the server's full answer when there was one, e.g. {"status": "failed", "changed": 0, "error": "..."}.
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
        self._origin: list | None = None

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

    def check(self, region, checks: str | None = None) -> dict:
        """Build mistakes as {"issues": [{"kind", "count", "at", "hint"}]}; checks="floating,dark" runs only those."""
        return self.call("check_region", region=region, checks=checks)

    def render(self, region, view: str = "both", save_to: str | os.PathLike | None = None,
               size: str | None = None, corner: str | None = None) -> bytes:
        """PNG of a region (absolute coordinates). size: "small", "medium" (default) or "large".
        view: "both", "top", "iso" or an elevation "north"/"south"/"east"/"west"; corner: "se" (default), "sw", "ne"
        or "nw", where the iso camera stands.

        Looking at the picture costs Claude about width*height/750 tokens, so use "small" for progress checks.
        """
        png = base64.b64decode(self.call("render_region", region=region, view=view, size=size,
                                         corner=corner)["png_base64"])
        if save_to:
            Path(save_to).write_bytes(png)
        return png

    def undo(self, steps: int = 1) -> int:
        return self.call("undo", steps=steps)["undone"]

    def decorate(self, items: list | None = None, origin: Vec | None = None, remove=None) -> dict:
        """Sign text, container contents, item frames and armor stands; runs now, not queued.

        Each item is {"at": [x, y, z]} plus one of text=[lines], contents={item: count}, frame=item with
        facing, or stand={yaw, head, chest, legs, feet, hand, arms, small, base}. `remove` (a region) first
        deletes item frames and armor stands in it. NOT undoable: `undo` does not revert it, so flush the blocks
        it needs (signs, chests, walls) before calling. Returns {"set": n, "spawned": n, "removed": n}, zeros left out.
        """
        return self.call("decorate", items=items, origin=list(origin) if origin is not None else None, remove=remove)

    # ---- edits (queued until flush) ----

    def at(self, origin: Vec | None) -> "Builder":
        """Makes the coordinates of queued edits offsets from `origin` ([x, y, z], "~" allowed); None turns it off.

        Applies to a whole flush, so anything already queued is sent first. Reads and renders stay absolute.
        """
        self.flush()
        self._origin = list(origin) if origin is not None else None
        return self

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

    def paste(self, blueprint: dict | str | os.PathLike | None = None, origin: Vec | None = None,
              rotation: int = 0, name: str | None = None):
        """Queues a blueprint paste: a dict, a path, or name= of one saved with save_blueprint.

        A .json path is read here, so the file never has to reach the server's disk; a .schem/.schematic path
        is read by the server's WorldEdit, so it must be a file the server can see.
        """
        if origin is None:
            raise AIBuilderError("paste needs an origin")
        if name is not None:
            return self._queue("paste", name=name, origin=list(origin), rotation=rotation)
        if not isinstance(blueprint, dict):
            path = Path(blueprint)
            if path.suffix in (".schem", ".schematic"):
                return self._queue("paste", path=str(path.resolve()), origin=list(origin), rotation=rotation)
            blueprint = json.loads(path.read_text())
        return self._queue("paste", blueprint=blueprint, origin=list(origin), rotation=rotation)

    def clone(self, region, to: Vec, rotation: int = 0, flip: str | None = None, repeat: int = 1,
              step: Vec | None = None, air: bool = False):
        """Queues a copy of a built region with its min corner at `to`; flip="x"|"z" mirrors before rotating.

        repeat > 1 places more copies, each `step` ([dx, dy, dz], never shifted by at()) further on. air=True
        copies air too instead of merging with what is there. Sign text and container contents are not copied.
        """
        return self._queue("clone", region=region, to=list(to), rotation=rotation or None, flip=flip,
                           repeat=repeat if repeat != 1 else None, step=list(step) if step else None, air=air or None)

    def save_blueprint(self, name: str, region) -> dict:
        """Saves a region on the server ({"saved", "size", "blocks"}); paste(name=...) places it.

        Runs immediately: flush() first if the region is still queued.
        """
        return self.call("save_blueprint", name=name, region=region)

    def flush(self, wait: bool = True) -> dict | None:
        """Sends everything queued as one batch (= one undo step). Returns the result, {"changed": n} when done.

        On failure the queue is put back, so fix the cause (or `clear()`) and flush again. Blocks are validated
        before anything is placed, so an unknown block fails the whole batch with nothing built.
        """
        if not self._ops:
            return None
        ops, self._ops = self._ops, []
        try:
            result = self.call("batch", ops=ops, origin=self._origin)
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


def expand_layers(layers: list[list[str]]) -> list[list[str]]:
    """Blueprint layers with the "^" / "3^" row and layer repeats written out."""
    out: list[list[str]] = []
    for layer in layers:
        if layer and layer[0].endswith("^"):
            out.extend([out[-1]] * int(layer[0][:-1] or 1))
            continue
        rows: list[str] = []
        for row in layer:
            if row.endswith("^"):
                rows.extend([rows[-1]] * int(row[:-1] or 1))
            else:
                rows.append(row)
        out.append(rows)
    return out


def decode(read: dict) -> dict[tuple[int, int, int], str]:
    """A `read(region, mode="full")` result as {(x, y, z): state} in world coordinates."""
    ox, oy, oz = read["origin"]
    out = {}
    for y, layer in enumerate(expand_layers(read["layers"])):
        for z, row in enumerate(layer):
            x, count = 0, ""
            for ch in row:
                if ch.isdigit():
                    count += ch
                    continue
                for _ in range(int(count or 1)):
                    out[(ox + x, oy + y, oz + z)] = read["palette"][ch]
                    x += 1
                count = ""
    return out
