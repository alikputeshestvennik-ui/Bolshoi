"""Recover real OSM footprints and drape waterways during offline preparation.

Uses the repository's attributed OSM extracts. No BeamNG code or services.
"""
from __future__ import annotations

import gzip
import json
import math
import sys
import xml.etree.ElementTree as ET
from array import array
from functools import lru_cache
from pathlib import Path

from road_geometry import ground_height, edge_planes, split_polygon

ANDROID = Path(__file__).resolve().parents[2] / "yubk-3d-android"
sys.path.insert(0, str(ANDROID / "tools"))
from prepare_route import SX, SZ, triangulate, densify, clip_runs, building_height


@lru_cache(maxsize=1)
def yalta_buildings():
    root = ET.fromstring(gzip.decompress((ANDROID / "data/yalta.osm.gz").read_bytes()))
    nodes = {n.get("id"): (float(n.get("lat")), float(n.get("lon"))) for n in root.findall("node")}
    buildings = []
    for way in root.findall("way"):
        tags = {t.get("k"): t.get("v") for t in way.findall("tag")}
        refs = [n.get("ref") for n in way.findall("nd")]
        if tags.get("building", "no") == "no" or len(refs) < 4 or refs[0] != refs[-1]:
            continue
        if all(ref in nodes for ref in refs):
            buildings.append((int(way.get("id")), [nodes[ref] for ref in refs[:-1]], tags))
    return buildings


def append_triangle(vertices, points, color):
    for p in points:
        vertices.extend((*p, *color))


def recover_buildings(vertices, meta):
    """Add missing footprints as bounded, inexpensive prisms, without a tile cap."""
    lat, lon = meta["origin"]
    half_x = (meta["w"] - 1) * meta["dx"] / 2
    half_z = (meta["h"] - 1) * meta["dz"] / 2
    existing = set()
    for poly in meta.get("buildings", []):
        # Legacy polygons may repeat the closing point.
        points = poly[:-1] if poly[0] == poly[-1] else poly
        if points:
            existing.add((round(sum(p[0] for p in points) / len(points)),
                          round(sum(p[1] for p in points) / len(points))))
    added = 0
    for osm_id, points, tags in yalta_buildings():
        poly = [((lo - lon) * SX, (lat - la) * SZ) for la, lo in points]
        cx, cz = (sum(p[i] for p in poly) / len(poly) for i in (0, 1))
        if not (-half_x <= cx <= half_x and -half_z <= cz <= half_z):
            continue
        if any((round(cx) + dx, round(cz) + dz) in existing for dx in range(-2, 3) for dz in range(-2, 3)):
            continue
        roof = triangulate(poly)
        if not roof:
            continue
        base = max(ground_height(meta, x, z) for x, z in poly)
        top = base + building_height(tags)
        tint = (osm_id % 7) * 0.015
        for a, b in zip(poly, poly[1:] + poly[:1]):
            shade = 0.78 + 0.17 * abs(b[0] - a[0]) / max(0.01, math.dist(a, b))
            color = tuple(v * shade for v in (0.78 + tint, 0.73 + tint, 0.64 + tint))
            pa = (a[0], ground_height(meta, *a) - 0.3, a[1])
            pb = (b[0], ground_height(meta, *b) - 0.3, b[1])
            qa, qb = (a[0], top, a[1]), (b[0], top, b[1])
            append_triangle(vertices, (pa, pb, qb), color)
            append_triangle(vertices, (pa, qb, qa), color)
        for tri in roof:
            append_triangle(vertices, [(x, top, z) for x, z in tri], (0.42, 0.33, 0.28))
        meta.setdefault("buildings", []).append(poly)
        added += 1
    meta["recovered_osm_buildings"] = added
    return added


@lru_cache(maxsize=1)
def waterways():
    return json.loads(gzip.decompress((ANDROID / "data/water_osm.json.gz").read_bytes()))["elements"]


def drape_polygon(out, poly, meta):
    """Clip to DEM triangles so water cannot disappear under a sloping grid cell."""
    dx, dz = meta["dx"], meta["dz"]
    hx, hz = (meta["w"] - 1) * dx / 2, (meta["h"] - 1) * dz / 2
    c0 = max(0, math.floor((min(p[0] for p in poly) + hx) / dx))
    c1 = min(meta["w"] - 2, math.floor((max(p[0] for p in poly) + hx) / dx))
    r0 = max(0, math.floor((min(p[1] for p in poly) + hz) / dz))
    r1 = min(meta["h"] - 2, math.floor((max(p[1] for p in poly) + hz) / dz))
    for r in range(r0, r1 + 1):
        for c in range(c0, c1 + 1):
            x, z = c * dx - hx, r * dz - hz
            for tri in [[(x, 0, z), (x, 0, z + dz), (x + dx, 0, z)],
                        [(x + dx, 0, z), (x, 0, z + dz), (x + dx, 0, z + dz)]]:
                clipped = [(p[0], 0, p[1]) for p in poly]
                for plane in edge_planes(tri):
                    if not clipped:
                        break
                    clipped, _ = split_polygon(clipped, plane)
                for i in range(1, len(clipped) - 1):
                    points = [clipped[j] for j in (0, i, i + 1)]
                    area = abs((points[1][0] - points[0][0]) * (points[2][2] - points[0][2]) -
                               (points[1][2] - points[0][2]) * (points[2][0] - points[0][0]))
                    if area > 1e-7:
                        append_triangle(out, [(p[0], ground_height(meta, p[0], p[2]) + 0.14, p[2]) for p in points],
                                        (0.16, 0.43, 0.53))


def rebuild_water(vertices, meta):
    terrain_end = (meta["w"] - 1) * (meta["h"] - 1) * 36
    out = array("f", vertices[:terrain_end])
    removed = 0
    for off in range(terrain_end, len(vertices), 18):
        r, g, b = vertices[off + 3:off + 6]
        if r > 0 and abs(g / r - 3.3) < 0.002 and abs(b / r - 4.5) < 0.002:
            removed += 1
        else:
            out.extend(vertices[off:off + 18])
    lat, lon = meta["origin"]
    hx, hz = (meta["w"] - 1) * meta["dx"] / 2, (meta["h"] - 1) * meta["dz"] / 2
    widths = {"river": 8.0, "stream": 3.0, "canal": 5.0, "drain": 2.0, "drystream": 2.0}
    count = 0
    for el in waterways():
        tags = el.get("tags", {})
        points = [((p["lon"] - lon) * SX, (lat - p["lat"]) * SZ) for p in el.get("geometry", [])]
        if len(points) < 2 or max(p[0] for p in points) < -hx or min(p[0] for p in points) > hx or max(p[1] for p in points) < -hz or min(p[1] for p in points) > hz:
            continue
        closed = len(points) > 3 and math.dist(points[0], points[-1]) < 0.8
        if closed and (tags.get("natural") == "water" or tags.get("water") or tags.get("waterway")):
            # Shorelines follow their measured polygon instead of rectangular DEM cells.
            for tri in triangulate(points):
                drape_polygon(out, tri, meta)
            count += 1
            continue
        if tags.get("waterway") not in widths:
            continue
        try:
            width = max(0.8, min(35.0, float(tags.get("width", widths[tags["waterway"]]))))
        except (TypeError, ValueError):
            width = widths[tags["waterway"]]
        for run in clip_runs(points, (-hx, -hz, hx, hz)):
            # Shared cross sections at bends close gaps between consecutive ribbons.
            dense = densify(run, 8.0)
            edges = []
            for i, p in enumerate(dense):
                a, b = dense[max(0, i - 1)], dense[min(len(dense) - 1, i + 1)]
                length = math.dist(a, b)
                nx, nz = (-(b[1] - a[1]) / length, (b[0] - a[0]) / length) if length else (0, 1)
                edges.append(((p[0] - nx * width / 2, p[1] - nz * width / 2),
                              (p[0] + nx * width / 2, p[1] + nz * width / 2)))
            for a, b in zip(edges, edges[1:]):
                drape_polygon(out, [a[0], b[0], b[1], a[1]], meta)
            count += 1
    return out, {"removed_water_triangles": removed, "water_features": count}
