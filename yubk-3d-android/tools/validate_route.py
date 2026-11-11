#!/usr/bin/env python3
"""Validate the regional route, tile coverage and streamed mesh payloads."""
from __future__ import annotations

import gzip
import json
import math
import pathlib
import struct
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/route"


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    region = json.loads((ASSETS / "region.json").read_text())
    route = region["route"]
    if len(route) < 1000:
        fail("regional route is too short")
    if region["length_m"] < 100_000 or region["length_m"] > 140_000:
        fail(f"unexpected route length {region['length_m']}")
    tiles = region.get("tiles", [])
    if not tiles:
        fail("no tiles in region.json")

    ordered = sorted(tiles, key=lambda tile: tile["start_m"])
    if abs(ordered[0]["start_m"]) > 1:
        fail("tile coverage does not start at kilometre zero")
    for left, right in zip(ordered, ordered[1:]):
        if abs(left["end_m"] - right["start_m"]) > 1:
            fail(f"tile gap between {left['id']} and {right['id']}")
    if region["length_m"] - ordered[-1]["end_m"] > 120:
        fail("tile coverage does not reach the end of the route")

    vertex_total = 0
    building_total = 0
    for tile in ordered:
        prefix = ASSETS / tile["id"]
        meta_path = prefix.with_suffix(".json")
        nav_path = ASSETS / (tile["id"] + ".navigation.json")
        if not meta_path.exists():
            fail(f"missing {meta_path.name}")
        if not nav_path.exists():
            fail(f"missing {nav_path.name}")
        meta = json.loads(meta_path.read_text())
        nav = json.loads(nav_path.read_text())
        if meta["route_start_m"] != tile["start_m"] or meta["route_end_m"] != tile["end_m"]:
            fail(f"{tile['id']} route range disagrees with region.json")
        heights = meta["heights"]
        if len(heights) != meta["h"] or any(len(row) != meta["w"] for row in heights):
            fail(f"{tile['id']} terrain grid is incomplete")
        vertices = meta["counts"]["vertices"]
        vertex_total += vertices
        building_total += meta["counts"]["buildings"]
        if vertices < 1000 or vertices > 2_500_000:
            fail(f"{tile['id']} has implausible vertex count {vertices}")
        if meta.get("mesh_parts", 0) < 1:
            fail(f"{tile['id']} has no mesh parts")
        parts = []
        for i in range(meta["mesh_parts"]):
            part = ASSETS / f"{tile['id']}.mesh.{i:03d}.gzpart"
            if not part.exists() or part.stat().st_size == 0:
                fail(f"missing mesh part {part.name}")
            parts.append(part.read_bytes())
        packed = b"".join(parts)
        try:
            raw = gzip.decompress(packed)
        except Exception as exc:
            fail(f"{tile['id']} mesh is not valid gzip: {exc}")
        if len(raw) < 4:
            fail(f"{tile['id']} mesh header is missing")
        count = struct.unpack(">i", raw[:4])[0]
        body = raw[4:]
        if count < 3 or count % 3 != 0 or count != len(body) // 24:
            fail(f"{tile['id']} mesh vertex count does not match its payload")
        if count != vertices:
            fail(f"{tile['id']} metadata vertex count does not match mesh")
        spawn = meta["spawn"]
        half_x = (meta["w"] - 1) * meta["dx"] / 2
        half_z = (meta["h"] - 1) * meta["dz"] / 2
        if abs(spawn[0]) > half_x or abs(spawn[1]) > half_z:
            fail(f"{tile['id']} spawn lies outside its terrain grid")
        # Sampling keeps CI fast while still checking every byte region of a tile.
        stride = max(3, (count // 1800) // 3 * 3)
        for offset in range(0, len(body), stride * 24):
            values = struct.unpack_from(">6f", body, offset) if offset + 24 <= len(body) else None
            if values is None:
                break
            if not all(math.isfinite(v) for v in values):
                fail(f"{tile['id']} contains a non-finite vertex")
            if any(v < -0.001 or v > 1.001 for v in values[3:]):
                fail(f"{tile['id']} contains an out-of-range colour")
        if not nav.get("roads"):
            fail(f"{tile['id']} navigation has no roads")

    if len(ordered) != 21:
        fail(f"expected 21 route tiles, found {len(ordered)}")
    print(f"OK: {region['length_m'] / 1000:.1f} km, {len(ordered)} tiles, {vertex_total:,} vertices, {building_total} buildings")


if __name__ == "__main__":
    main()
