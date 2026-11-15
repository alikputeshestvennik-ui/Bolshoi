#!/usr/bin/env python3
"""Prepare Godot res:// assets from the existing Android route assets.

The Java renderer gzip-compresses each tile and then splits that single gzip
stream into fixed-size .gzpart slices. This script reassembles those slices,
decompresses once, byte-swaps the vertex floats to little-endian (the format
Godot's PackedByteArray.to_float32_array expects) and writes one gzip blob per
tile. Small JSON/PNG metadata files are copied unchanged.
"""
from __future__ import annotations

import argparse
import gzip
import json
import shutil
import struct
import sys
from array import array
from pathlib import Path

from road_geometry import repair_geometry

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "yubk-3d-android" / "app" / "src" / "main" / "assets" / "route"
DST = ROOT / "yubk-3d-godot" / "assets" / "route"

def convert_tile(tile_id: str) -> int:
    parts = sorted(SRC.glob(f"{tile_id}.mesh.*.gzpart"))
    if not parts:
        return 0
    compressed = b"".join(p.read_bytes() for p in parts)
    raw = gzip.decompress(compressed)
    if len(raw) < 4:
        return 0
    count = struct.unpack(">i", raw[:4])[0]
    if count <= 0 or count % 3 != 0:
        raise ValueError(f"{tile_id}: bad vertex count {count}")
    n = count * 6
    if len(raw) != 4 + n * 4:
        raise ValueError(f"{tile_id}: truncated or oversized mesh")
    vertices = array("f")
    vertices.frombytes(raw[4:])
    if sys.byteorder == "little":
        vertices.byteswap()
    del raw, compressed
    meta = json.loads((SRC / f"{tile_id}.json").read_text())
    navigation = json.loads((SRC / f"{tile_id}.navigation.json").read_text())
    vertices, surfaces, stats = repair_geometry(vertices, meta, navigation["roads"])
    print(f"{tile_id}: repaired {stats}", flush=True)
    if sys.byteorder != "little":
        vertices.byteswap()
        surfaces.byteswap()
    for suffix, data in [("mesh.bin.gz", vertices), ("roads.bin.gz", surfaces)]:
        (DST / f"{tile_id}.{suffix}").write_bytes(gzip.compress(data.tobytes(), compresslevel=6, mtime=0))
    return len(vertices) // 6


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tiles", nargs="+", help="Prepare only these tile IDs (default: all)")
    args = parser.parse_args()
    DST.mkdir(parents=True, exist_ok=True)

    for name in ["region.json", "region_map.png"]:
        src = SRC / name
        if src.exists():
            shutil.copyfile(src, DST / name)

    for pattern in ["tile_*.json", "tile_*.navigation.json"]:
        for src in sorted(SRC.glob(pattern)):
            shutil.copyfile(src, DST / src.name)

    tile_ids = sorted({p.name.split(".mesh.")[0] for p in SRC.glob("tile_*.mesh.000.gzpart")})
    if args.tiles:
        missing = set(args.tiles) - set(tile_ids)
        if missing:
            parser.error(f"Unknown tiles: {sorted(missing)}")
        tile_ids = [tile_id for tile_id in tile_ids if tile_id in args.tiles]
    total = 0
    for tile_id in tile_ids:
        count = convert_tile(tile_id)
        total += count
        print(f"{tile_id}: {count} vertices")
    print(f"total vertices: {total}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
