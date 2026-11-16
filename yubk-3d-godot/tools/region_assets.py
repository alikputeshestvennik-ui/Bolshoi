#!/usr/bin/env python3
"""Bake the entire regional rectangle, independently of the highway corridor.

4096 m pages hold navigation/DEM metadata; 256 m cells hold bounded geometry
jobs. Geometry uses one shared metric origin, including across page boundaries.
The phone opens nearby manifests only and applies one global residency budget.
"""
from __future__ import annotations

import argparse
import gzip
import json
import math
import sys
from array import array
from collections import defaultdict
from pathlib import Path

from fetch_region import DATA, BOUNDS, shards
from mobile_assets import write_json, write_floats, partition_triangles, bake_map
from road_geometry import repair_geometry, ground_height, split_polygon, edge_planes
from scene_data import append_triangle, rebuild_water
from prepare_route import (SX, SZ, ORIGIN_LAT, ORIGIN_LON, xz, ll, dem_height,
                           clip_runs, densify, road_width, building_height,
                           triangulate, polygon_area, simplify_polygon, terrain_color)

ROOT = Path(__file__).resolve().parents[2]
DST = ROOT / "yubk-3d-godot/assets/route"
PAGE = 4096
CELL = 256
STEP = 32


def read_gzip(path):
    return json.loads(gzip.decompress(path.read_bytes()))


def grid_bounds():
    w, s, e, n = BOUNDS
    x0, z0 = xz(n, w)
    x1, z1 = xz(s, e)
    return (math.floor(x0 / PAGE), math.floor(z0 / PAGE),
            math.ceil(x1 / PAGE), math.ceil(z1 / PAGE))


def page_id(col, row):
    return f"area_{col + 32:02d}_{row + 32:02d}"


def join_rings(members, role):
    runs = [[(p["lat"], p["lon"]) for p in member.get("geometry", [])]
            for member in members if member.get("type") == "way" and member.get("role", "outer") == role]
    runs = [r for r in runs if len(r) >= 2]
    rings = []
    while runs:
        ring = runs.pop()
        while ring[0] != ring[-1]:
            for i, other in enumerate(runs):
                if ring[-1] == other[0]:
                    ring.extend(other[1:])
                elif ring[-1] == other[-1]:
                    ring.extend(other[-2::-1])
                elif ring[0] == other[-1]:
                    ring = other[:-1] + ring
                elif ring[0] == other[0]:
                    ring = other[:0:-1] + ring
                else:
                    continue
                runs.pop(i)
                break
            else:
                break
        if ring[0] == ring[-1] and len(ring) >= 4:
            rings.append([xz(*p) for p in ring[:-1]])
    return rings


def building_features(elements):
    """Preserve relation courtyards and avoid drawing tagged outer ways twice."""
    elements = list(elements)
    represented = {m["ref"] for e in elements if e["type"] == "relation"
                   for m in e.get("members", []) if m.get("type") == "way"}
    for e in elements:
        tags = e.get("tags", {})
        if tags.get("building", "no") == "no":
            continue
        if e["type"] == "way" and e["id"] not in represented:
            poly = [xz(p["lat"], p["lon"]) for p in e.get("geometry", [])]
            if len(poly) > 3 and poly[0] == poly[-1]:
                yield e["id"], poly[:-1], [], tags
        elif e["type"] == "relation":
            holes = join_rings(e.get("members", []), "inner")
            for poly in join_rings(e.get("members", []), "outer"):
                yield -e["id"], poly, holes, tags


def bbox(points):
    return min(p[0] for p in points), min(p[1] for p in points), max(p[0] for p in points), max(p[1] for p in points)


def page_keys(box, margin=0):
    x0, z0, x1, z1 = grid_bounds()
    for col in range(max(x0, math.floor((box[0] - margin) / PAGE)), min(x1 - 1, math.floor((box[2] + margin) / PAGE)) + 1):
        for row in range(max(z0, math.floor((box[1] - margin) / PAGE)), min(z1 - 1, math.floor((box[3] + margin) / PAGE)) + 1):
            yield col, row


def source_index():
    elements = {}
    for name, bounds in shards():
        path = DATA / (name + ".json.gz")
        if not path.exists():
            raise ValueError(f"Missing building coverage {path}; run fetch_region.py")
        shard = read_gzip(path)
        if shard.get("bbox") != bounds or not shard.get("elements") or shard.get("remark"):
            raise ValueError(f"Incomplete building coverage {path}")
        for e in shard["elements"]:
            elements[e["type"], e["id"]] = e
    buildings = defaultdict(list)
    total = 0
    for feature in building_features(elements.values()):
        for key in page_keys(bbox(feature[1]), margin=32):
            buildings[key].append(feature)
        total += 1
    roads = defaultdict(list)
    region = read_gzip(ROOT / "yubk-3d-android/data/region_osm.json.gz")
    for e in region["elements"]:
        tags = e.get("tags", {})
        if e["type"] != "way" or not tags.get("highway") or tags.get("area") == "yes":
            continue
        if tags["highway"] in ("proposed", "construction", "abandoned", "platform", "razed"):
            continue
        points = [xz(p["lat"], p["lon"]) for p in e.get("geometry", [])]
        if len(points) < 2:
            continue
        for key in page_keys(bbox(points)):
            roads[key].append((e["id"], points, tags))
    print(f"Indexed {total} building outlines and {sum(len(v) for v in roads.values())} road/page intersections", flush=True)
    return buildings, roads, total


def make_meta(col, row):
    cx, cz = (col + 0.5) * PAGE, (row + 0.5) * PAGE
    lat, lon = ll(cx, cz)
    side = PAGE // STEP + 1
    heights = [[round(max(0, dem_height(*ll(col * PAGE + c * STEP, row * PAGE + r * STEP))), 3)
                for c in range(side)] for r in range(side)]
    return {"name": "Южный Крым", "origin": [lat, lon], "w": side, "h": side,
            "dx": STEP, "dz": STEP, "heights": heights, "buildings": [],
            "center_x": cx, "center_z": cz, "col": col, "row": row}


def terrain(meta, stride=1):
    out = array("f")
    side = meta["w"]
    for r in range(0, side - 1, stride):
        for c in range(0, side - 1, stride):
            rr, cc = min(side - 1, r + stride), min(side - 1, c + stride)
            for row, col in ((r, c), (rr, c), (r, cc), (r, cc), (rr, c), (rr, cc)):
                y = meta["heights"][row][col]
                x, z = col * STEP - PAGE / 2, row * STEP - PAGE / 2
                color = terrain_color(x + meta["center_x"], z + meta["center_z"], y, 0)
                out.extend((x, y, z, *color))
    return out


def quad(out, a, b, c, d, color):
    append_triangle(out, (a, b, c), color)
    append_triangle(out, (a, c, d), color)


def bake_roads(vertices, meta, features):
    roads, streets = [], []
    cx, cz = meta["center_x"], meta["center_z"]
    for _, points, tags in features:
        local = [(x - cx, z - cz) for x, z in points]
        name = tags.get("name:ru") or tags.get("name") or "Дорога / дорожка"
        width = road_width(tags)
        pathlike = tags["highway"] in ("path", "footway", "steps", "track", "cycleway")
        color = (0.53, 0.47, 0.35) if pathlike else (0.39, 0.40, 0.41)
        raised = 5.0 if tags.get("bridge") in ("yes", "viaduct") else 0.0
        for run in clip_runs(local, (-PAGE / 2, -PAGE / 2, PAGE / 2, PAGE / 2)):
            dense = densify(run, 8)
            for a, b in zip(dense, dense[1:]):
                length = math.dist(a, b)
                if length < 0.01:
                    continue
                roads.append([round(a[0], 3), round(a[1], 3), round(b[0], 3), round(b[1], 3), name])
                nx, nz = -(b[1] - a[1]) / length, (b[0] - a[0]) / length
                ya, yb = ground_height(meta, *a) + raised, ground_height(meta, *b) + raised
                for half, offset, tint in ((width / 2 + 0.55, 0.20, (0.53, 0.50, 0.42)), (width / 2, 0.34, color)):
                    quad(vertices, (a[0] - nx * half, ya + offset, a[1] - nz * half),
                         (b[0] - nx * half, yb + offset, b[1] - nz * half),
                         (b[0] + nx * half, yb + offset, b[1] + nz * half),
                         (a[0] + nx * half, ya + offset, a[1] + nz * half), tint)
            if tags.get("name") or tags.get("name:ru"):
                a, b = run[0], run[-1]
                streets.append({"name": name, "lx": (a[0] + b[0]) / 2 + cx,
                                "lz": (a[1] + b[1]) / 2 + cz, "heading": math.degrees(math.atan2(b[0] - a[0], a[1] - b[1]))})
    return roads, streets


def roof_triangles(poly, holes):
    pieces = [[(x, 0, z) for x, z in tri] for tri in triangulate(poly)]
    for hole in holes:
        for tri in triangulate(hole):
            planes = edge_planes([(x, 0, z) for x, z in tri])
            remaining = []
            for piece in pieces:
                inside = piece
                for plane in planes:
                    if not inside:
                        break
                    inside, outside = split_polygon(inside, plane)
                    if outside:
                        remaining.append(outside)
            pieces = remaining
    for piece in pieces:
        for i in range(1, len(piece) - 1):
            yield [(p[0], p[2]) for p in (piece[0], piece[i], piece[i + 1])]


def bake_buildings(vertices, meta, features):
    cx, cz = meta["center_x"], meta["center_z"]
    count = 0
    for osm_id, outline, holes, tags in features:
        # Store collisions in every page touched by the building. Render once.
        poly = simplify_polygon([(x - cx, z - cz) for x, z in outline], 0.5)
        if len(poly) < 3 or abs(polygon_area(poly)) < 2:
            continue
        meta["buildings"].append([[x + cx, z + cz] for x, z in poly])
        mx, mz = (sum(p[i] for p in outline) / len(outline) for i in (0, 1))
        if (math.floor(mx / PAGE), math.floor(mz / PAGE)) != (meta["col"], meta["row"]):
            continue
        local_holes = [[(x - cx, z - cz) for x, z in h] for h in holes]
        base = max(ground_height(meta, x, z) for x, z in poly)
        top = base + building_height(tags)
        tint = (abs(osm_id) % 7) * 0.012
        facade = (0.77 + tint, 0.72 + tint, 0.63 + tint)
        raw_color = tags.get("building:colour", "")
        if len(raw_color) == 7 and raw_color.startswith("#"):
            try:
                facade = tuple(int(raw_color[i:i + 2], 16) / 255 for i in (1, 3, 5))
            except ValueError:
                pass
        for ring in [poly] + local_holes:
            for a, b in zip(ring, ring[1:] + ring[:1]):
                shade = 0.77 + 0.19 * abs(b[0] - a[0]) / max(0.01, math.dist(a, b))
                quad(vertices, (a[0], ground_height(meta, *a) - 0.5, a[1]),
                     (b[0], ground_height(meta, *b) - 0.5, b[1]),
                     (b[0], top, b[1]), (a[0], top, a[1]), tuple(v * shade for v in facade))
        for tri in roof_triangles(poly, local_holes):
            append_triangle(vertices, [(x, top, z) for x, z in tri], (0.43, 0.34, 0.29))
        count += 1
    return count


def shift_vertices(vertices, cx, cz, stride=6):
    for off in range(0, len(vertices), stride):
        vertices[off] += cx
        vertices[off + 2] += cz


def bake_page(col, row, buildings, roads):
    tile_id = page_id(col, row)
    meta = make_meta(col, row)
    vertices = terrain(meta)
    nav, streets = bake_roads(vertices, meta, roads)
    vertices, surfaces, stats = repair_geometry(vertices, meta, nav)
    vertices, _ = rebuild_water(vertices, meta)
    count = bake_buildings(vertices, meta, buildings)
    cx, cz = meta["center_x"], meta["center_z"]
    shift_vertices(vertices, cx, cz)
    shift_vertices(surfaces, cx, cz, 3)
    for road in nav:
        road[0] += cx
        road[1] += cz
        road[2] += cx
        road[3] += cz
    meta["origin"] = [ORIGIN_LAT, ORIGIN_LON]
    meta["regional"] = True
    meta["counts"] = {"buildings": count, "road_segments": len(nav), "vertices": len(vertices) // 6}
    meta["streets"] = streets
    folder = DST / (tile_id + "_chunks")
    folder.mkdir(exist_ok=True)
    chunks = []
    for (x, z), part, bounds, block in partition_triangles(vertices, cell_m=CELL):
        # Sea has a coarse surface already; do not make empty ocean detail jobs.
        if max(block[1::6]) < 0.7:
            continue
        file = f"{folder.name}/{x}_{z}_{part}.bin.gz"
        write_floats(DST / file, block)
        chunks.append({"file": file, "bounds": bounds, "cell": [x, z], "vertices": len(block) // 6})
    write_json(DST / (tile_id + ".json"), meta)
    write_json(DST / (tile_id + ".navigation.json"), {"roads": nav})
    write_json(DST / (tile_id + ".stream.json"), {"version": 1, "cell_m": CELL, "chunks": chunks})
    write_floats(DST / (tile_id + ".roads.bin.gz"), surfaces)
    return meta["counts"], streets


def build_background():
    x0, z0, x1, z1 = grid_bounds()
    out = array("f")
    step = 512
    for z in range(z0 * PAGE, z1 * PAGE, step):
        for x in range(x0 * PAGE, x1 * PAGE, step):
            for px, pz in ((x, z), (x, z + step), (x + step, z), (x + step, z), (x, z + step), (x + step, z + step)):
                height = max(0, dem_height(*ll(px, pz)))
                out.extend((px, height - 2, pz, *terrain_color(px, pz, height, 0)))
    write_floats(DST / "region.far.bin.gz", out)
    return len(out) // 6


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--page", nargs=2, type=int, help="Bake one page for development")
    args = parser.parse_args(argv)
    DST.mkdir(parents=True, exist_ok=True)
    buildings, roads, total = source_index()
    x0, z0, x1, z1 = grid_bounds()
    counts = defaultdict(int)
    streets, tiles = [], []
    for row in range(z0, z1):
        for col in range(x0, x1):
            tile_id = page_id(col, row)
            tiles.append({"id": tile_id, "col": col, "row": row, "name": "Южный Крым",
                          "origin": [ORIGIN_LAT, ORIGIN_LON], "x0": col * PAGE, "z0": row * PAGE,
                          "x1": (col + 1) * PAGE, "z1": (row + 1) * PAGE})
            if args.page and args.page != [col, row]:
                continue
            stats, labels = bake_page(col, row, buildings[col, row], roads[col, row])
            streets.extend({**s, "tile": tile_id} for s in labels)
            for key, value in stats.items():
                counts[key] += value
            print(tile_id, stats, flush=True)
    if args.page:
        return
    far_count = build_background()
    # Reuse the attributed cartographic overview, replacing route-only 3D pages.
    region = json.loads((ROOT / "yubk-3d-android/app/src/main/assets/route/region.json").read_text())
    region.update({"tiles": tiles, "streets": streets, "spatial_grid": {
        "page_m": PAGE, "cell_m": CELL, "min_col": x0, "min_row": z0, "max_col": x1, "max_row": z1,
        "far_vertices": far_count}, "coverage": {"bounds": BOUNDS, "source_buildings": total, **counts}})
    write_json(DST / "region.json", region)
    bake_map(DST)
    write_json(DST / "coverage.json", region["coverage"])
    print("REGIONAL_COVERAGE", dict(counts), "far vertices", far_count, flush=True)


if __name__ == "__main__":
    main()
