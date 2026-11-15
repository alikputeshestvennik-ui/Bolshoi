#!/usr/bin/env python3
"""Prepare Godot res:// assets from the existing Android route assets.

The Java renderer gzip-compresses each tile and then splits that single gzip
stream into fixed-size .gzpart slices. This script reassembles those slices,
decompresses once, byte-swaps the vertex floats to little-endian (the format
Godot's PackedByteArray.to_float32_array expects) and writes one gzip blob per
tile. Small JSON/PNG metadata files are copied unchanged.
"""
from __future__ import annotations

import gzip
import shutil
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "yubk-3d-android" / "app" / "src" / "main" / "assets" / "route"
DST = ROOT / "yubk-3d-godot" / "assets" / "route"

CHUNK_FLOATS = 8192


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
    body = raw[4:4 + n * 4]
    out = bytearray(n * 4)
    pos = 0
    while pos < n:
        stop = min(n, pos + CHUNK_FLOATS)
        chunk = body[pos * 4:stop * 4]
        vals = struct.unpack(f">{len(chunk) // 4}f", chunk)
        out[pos * 4:stop * 4] = struct.pack(f"<{len(vals)}f", *vals)
        pos = stop
    with gzip.open(DST / f"{tile_id}.mesh.bin.gz", "wb", compresslevel=6) as fh:
        fh.write(out)
    return count


def main() -> int:
    DST.mkdir(parents=True, exist_ok=True)

    for name in ["region.json", "region_map.png"]:
        src = SRC / name
        if src.exists():
            shutil.copyfile(src, DST / name)

    for pattern in ["tile_*.json", "tile_*.navigation.json"]:
        for src in sorted(SRC.glob(pattern)):
            shutil.copyfile(src, DST / src.name)

    tile_ids = sorted({p.name.split(".mesh.")[0] for p in SRC.glob("tile_*.mesh.000.gzpart")})
    total = 0
    for tile_id in tile_ids:
        count = convert_tile(tile_id)
        total += count
        print(f"{tile_id}: {count} vertices")
    print(f"total vertices: {total}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
