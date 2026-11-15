"""Bake spatial mobile assets; never build or repair the world on the phone.

XYZRGB triangles are owned by exactly one 128 m cell, split into bounded jobs.
The map uses packed float pairs instead of hundreds of thousands of JSON lists.
No BeamNG code, assets, network services or telemetry are embedded.
"""
from __future__ import annotations

import gzip
import json
import math
import sys
from array import array
from collections import defaultdict
from pathlib import Path

CELL_M = 128.0
MAX_CHUNK_VERTICES = 18000  # 432 KB decoded, at most 6000 triangles per upload
MAP_CELL_M = 4096.0


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, separators=(",", ":")))


def write_floats(path, values):
    data = array("f", values)
    if sys.byteorder != "little":
        data.byteswap()
    path.write_bytes(gzip.compress(data.tobytes(), compresslevel=6, mtime=0))


def partition_triangles(vertices, cell_m=CELL_M, max_vertices=MAX_CHUNK_VERTICES):
    if len(vertices) % 18 or max_vertices < 3 or max_vertices % 3:
        raise ValueError("XYZRGB data and chunk limits must contain whole triangles")
    cells = defaultdict(lambda: array("f"))
    for off in range(0, len(vertices), 18):
        x = sum(vertices[off + k] for k in (0, 6, 12)) / 3
        z = sum(vertices[off + k] for k in (2, 8, 14)) / 3
        cells[math.floor(x / cell_m), math.floor(z / cell_m)].extend(vertices[off:off + 18])
    for cell, data in sorted(cells.items()):
        for part, start in enumerate(range(0, len(data), max_vertices * 6)):
            block = data[start:start + max_vertices * 6]
            xs, zs = block[0::6], block[2::6]
            yield cell, part, [min(xs), min(zs), max(xs), max(zs)], block


def coarse_terrain(meta, stride=4):
    """Distant terrain only. Near cells keep the repaired pavement and terrain."""
    out = array("f")
    cols = sorted(set(range(0, meta["w"], stride)) | {meta["w"] - 1})
    rows = sorted(set(range(0, meta["h"], stride)) | {meta["h"] - 1})
    hx, hz = (meta["w"] - 1) / 2, (meta["h"] - 1) / 2
    for r, rr in zip(rows, rows[1:]):
        for c, cc in zip(cols, cols[1:]):
            for row, col in ((r, c), (rr, c), (r, cc), (r, cc), (rr, c), (rr, cc)):
                height = meta["heights"][row][col]
                color = (0.12, 0.35, 0.45) if height < 0.7 else (0.34, 0.41, 0.28)
                # A small inset prevents z fighting with the original near terrain.
                out.extend(((col - hx) * meta["dx"], height - 2.0, (row - hz) * meta["dz"], *color))
    return out


def bake_tile(dst: Path, tile_id: str, vertices, meta):
    folder = dst / (tile_id + "_chunks")
    folder.mkdir(exist_ok=True)
    for old in folder.glob("*.gz"):
        old.unlink()
    chunks = []
    for (x, z), part, bounds, block in partition_triangles(vertices):
        name = f"{x}_{z}_{part}.bin.gz"
        write_floats(folder / name, block)
        chunks.append({"file": folder.name + "/" + name, "bounds": bounds, "cell": [x, z], "vertices": len(block) // 6})
    background = coarse_terrain(meta)
    write_floats(dst / (tile_id + ".far.bin.gz"), background)
    write_json(dst / (tile_id + ".stream.json"), {
        "version": 1, "cell_m": CELL_M, "max_chunk_vertices": MAX_CHUNK_VERTICES,
        "total_vertices": len(vertices) // 6, "far_vertices": len(background) // 6, "chunks": chunks,
    })
    return len(chunks)


def simplify_run(points, tolerance):
    if len(points) <= 2 or tolerance <= 0:
        return points
    a, b = points[0], points[-1]
    dx, dz = b[0] - a[0], b[1] - a[1]
    length = dx * dx + dz * dz
    farthest, distance = 0, 0.0
    for i, p in enumerate(points[1:-1], 1):
        t = max(0, min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dz) / length)) if length else 0
        d = math.hypot(p[0] - a[0] - dx * t, p[1] - a[1] - dz * t)
        if d > distance:
            farthest, distance = i, d
    if distance <= tolerance:
        return [a, b]
    return simplify_run(points[:farthest + 1], tolerance)[:-1] + simplify_run(points[farthest:], tolerance)


def road_runs(segments):
    run = []
    for ax, az, bx, bz, *_ in segments:
        if run and run[-1] != (ax, az):
            yield run
            run = []
        if not run:
            run.append((ax, az))
        run.append((bx, bz))
    if run:
        yield run


def bake_map(dst: Path):
    region = json.loads((dst / "region.json").read_text())
    batches, packed = [], array("f")
    for group in region.pop("road_groups", []):
        runs = list(road_runs(group["segments"]))
        for low, high, tolerance in [(1, 4, 25.0), (4, 12, 6.0), (12, 65, 0.0)]:
            cells = defaultdict(lambda: array("f"))
            for run in runs:
                simplified = simplify_run(run, tolerance)
                for (ax, az), (bx, bz) in zip(simplified, simplified[1:]):
                    cell = math.floor((ax + bx) / 2 / MAP_CELL_M), math.floor((az + bz) / 2 / MAP_CELL_M)
                    cells[cell].extend((ax, az, bx, bz))
            for _, data in sorted(cells.items()):
                xs, zs = data[0::2], data[1::2]
                batches.append({"kind": group["kind"], "start": len(packed), "count": len(data),
                                "lod_min": low, "lod_max": high,
                                "bounds": [min(xs), min(zs), max(xs), max(zs)]})
                packed.extend(data)
    region["roads"] = []
    region["mobile_map"] = {"version": 1, "float_count": len(packed), "batches": batches}
    write_json(dst / "region.mobile.json", region)
    write_floats(dst / "region.lines.bin.gz", packed)
    return len(packed) // 4, len(batches)


if __name__ == "__main__":
    # Reuse already repaired assets during development, without rebuilding roads.
    dst = Path(__file__).resolve().parents[1] / "assets" / "route"
    print("map", bake_map(dst), flush=True)
    for path in sorted(dst.glob("tile_*.mesh.bin.gz")):
        vertices = array("f")
        vertices.frombytes(gzip.decompress(path.read_bytes()))
        if sys.byteorder != "little":
            vertices.byteswap()
        tile_id = path.name.split(".")[0]
        print(tile_id, bake_tile(dst, tile_id, vertices, json.loads((dst / (tile_id + ".json")).read_text())), flush=True)
