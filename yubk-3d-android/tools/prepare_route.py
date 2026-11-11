#!/usr/bin/env python3
"""Build the South Coast regional map and streamed 3D route tiles.

Inputs (cached, not part of the APK):
  data/route_yubk.json          ordered Sevastopol-Alushta road centreline
  data/region_osm.json.gz      OSM major roads, coastline, places and landuse
  .cache/N44E033.hgt.gz        Skadi 1 arc-second elevations
  .cache/N44E034.hgt.gz
  .cache/route_osm/chunks.json route chunks and padded bounding boxes
  .cache/route_osm/chunk_NN.json  OSM buildings/roads around each chunk

Outputs:
  app/src/main/assets/route/region.json
  app/src/main/assets/route/region_map.png
  app/src/main/assets/route/tile_NN.json
  app/src/main/assets/route/tile_NN.mesh.NNN.gzpart
"""
from __future__ import annotations

import argparse
import gzip
import json
import math
import pathlib
import random
import struct
import sys
import zlib
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parents[1]
CACHE = ROOT / ".cache"
ASSETS = ROOT / "app/src/main/assets"
ROUTE_DIR = ASSETS / "route"

REGION_BOUNDS = (33.35, 44.35, 34.50, 44.75)
ORIGIN_LAT, ORIGIN_LON = 44.55, 33.95
GRID_STEP = 25.0
ROAD_STEP_MAIN = 3.0
ROAD_STEP_OTHER = 6.0
CORRIDOR_M = 520.0
MAX_WINDOWS_PER_TILE = 4200

# WGS84 metres per degree at the latitude of the South Coast.
_phi = math.radians(44.55)
_a, _e2 = 6378137.0, 0.00669437999014
SX = _a / math.sqrt(1 - _e2 * math.sin(_phi) ** 2) * math.cos(_phi) * math.pi / 180
SZ = _a * (1 - _e2) / (1 - _e2 * math.sin(_phi) ** 2) ** 1.5 * math.pi / 180


def xz(lat: float, lon: float) -> tuple[float, float]:
    return (lon - ORIGIN_LON) * SX, (ORIGIN_LAT - lat) * SZ


def ll(x: float, z: float) -> tuple[float, float]:
    return ORIGIN_LAT - z / SZ, ORIGIN_LON + x / SX


def dist(a: tuple[float, float], b: tuple[float, float]) -> float:
    return math.hypot(a[0] - b[0], a[1] - b[1])


def mix(a: float, b: float, t: float) -> float:
    return a + (b - a) * max(0.0, min(1.0, t))


def mix3(a, b, t):
    return tuple(mix(a[i], b[i], t) for i in range(3))


def stable_hash(value: int) -> int:
    value = (value ^ 61) ^ (value >> 16)
    value = value + (value << 3)
    value = value ^ (value >> 4)
    value = value * 0x27D4EB2D
    value = value ^ (value >> 15)
    return value & 0x7FFFFFFF


def clamp(x: float, a: float, b: float) -> float:
    return max(a, min(b, x))


_dem: dict[int, tuple[bytes, int]] = {}


def dem_tile(lon: int) -> tuple[bytes, int]:
    if lon not in _dem:
        path = CACHE / f"N44E{lon:03d}.hgt.gz"
        if not path.exists():
            raise SystemExit(f"missing DEM tile {path}; run prepare_terrain.py first")
        raw = gzip.decompress(path.read_bytes())
        side = math.isqrt(len(raw) // 2)
        if side * side * 2 != len(raw):
            raise SystemExit(f"invalid DEM tile {path}")
        _dem[lon] = (raw, side)
    return _dem[lon]


def dem_height(lat: float, lon: float) -> float:
    lon_index = math.floor(lon)
    raw, side = dem_tile(lon_index)
    xx = (lon - lon_index) * (side - 1)
    yy = (45.0 - lat) * (side - 1)
    ix, iy = int(xx), int(yy)
    fx, fy = xx - ix, yy - iy
    ix = max(0, min(side - 2, ix))
    iy = max(0, min(side - 2, iy))
    q = [struct.unpack_from(">h", raw, 2 * ((iy + j) * side + ix + i))[0] for j in range(2) for i in range(2)]
    if any(v == -32768 for v in q):
        raise ValueError(f"DEM void at {lat}, {lon}")
    return (q[0] * (1 - fx) + q[1] * fx) * (1 - fy) + (q[2] * (1 - fx) + q[3] * fx) * fy


def line_length(points):
    return sum(dist(a, b) for a, b in zip(points, points[1:]))


def catmull_rom(points, step: float):
    """Densify a polyline while retaining the original curve direction."""
    if len(points) < 2:
        return list(points)
    out = [points[0]]
    for i in range(len(points) - 1):
        p0 = points[i - 1] if i else points[i]
        p1, p2 = points[i], points[i + 1]
        p3 = points[i + 2] if i + 2 < len(points) else p2
        seg = dist(p1, p2)
        n = max(1, int(math.ceil(seg / step)))
        for j in range(1, n + 1):
            t = j / n
            t2, t3 = t * t, t * t * t
            x = 0.5 * ((2 * p1[0]) + (-p0[0] + p2[0]) * t + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2 + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3)
            z = 0.5 * ((2 * p1[1]) + (-p0[1] + p2[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2 + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3)
            out.append((x, z))
    return out


def smooth(values, radius=3):
    if len(values) < 3:
        return list(values)
    prefix = [0.0]
    for value in values:
        prefix.append(prefix[-1] + value)
    out = []
    for i in range(len(values)):
        lo, hi = max(0, i - radius), min(len(values), i + radius + 1)
        out.append((prefix[hi] - prefix[lo]) / (hi - lo))
    return out


def clip_segment(a, b, bounds):
    """Liang-Barsky clipping. Returns a clipped segment or None."""
    xmin, zmin, xmax, zmax = bounds
    x1, z1 = a
    x2, z2 = b
    dx, dz = x2 - x1, z2 - z1
    t0, t1 = 0.0, 1.0
    for p, q in ((-dx, x1 - xmin), (dx, xmax - x1), (-dz, z1 - zmin), (dz, zmax - z1)):
        if abs(p) < 1e-12:
            if q < 0:
                return None
            continue
        r = q / p
        if p < 0:
            if r > t1:
                return None
            if r > t0:
                t0 = r
        else:
            if r < t0:
                return None
            if r < t1:
                t1 = r
    if t1 - t0 < 1e-8:
        return None
    return (x1 + t0 * dx, z1 + t0 * dz), (x1 + t1 * dx, z1 + t1 * dz)


def clip_runs(points, bounds):
    if len(points) < 2:
        return []
    runs = []
    current = None
    for a, b in zip(points, points[1:]):
        clipped = clip_segment(a, b, bounds)
        if clipped is None:
            if current and len(current) > 1:
                runs.append(current)
            current = None
            continue
        ca, cb = clipped
        if current is None or dist(current[-1], ca) > 0.8:
            if current and len(current) > 1:
                runs.append(current)
            current = [ca, cb]
        else:
            current.append(cb)
    if current and len(current) > 1:
        runs.append(current)
    return runs


def polygon_area(poly):
    return abs(sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(poly, poly[1:] + poly[:1]))) * 0.5


def simplify_polygon(poly, tolerance=0.8):
    if len(poly) <= 4:
        return poly
    closed = poly + [poly[0]]
    keep = {0, len(closed) - 1}

    def ramer(points, first, last):
        ax, az = points[first]
        bx, bz = points[last]
        dx, dz = bx - ax, bz - az
        denom = dx * dx + dz * dz
        best, index = -1.0, None
        for i in range(first + 1, last):
            px, pz = points[i]
            t = 0.0 if denom == 0 else clamp(((px - ax) * dx + (pz - az) * dz) / denom, 0.0, 1.0)
            dd = math.hypot(px - ax - t * dx, pz - az - t * dz)
            if dd > best:
                best, index = dd, i
        if index is not None and best > tolerance:
            keep.add(index)
            ramer(points, first, index)
            ramer(points, index, last)

    ramer(closed, 0, len(closed) - 1)
    out = [closed[i] for i in sorted(keep)]
    if out[-1] == out[0]:
        out.pop()
    return out if len(out) >= 3 else poly


def triangulate(poly):
    p = list(poly)
    if sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(p, p[1:] + p[:1])) < 0:
        p.reverse()

    def cross(a, b, c):
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])

    out = []
    guard = 0
    while len(p) > 3 and guard < len(poly) * len(poly):
        guard += 1
        found = False
        for i in range(len(p)):
            a, b, c = p[i - 1], p[i], p[(i + 1) % len(p)]
            if cross(a, b, c) <= 1e-7:
                continue
            if any(cross(a, b, q) >= 0 and cross(b, c, q) >= 0 and cross(c, a, q) >= 0 for q in p if q not in (a, b, c)):
                continue
            out.append((a, b, c))
            p.pop(i)
            found = True
            break
        if not found:
            return []
    return out + ([tuple(p)] if len(p) == 3 else [])


def point_in_poly(x, z, poly):
    hit = False
    for a, b in zip(poly, poly[1:] + poly[:1]):
        if (a[1] > z) != (b[1] > z) and x < (b[0] - a[0]) * (z - a[1]) / (b[1] - a[1]) + a[0]:
            hit = not hit
    return hit


def point_line_distance(p, a, b):
    vx, vz = b[0] - a[0], b[1] - a[1]
    ll2 = vx * vx + vz * vz
    t = 0.0 if ll2 == 0 else clamp(((p[0] - a[0]) * vx + (p[1] - a[1]) * vz) / ll2, 0.0, 1.0)
    return math.hypot(p[0] - a[0] - t * vx, p[1] - a[1] - t * vz)


def road_width(tags):
    try:
        if tags.get("width"):
            return clamp(float(str(tags["width"]).replace(" m", "")), 2.0, 24.0)
    except ValueError:
        pass
    lanes = 0
    for key in ("lanes", "lanes:forward", "lanes:backward"):
        try:
            lanes = max(lanes, int(float(tags.get(key, 0))))
        except ValueError:
            pass
    cls = tags.get("highway", "residential")
    if lanes:
        return clamp(lanes * 3.35, 4.0, 24.0)
    return {
        "trunk": 8.0,
        "primary": 7.5,
        "secondary": 6.5,
        "tertiary": 6.0,
        "residential": 5.2,
        "unclassified": 5.0,
        "living_street": 4.8,
        "service": 3.5,
        "pedestrian": 4.5,
        "footway": 2.2,
        "path": 1.8,
        "cycleway": 2.2,
    }.get(cls, 4.5)


def building_height(tags):
    raw = tags.get("height") or tags.get("building:height")
    if raw:
        try:
            return clamp(float(str(raw).replace(" m", "").replace(",", ".")), 2.5, 90.0)
        except ValueError:
            pass
    try:
        levels = float(str(tags.get("building:levels", "3")).replace(",", "."))
        return clamp(levels * 3.0, 2.5, 90.0)
    except ValueError:
        return 9.0


FACADES = {
    "residential": [(0.78, 0.72, 0.61), (0.84, 0.79, 0.68), (0.73, 0.70, 0.63), (0.87, 0.82, 0.72), (0.72, 0.66, 0.56)],
    "house": [(0.87, 0.81, 0.69), (0.82, 0.77, 0.67), (0.90, 0.85, 0.75), (0.75, 0.70, 0.61), (0.86, 0.78, 0.64)],
    "commercial": [(0.68, 0.71, 0.72), (0.76, 0.75, 0.70), (0.60, 0.64, 0.67)],
    "industrial": [(0.62, 0.61, 0.56), (0.69, 0.66, 0.59), (0.55, 0.57, 0.57)],
    "hotel": [(0.85, 0.76, 0.59), (0.78, 0.69, 0.54), (0.90, 0.82, 0.68)],
    "school": [(0.80, 0.66, 0.47), (0.73, 0.59, 0.40), (0.88, 0.75, 0.54)],
    "public": [(0.78, 0.73, 0.62), (0.71, 0.68, 0.58)],
}
ROOFS = [(0.48, 0.24, 0.17), (0.39, 0.29, 0.23), (0.31, 0.34, 0.33), (0.55, 0.31, 0.20), (0.42, 0.40, 0.35)]


class Mesh:
    def __init__(self):
        self.base = bytearray()
        self.windows = defaultdict(bytearray)

    @staticmethod
    def shade(a, b, c):
        ux, uy, uz = b[0] - a[0], b[1] - a[1], b[2] - a[2]
        vx, vy, vz = c[0] - a[0], c[1] - a[1], c[2] - a[2]
        nx, ny, nz = uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx
        length = math.sqrt(nx * nx + ny * ny + nz * nz)
        if length < 1e-10:
            return 0.8
        dot = (nx * -0.46 + ny * 0.78 + nz * 0.43) / length
        return 0.58 + 0.42 * max(0.0, dot)

    def vertex(self, target, p, color):
        target.extend(struct.pack(">6f", p[0], p[1], p[2], clamp(color[0], 0, 1), clamp(color[1], 0, 1), clamp(color[2], 0, 1)))

    def tri(self, a, b, c, color, target=None):
        target = self.base if target is None else target
        s = self.shade(a, b, c)
        shade_color = (color[0] * s, color[1] * s, color[2] * s)
        for p in (a, b, c):
            self.vertex(target, p, shade_color)

    def tri_rgb(self, a, b, c, ca, cb, cc, target=None):
        target = self.base if target is None else target
        self.vertex(target, a, ca)
        self.vertex(target, b, cb)
        self.vertex(target, c, cc)

    def quad(self, a, b, c, d, color, target=None):
        self.tri(a, b, c, color, target)
        self.tri(a, c, d, color, target)

    def quad_rgb(self, a, b, c, d, ca, cb, cc, cd, target=None):
        self.tri_rgb(a, b, c, ca, cb, cc, target)
        self.tri_rgb(a, c, d, ca, cc, cd, target)


def strip(mesh, a, b, half, y, color, target=None):
    length = math.hypot(b[0] - a[0], b[1] - a[1])
    if length < 1e-6:
        return
    nx, nz = -(b[1] - a[1]) / length * half, (b[0] - a[0]) / length * half
    mesh.quad((a[0] - nx, y(a[0] - nx, a[1] - nz), a[1] - nz), (b[0] - nx, y(b[0] - nx, b[1] - nz), b[1] - nz), (b[0] + nx, y(b[0] + nx, b[1] + nz), b[1] + nz), (a[0] + nx, y(a[0] + nx, a[1] + nz), a[1] + nz), color, target)


def strip2(mesh, a, b, half, offset, ya, yb, color):
    length = math.hypot(b[0] - a[0], b[1] - a[1])
    if length < 1e-6:
        return
    nx, nz = -(b[1] - a[1]) / length * half, (b[0] - a[0]) / length * half
    mesh.quad(
        (a[0] - nx, ya + offset, a[1] - nz),
        (b[0] - nx, yb + offset, b[1] - nz),
        (b[0] + nx, yb + offset, b[1] + nz),
        (a[0] + nx, ya + offset, a[1] + nz),
        color,
    )


class RoadIndex:
    """Small spatial index so building/tree filtering does not scan every road."""
    def __init__(self, cell=100.0):
        self.cell = cell
        self.buckets = defaultdict(list)

    def key(self, x, z):
        return int(math.floor(x / self.cell)), int(math.floor(z / self.cell))

    def add(self, run):
        for a, b in zip(run, run[1:]):
            minx, minz = min(a[0], b[0]), min(a[1], b[1])
            maxx, maxz = max(a[0], b[0]), max(a[1], b[1])
            for cx in range(int(math.floor(minx / self.cell)), int(math.floor(maxx / self.cell)) + 1):
                for cz in range(int(math.floor(minz / self.cell)), int(math.floor(maxz / self.cell)) + 1):
                    self.buckets[(cx, cz)].append((a, b))

    def distance(self, p):
        best = 1e9
        cx, cz = self.key(*p)
        for ix in range(cx - 1, cx + 2):
            for iz in range(cz - 1, cz + 2):
                for a, b in self.buckets.get((ix, iz), ()):
                    best = min(best, point_line_distance(p, a, b))
        return best


def terrain_color(x, z, raw, slope):
    if raw < 0:
        depth = clamp(-raw / 80.0, 0.0, 1.0)
        return mix3((0.20, 0.46, 0.53), (0.035, 0.13, 0.24), depth)
    if raw < 3.0:
        base = mix3((0.59, 0.53, 0.39), (0.48, 0.51, 0.33), clamp(raw / 3.0, 0, 1))
    elif raw < 80:
        base = mix3((0.42, 0.48, 0.29), (0.34, 0.42, 0.24), clamp((raw - 3) / 77, 0, 1))
    elif raw < 350:
        base = mix3((0.34, 0.39, 0.23), (0.44, 0.42, 0.30), clamp((raw - 80) / 270, 0, 1))
    else:
        base = mix3((0.44, 0.42, 0.34), (0.52, 0.51, 0.46), clamp((raw - 350) / 650, 0, 1))
    rock = clamp((slope - 0.42) / 0.55, 0.0, 1.0)
    base = mix3(base, (0.50, 0.47, 0.40), rock * 0.75)
    noise = math.sin(x * 0.071 + z * 0.037) * 0.012 + math.sin(x * 0.019 - z * 0.083) * 0.009
    return tuple(clamp(v + noise, 0, 1) for v in base)


def load_route():
    path = ROOT / "data/route_yubk.json"
    if not path.exists():
        raise SystemExit(f"missing {path}")
    raw = json.loads(path.read_text())
    return [(float(p[0]), float(p[1])) for p in raw]


def load_region_osm():
    path = ROOT / "data/region_osm.json.gz"
    if not path.exists():
        raise SystemExit(f"missing {path}")
    return json.loads(gzip.decompress(path.read_bytes()))


def load_chunks(route):
    meta_path = CACHE / "route_osm/chunks.json"
    if not meta_path.exists():
        return []
    chunks = json.loads(meta_path.read_text())
    out = []
    for item in chunks:
        n, start, end, south, west, north, east = item
        path = CACHE / f"route_osm/chunk_{n:02d}.json"
        if not path.exists():
            print(f"WARN: missing route chunk {n:02d}", file=sys.stderr)
            continue
        out.append((n, start, end, (south, west, north, east), path))
    return out


def merge_osm(chunks):
    ways = {}
    for _, _, _, _, path in chunks:
        data = json.loads(path.read_bytes())
        for element in data.get("elements", []):
            if element.get("type") == "way" and element.get("geometry"):
                ways[element["id"]] = element
    return list(ways.values())


def find_tile_for_distance(cumulative, distance):
    for i, d in enumerate(cumulative):
        if d >= distance:
            return i
    return len(cumulative) - 1


def heading_at(route, i):
    a = route[max(0, i - 1)]
    b = route[min(len(route) - 1, i + 1)]
    return math.degrees(math.atan2(b[0] - a[0], -(b[1] - a[1])))


def place_name_for_tile(chunks, places):
    names = []
    for item in chunks:
        n, start, end, bbox, path = item
        mid = (start + end) * 500.0
        pass
    return None


def build_region(route, region_osm, chunks):
    rxz = [xz(lat, lon) for lat, lon in route]
    cumulative = [0.0]
    for a, b in zip(rxz, rxz[1:]):
        cumulative.append(cumulative[-1] + math.hypot(b[0] - a[0], b[1] - a[1]))
    total = cumulative[-1]

    # Exact OSM coastline, kept as polylines for the 2D map overlay.
    coast = []
    for element in region_osm.get("elements", []):
        tags = element.get("tags", {})
        if element.get("type") == "way" and tags.get("natural") == "coastline" and element.get("geometry"):
            coast.append([list(xz(p["lat"], p["lon"])) for p in element["geometry"]])

    places = []
    seen = set()
    for element in region_osm.get("elements", []):
        tags = element.get("tags", {})
        if element.get("type") != "node" or "place" not in tags:
            continue
        name = tags.get("name:ru") or tags.get("name")
        if not name or name in seen:
            continue
        seen.add(name)
        gx, gz = xz(element["lat"], element["lon"])
        nearest = min(range(len(rxz)), key=lambda i: (rxz[i][0] - gx) ** 2 + (rxz[i][1] - gz) ** 2)
        if math.hypot(rxz[nearest][0] - gx, rxz[nearest][1] - gz) > 6500:
            continue
        places.append({"name": name, "x": gx, "z": gz, "kind": tags["place"], "route_m": cumulative[nearest]})

    sights = [
        ("Севастополь", 44.6054, 33.5221, "city"),
        ("Балаклава", 44.5121, 33.5990, "town"),
        ("Байдарские ворота", 44.4061, 33.7841, "sight"),
        ("Форосская церковь", 44.4050, 33.7922, "sight"),
        ("Ласточкино гнездо", 44.4305, 34.1287, "sight"),
        ("Воронцовский дворец", 44.4194, 34.0557, "sight"),
        ("Ливадийский дворец", 44.4669, 34.1418, "sight"),
        ("Ялта", 44.4988, 34.1689, "city"),
        ("Гурзуф", 44.5479, 34.2737, "town"),
        ("Артек", 44.5440, 34.2970, "sight"),
        ("Алушта", 44.6771, 34.4095, "town"),
    ]
    existing = {p["name"] for p in places}
    for name, lat, lon, kind in sights:
        if name in existing:
            continue
        gx, gz = xz(lat, lon)
        nearest = min(range(len(rxz)), key=lambda i: (rxz[i][0] - gx) ** 2 + (rxz[i][1] - gz) ** 2)
        places.append({"name": name, "x": gx, "z": gz, "kind": kind, "route_m": cumulative[nearest]})
    places.sort(key=lambda p: (p["route_m"], p["name"]))

    # Regional major-road overlay. The highlighted route is drawn by the app.
    roads = []
    seen_ways = set()
    for element in region_osm.get("elements", []):
        tags = element.get("tags", {})
        if element.get("type") != "way" or tags.get("highway") not in ("trunk", "primary", "secondary", "tertiary"):
            continue
        if element["id"] in seen_ways or not element.get("geometry"):
            continue
        seen_ways.add(element["id"])
        points = [xz(p["lat"], p["lon"]) for p in element["geometry"]]
        if len(points) > 1:
            roads.append([[round(a, 1), round(b, 1), round(c, 1), round(d, 1)] for (a, b), (c, d) in zip(points, points[1:])])

    tiles = []
    for n, start, end, (south, west, north, east), path in chunks:
        gx0, gz0 = xz(north, west)
        gx1, gz1 = xz(south, east)
        mid_lat, mid_lon = (south + north) / 2, (west + east) / 2
        mid_distance = (start + end) * 500.0
        idx = find_tile_for_distance(cumulative, mid_distance)
        # Name a section after the nearest settlement/landmark, if one is close.
        near = min(places, key=lambda p: abs(p["route_m"] - mid_distance), default=None)
        label = f"ЮБК · {start:.0f}–{end:.0f} км"
        if near and abs(near["route_m"] - mid_distance) < 12000:
            label = f"{near['name']} · {start:.0f}–{end:.0f} км"
        tiles.append({
            "id": f"tile_{n:02d}", "name": label, "start_m": start * 1000.0, "end_m": end * 1000.0,
            "bbox": [west, south, east, north], "x0": gx0, "z0": gz0, "x1": gx1, "z1": gz1,
            "origin": [mid_lat, mid_lon],
        })

    # A compact route polyline for drawing and nearest-point search.
    route_map = []
    step_accum = 0.0
    for i, (x, z) in enumerate(rxz):
        if i == 0 or cumulative[i] - step_accum >= 20.0 or i == len(rxz) - 1:
            route_map.append([round(x, 1), round(z, 1), round(heading_at(rxz, i), 1), round(cumulative[i], 1)])
            step_accum = cumulative[i]

    region = {
        "name": "ЮБК · Севастополь — Алушта",
        "bounds": list(REGION_BOUNDS),
        "origin": [ORIGIN_LAT, ORIGIN_LON],
        "sx": SX,
        "sz": SZ,
        "length_m": total,
        "route": route_map,
        "coast": coast,
        "roads": roads,
        "places": places,
        "tiles": tiles,
        "map": "route/region_map.png",
        "source": "© OpenStreetMap contributors, ODbL 1.0; Mapzen/Tilezen Skadi (SRTM)", 
    }
    ROUTE_DIR.mkdir(parents=True, exist_ok=True)
    (ROUTE_DIR / "region.json").write_text(json.dumps(region, ensure_ascii=False, separators=(",", ":")))
    headings = [heading_at(rxz, i) for i in range(len(rxz))]
    return region, rxz, cumulative, headings


def write_png(path, width, height, rgb: bytes):
    raw = b"".join(b"\x00" + rgb[y * width * 3:(y + 1) * width * 3] for y in range(height))
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    path.write_bytes(png)


def build_region_map(region_osm):
    """Render a hill-shaded regional backdrop and road context as a PNG."""
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        print("PIL is required for region_map.png", file=sys.stderr)
        return
    bounds = REGION_BOUNDS
    west, south, east, north = bounds
    W, H = 1200, 576
    image = Image.new("RGB", (W, H))
    pixels = image.load()
    step_x = (east - west) / (W - 1)
    step_z = (north - south) / (H - 1)
    for row in range(H):
        lat = north - row * step_z
        for col in range(W):
            lon = west + col * step_x
            h = dem_height(lat, lon)
            hx = dem_height(lat, lon + step_x)
            hz = dem_height(lat - step_z, lon)
            dx = (hx - h) / max(1e-9, step_x * SX)
            dz = (hz - h) / max(1e-9, step_z * SZ)
            nx, ny, nz = -dx, 1.0, -dz
            norm = math.sqrt(nx * nx + ny * ny + nz * nz)
            shade = 0.70 + 0.30 * max(0.0, (nx * -0.5 + ny * 0.76 + nz * 0.41) / norm)
            if h < 0:
                depth = clamp(-h / 220.0, 0, 1)
                color = mix3((0.23, 0.48, 0.56), (0.035, 0.12, 0.24), depth)
            elif h < 5:
                color = mix3((0.63, 0.58, 0.43), (0.51, 0.53, 0.35), clamp(h / 5, 0, 1))
            elif h < 90:
                color = mix3((0.45, 0.50, 0.31), (0.33, 0.42, 0.24), clamp((h - 5) / 85, 0, 1))
            elif h < 400:
                color = mix3((0.36, 0.40, 0.24), (0.48, 0.45, 0.32), clamp((h - 90) / 310, 0, 1))
            else:
                color = mix3((0.48, 0.46, 0.39), (0.58, 0.57, 0.52), clamp((h - 400) / 800, 0, 1))
            pixels[col, row] = tuple(int(clamp(v * shade, 0, 1) * 255) for v in color)
    draw = ImageDraw.Draw(image, "RGBA")
    # Urban and rural settlement tint from OSM landuse.
    for element in region_osm.get("elements", []):
        tags = element.get("tags", {})
        if element.get("type") != "way" or tags.get("landuse") not in ("residential", "commercial", "industrial", "retail") or not element.get("geometry"):
            continue
        pts = []
        for p in element["geometry"]:
            px = (p["lon"] - west) / (east - west) * (W - 1)
            py = (north - p["lat"]) / (north - south) * (H - 1)
            pts.append((px, py))
        if len(pts) > 2:
            draw.polygon(pts, fill=(120, 120, 110, 52 if tags.get("landuse") == "residential" else 70))
    # Major roads; the highlighted route is drawn by Android on top.
    road_colors = {"trunk": (216, 193, 142, 220), "primary": (207, 188, 145, 200), "secondary": (195, 188, 160, 180), "tertiary": (187, 184, 165, 150)}
    widths = {"trunk": 4, "primary": 3, "secondary": 2, "tertiary": 1}
    for element in region_osm.get("elements", []):
        tags = element.get("tags", {})
        cls = tags.get("highway")
        if element.get("type") != "way" or cls not in road_colors or not element.get("geometry"):
            continue
        pts = []
        for p in element["geometry"]:
            px = (p["lon"] - west) / (east - west) * (W - 1)
            py = (north - p["lat"]) / (north - south) * (H - 1)
            pts.append((px, py))
        if len(pts) > 1:
            draw.line(pts, fill=road_colors[cls], width=widths[cls], joint="curve")
    # Coastline accent.
    for element in region_osm.get("elements", []):
        tags = element.get("tags", {})
        if element.get("type") != "way" or tags.get("natural") != "coastline" or not element.get("geometry"):
            continue
        pts = []
        for p in element["geometry"]:
            px = (p["lon"] - west) / (east - west) * (W - 1)
            py = (north - p["lat"]) / (north - south) * (H - 1)
            pts.append((px, py))
        if len(pts) > 1:
            draw.line(pts, fill=(221, 230, 206, 220), width=2)
    image.save(ROUTE_DIR / "region_map.png", optimize=True)


def choose_spawn(rxz, cumulative, tile_start, tile_end, tile_bounds, origin_lat, origin_lon):
    mid = (tile_start + tile_end) * 500.0
    idx = min(range(len(cumulative)), key=lambda i: abs(cumulative[i] - mid))
    # Prefer the first route point well inside the tile, then the nearest midpoint.
    for i in range(len(rxz)):
        if tile_start * 1000 + 30 <= cumulative[i] <= tile_end * 1000 - 30:
            x, z = rxz[i]
            xl = x + (ORIGIN_LON - origin_lon) * SX
            zl = z + (origin_lat - ORIGIN_LAT) * SZ
            if tile_bounds[0] <= xl <= tile_bounds[2] and tile_bounds[1] <= zl <= tile_bounds[3]:
                return i
    return idx


def build_tile(item, rxz, cumulative, headings, ways):
    n, start, end, (south, west, north, east), _ = item
    origin_lat, origin_lon = (south + north) / 2, (west + east) / 2

    def local(lat, lon):
        return (lon - origin_lon) * SX, (origin_lat - lat) * SZ

    def local_from_global(gx, gz):
        return gx + (ORIGIN_LON - origin_lon) * SX, gz + (origin_lat - ORIGIN_LAT) * SZ

    # Terrain grid, 25 m. Cap the grid so a hairpin does not create a huge tile.
    span_x = (east - west) * SX
    span_z = (north - south) * SZ
    step = GRID_STEP
    while (span_x / step + 1) * (span_z / step + 1) > 90000:
        step *= 1.25
    w = max(3, int(math.ceil(span_x / step)) + 1)
    h = max(3, int(math.ceil(span_z / step)) + 1)
    dx, dz = span_x / (w - 1), span_z / (h - 1)
    xs = [-(w - 1) * dx / 2 + c * dx for c in range(w)]
    zs = [-(h - 1) * dz / 2 + r * dz for r in range(h)]
    raw_h = [[0.0] * w for _ in range(h)]
    for r, z in enumerate(zs):
        lat = origin_lat - z / SZ
        for c, x in enumerate(xs):
            lon = origin_lon + x / SX
            raw_h[r][c] = dem_height(lat, lon)
    ground = [[max(0.0, raw_h[r][c]) for c in range(w)] for r in range(h)]

    def ground_at(x, z):
        u = clamp((x + (w - 1) * dx / 2) / dx, 0, w - 1.00001)
        v = clamp((z + (h - 1) * dz / 2) / dz, 0, h - 1.00001)
        c, r = int(u), int(v)
        u -= c
        v -= r
        aa, bb = ground[r][c], ground[r][c + 1]
        cc, dd = ground[r + 1][c], ground[r + 1][c + 1]
        return aa + (bb - aa) * u + (cc - aa) * v if u + v <= 1 else dd + (cc - dd) * (1 - u) + (bb - dd) * (1 - v)

    mesh = Mesh()
    xmin, xmax = xs[0], xs[-1]
    zmin, zmax = zs[0], zs[-1]
    tile_bounds = (xmin - 0.01, zmin - 0.01, xmax + 0.01, zmax + 0.01)
    route_local = []
    for gx, gz in rxz:
        xl, zl = local_from_global(gx, gz)
        if xmin + 2 <= xl <= xmax - 2 and zmin + 2 <= zl <= zmax - 2:
            route_local.append((xl, zl))
    route_index = RoadIndex(80.0)
    for a, b in zip(route_local, route_local[1:]):
        route_index.add([a, b])

    def near_route(p, limit=CORRIDOR_M):
        return route_index.distance(p) < limit

    # Smooth shaded terrain, with bathymetry expressed as water at sea level.
    for r in range(h - 1):
        for c in range(w - 1):
            p00 = (xs[c], max(0.0, raw_h[r][c]), zs[r])
            p10 = (xs[c + 1], max(0.0, raw_h[r][c + 1]), zs[r])
            p01 = (xs[c], max(0.0, raw_h[r + 1][c]), zs[r + 1])
            p11 = (xs[c + 1], max(0.0, raw_h[r + 1][c + 1]), zs[r + 1])

            def slope(cc0, rr0):
                l = raw_h[rr0][max(0, cc0 - 1)]
                rr = raw_h[rr0][min(w - 1, cc0 + 1)]
                uu = raw_h[max(0, rr0 - 1)][cc0]
                dd = raw_h[min(h - 1, rr0 + 1)][cc0]
                return math.hypot((rr - l) / (2 * dx), (dd - uu) / (2 * dz))

            c00 = terrain_color(xs[c], zs[r], raw_h[r][c], slope(c, r))
            c10 = terrain_color(xs[c + 1], zs[r], raw_h[r][c + 1], slope(c + 1, r))
            c01 = terrain_color(xs[c], zs[r + 1], raw_h[r + 1][c], slope(c, r + 1))
            c11 = terrain_color(xs[c + 1], zs[r + 1], raw_h[r + 1][c + 1], slope(c + 1, r + 1))
            mesh.tri_rgb(p00, p01, p10, c00, c01, c10)
            mesh.tri_rgb(p10, p01, p11, c10, c01, c11)

    # OSM traffic ways: clip to the tile, then draw layered road ribbons.
    roads_all = []
    buildings = []
    road_index = RoadIndex()
    for element in ways:
        tags = element.get("tags", {})
        points = []
        for p in element.get("geometry", []):
            points.append(local(p["lat"], p["lon"]))
        if not points:
            continue
        if "building" in tags and tags.get("building") != "no":
            cx = sum(p[0] for p in points) / len(points)
            cz = sum(p[1] for p in points) / len(points)
            if xmin - 30 <= cx <= xmax + 30 and zmin - 30 <= cz <= zmax + 30:
                buildings.append((element["id"], points, tags))
            continue
        if tags.get("highway") and tags.get("highway") not in ("construction", "proposed"):
            # Keep a compact local copy for the walk minimap and collision/POI logic.
            for run in clip_runs(points, tile_bounds):
                if line_length(run) > 1.5:
                    roads_all.append((element["id"], run, tags))
                    road_index.add(run)

    roads_all = [item for item in roads_all if any(near_route(p) for p in item[1])]
    buildings = [item for item in buildings if near_route((sum(p[0] for p in item[1]) / len(item[1]), sum(p[1] for p in item[1]) / len(item[1])), CORRIDOR_M + 80)]

    # Main route is the highest-detail ribbon; other ways are rendered as context.
    main_names = {"Южнобережное шоссе"}
    main_refs = ("35А-002", "35К-002", "67К-1", "М-18", "А-291")
    drawn_way_ids = set()
    for way_id, run, tags in sorted(roads_all, key=lambda v: (v[2].get("highway") not in ("trunk", "primary"), v[0])):
        if way_id in drawn_way_ids:
            continue
        drawn_way_ids.add(way_id)
        name = tags.get("name:ru") or tags.get("name") or ""
        ref = tags.get("ref") or ""
        is_main = name in main_names or any(x in ref for x in main_refs)
        cls = tags.get("highway", "residential")
        step = ROAD_STEP_MAIN if is_main else ROAD_STEP_OTHER
        dense = catmull_rom(run, step)
        if len(dense) < 2:
            continue
        heights = [ground_at(x, z) for x, z in dense]
        heights = smooth(heights, 3 if is_main else 1)
        widths = road_width(tags)
        half = widths / 2
        oneway = tags.get("oneway") in ("yes", "1", "true")
        surface = (tags.get("surface") or "").lower()
        if "footway" in cls or "path" in cls:
            asphalt = (0.58, 0.52, 0.41)
            shoulder = (0.48, 0.46, 0.36)
        elif surface in ("unpaved", "gravel", "ground", "dirt", "compacted"):
            asphalt = (0.49, 0.42, 0.30)
            shoulder = (0.43, 0.38, 0.28)
        else:
            asphalt = (0.33, 0.34, 0.35) if is_main else (0.36, 0.36, 0.35)
            shoulder = (0.53, 0.50, 0.42)
        sidewalk = tags.get("sidewalk") in ("both", "left", "right", "yes") or (is_main and cls in ("trunk", "primary", "secondary"))
        for a, b, ya, yb in zip(dense, dense[1:], heights, heights[1:]):
            length = math.hypot(b[0] - a[0], b[1] - a[1])
            if length < 0.05:
                continue
            # A thin shoulder prevents roads from looking pasted over the DEM.
            strip2(mesh, a, b, half + 0.55, 0.055, ya, yb, shoulder)
            strip2(mesh, a, b, half, 0.11, ya, yb, asphalt)
            if sidewalk and is_main:
                for side in (-1, 1):
                    nx, nz = -(b[1] - a[1]) / length, (b[0] - a[0]) / length
                    off = half + 0.85
                    pa = (a[0] + nx * off * side, a[1] + nz * off * side)
                    pb = (b[0] + nx * off * side, b[1] + nz * off * side)
                    strip2(mesh, pa, pb, 0.65, 0.17, ya, yb, (0.58, 0.56, 0.49))
            if is_main and ("footway" not in cls and "path" not in cls):
                # Continuous edge lines and a dashed centre line.
                for side in (-1, 1):
                    nx, nz = -(b[1] - a[1]) / length, (b[0] - a[0]) / length
                    off = half - 0.22
                    pa = (a[0] + nx * off * side, a[1] + nz * off * side)
                    pb = (b[0] + nx * off * side, b[1] + nz * off * side)
                    strip2(mesh, pa, pb, 0.07, 0.135, ya, yb, (0.92, 0.89, 0.74))
                # Use the actual arc position from the run to make a stable dash pattern.
                # The tiny modulo is enough for visual purpose and avoids per-tile seams.
                phase = (abs(a[0] * 0.73 + a[1] * 0.37) % 9.0)
                if phase < 4.0:
                    strip2(mesh, a, b, 0.07, 0.14, ya, yb, (0.93, 0.90, 0.75))
            # Guardrail on the downhill side of mountain roads, retaining wall uphill.
            if is_main:
                nx, nz = -(b[1] - a[1]) / length, (b[0] - a[0]) / length
                for side in (-1, 1):
                    ox, oz = nx * side * (half + 2.5), nz * side * (half + 2.5)
                    sx = (a[0] + b[0]) / 2 + ox
                    sz = (a[1] + b[1]) / 2 + oz
                    if abs(sx) > (w - 1) * dx / 2 - 2 or abs(sz) > (h - 1) * dz / 2 - 2:
                        continue
                    other = ground_at(sx, sz)
                    base = (ya + yb) / 2
                    edge = half + 0.65
                    ex, ez = a[0] + nx * edge * side, a[1] + nz * edge * side
                    if base - other > 1.4:
                        # Two slim horizontal rails make a readable guardrail at this scale.
                        for rail_h in (0.55, 0.95):
                            strip(mesh, (ex, ez), (ex + (b[0] - a[0]), ez + (b[1] - a[1])), 0.055, lambda x, z, _y=base + rail_h: _y, (0.71, 0.73, 0.72))
                    elif other - base > 1.8:
                        # Stone retaining wall above the uphill shoulder.
                        wall_top = base + 1.15
                        mesh.quad((ex, base - 0.25, ez), (ex + (b[0] - a[0]), base - 0.25, ez + (b[1] - a[1])), (ex + (b[0] - a[0]), wall_top, ez + (b[1] - a[1])), (ex, wall_top, ez), (0.55, 0.51, 0.43))

    # Buildings: simplified footprints with a plinth, cornice, roofs and local windows.
    def near_road(p):
        return road_index.distance(p) < 18.0

    window_budget = MAX_WINDOWS_PER_TILE
    for way_id, poly_ll, tags in buildings:
        poly = simplify_polygon(poly_ll, 0.85)
        if len(poly) < 3 or polygon_area(poly) < 7.0:
            continue
        low = min(ground_at(x, z) for x, z in poly) - 0.8
        base = max(ground_at(x, z) for x, z in poly)
        height = building_height(tags)
        top = base + height
        kind = (tags.get("building") or "residential").lower()
        if kind in ("apartments", "residential", "dormitory", "terrace"):
            kind = "residential"
        elif kind in ("house", "detached", "villa", "bungalow", "garage", "garages"):
            kind = "house"
        elif any(k in kind for k in ("hotel", "hostel")):
            kind = "hotel"
        elif any(k in kind for k in ("school", "kindergarten", "college", "university")):
            kind = "school"
        elif any(k in kind for k in ("commercial", "shop", "office", "retail", "supermarket")):
            kind = "commercial"
        elif any(k in kind for k in ("industrial", "warehouse", "factory")):
            kind = "industrial"
        palette = FACADES.get(kind, FACADES["residential"])
        hsel = stable_hash(way_id)
        facade = palette[hsel % len(palette)]
        roof_color = ROOFS[(hsel // 7) % len(ROOFS)]
        lower_color = tuple(v * 0.82 for v in facade)
        near_main = near_route((sum(p[0] for p in poly) / len(poly), sum(p[1] for p in poly) / len(poly)), 170.0)
        roof = triangulate(poly)
        if not roof:
            continue
        # Walls are split at 3.1 m: darker plinth below, daylight facade above.
        band = min(top - 0.15, base + 3.1)
        for a, b in zip(poly, poly[1:] + poly[:1]):
            ya, yb = ground_at(a[0], a[1]), ground_at(b[0], b[1])
            bottom_a, bottom_b = ya - 0.9, yb - 0.9
            mesh.quad((a[0], bottom_a, a[1]), (b[0], bottom_b, b[1]), (b[0], band, b[1]), (a[0], band, a[1]), lower_color)
            if top > band:
                mesh.quad((a[0], band, a[1]), (b[0], band, b[1]), (b[0], top, b[1]), (a[0], top, a[1]), facade)
            # Window rows. Near-road buildings receive individual panes; distant ones stay clean.
            wall_len = math.hypot(b[0] - a[0], b[1] - a[1])
            if near_main and window_budget > 0 and wall_len > 3.0 and height > 5.5:
                # Extra vertices are intentionally limited per tile on older devices.
                floors = int(clamp((height - 3.0) // 3.0, 0, 18))
                cols = int(clamp((wall_len - 1.0) // 3.2, 0, 14))
                if floors and cols:
                    nx, nz = (b[1] - a[1]) / wall_len * 0.075, -(b[0] - a[0]) / wall_len * 0.075
                    for floor in range(floors):
                        y0 = base + 1.55 + floor * 3.0
                        if y0 + 1.15 >= top - 0.5:
                            break
                        for col in range(cols):
                            if window_budget <= 0:
                                break
                            t = (col + 0.5) / cols
                            cx = a[0] + (b[0] - a[0]) * t
                            cz = a[1] + (b[1] - a[1]) * t
                            t2 = min(cols, col + 0.56) / cols
                            x2 = a[0] + (b[0] - a[0]) * t2
                            z2 = a[1] + (b[1] - a[1]) * t2
                            # Glass and a narrow warm sill.
                            pane = (0.15 + (hsel % 5) * 0.008, 0.22, 0.27)
                            cell = (int(math.floor(cx / 80.0)), int(math.floor(cz / 80.0)))
                            target = mesh.windows[cell]
                            mesh.quad((cx + nx, y0, cz + nz), (x2 + nx, y0, z2 + nz), (x2 + nx, y0 + 1.15, z2 + nz), (cx + nx, y0 + 1.15, cz + nz), pane, target)
                            mesh.quad((cx + nx, y0 - 0.08, cz + nz), (x2 + nx, y0 - 0.08, z2 + nz), (x2 + nx, y0, z2 + nz), (cx + nx, y0, cz + nz), (0.86, 0.81, 0.69), target)
                            window_budget -= 1
        # Roof cap or a low hipped roof for small houses.
        if kind == "house" and height <= 16 and polygon_area(poly) < 900:
            cx = sum(p[0] for p in poly) / len(poly)
            cz = sum(p[1] for p in poly) / len(poly)
            apex = (cx, top + min(3.0, max(1.1, height * 0.32)), cz)
            for a, b in zip(poly, poly[1:] + poly[:1]):
                mesh.tri((a[0], top, a[1]), (b[0], top, b[1]), apex, roof_color)
        else:
            for a, b, c in roof:
                mesh.tri((a[0], top, a[1]), (b[0], top, b[1]), (c[0], top, c[1]), roof_color)
            # Slim cornice around the roof edge.
            for a, b in zip(poly, poly[1:] + poly[:1]):
                mesh.quad((a[0], top - 0.22, a[1]), (b[0], top - 0.22, b[1]), (b[0], top + 0.04, b[1]), (a[0], top + 0.04, a[1]), tuple(v * 0.9 for v in facade))

    # Vegetation: cypress-like uprights and broad coastal crowns along the route.
    rng = random.Random(7300 + n)
    for i in range(0, len(route_local) - 1, 8):
        a, b = route_local[i], route_local[i + 1]
        length = math.hypot(b[0] - a[0], b[1] - a[1])
        if length < 0.1:
            continue
        nx, nz = -(b[1] - a[1]) / length, (b[0] - a[0]) / length
        for side in (-1, 1):
            if rng.random() < 0.45:
                off = rng.uniform(9, 28)
                t = rng.random()
                x = a[0] + (b[0] - a[0]) * t + nx * side * off
                z = a[1] + (b[1] - a[1]) * t + nz * side * off
                if not (xmin + 2 < x < xmax - 2 and zmin + 2 < z < zmax - 2) or ground_at(x, z) < 1.2:
                    continue
                if near_road((x, z)) or not near_route((x, z), 90.0):
                    continue
                y = ground_at(x, z)
                height = rng.uniform(5.5, 11.5)
                radius = rng.uniform(1.4, 2.8)
                crown = (0.12, 0.29 + rng.random() * 0.08, 0.14)
                # Trunk
                mesh.quad((x - 0.18, y, z - 0.18), (x + 0.18, y, z - 0.18), (x + 0.18, y + height * 0.55, z - 0.18), (x - 0.18, y + height * 0.55, z - 0.18), (0.29, 0.20, 0.11))
                # Crown as a compact cone.
                for k in range(6):
                    aa, bb = k * math.tau / 6, (k + 1) * math.tau / 6
                    mesh.tri((x + math.cos(aa) * radius, y + height * 0.40, z + math.sin(aa) * radius), (x + math.cos(bb) * radius, y + height * 0.40, z + math.sin(bb) * radius), (x, y + height, z), crown)

    # Navigation/safety points and the tile-local road graph.
    nav_roads = []
    for way_id, run, tags in roads_all:
        name = tags.get("name:ru") or tags.get("name") or "Безымянная дорога / дорожка"
        for a, b in zip(run, run[1:]):
            nav_roads.append([round(a[0], 2), round(a[1], 2), round(b[0], 2), round(b[1], 2), name])
    points = []
    seen_names = {}
    for i, (gx, gz) in enumerate(rxz):
        x, z = local_from_global(gx, gz)
        if not (xmin + 4 < x < xmax - 4 and zmin + 4 < z < zmax - 4):
            continue
        heading = headings[i]
        name = "Южнобережное шоссе"
        # A coarse label is enough for the HUD; detailed names are on the regional map.
        points.append([round(x, 2), round(z, 2), round(heading, 1), name])
    places = []
    for p in points:
        if p[3] not in seen_names:
            seen_names[p[3]] = p
    spawn_index = choose_spawn(rxz, cumulative, start, end, tile_bounds, origin_lat, origin_lon)
    spawn_x, spawn_z = local_from_global(*rxz[spawn_index])
    sx, sz = spawn_x, spawn_z
    heading = headings[spawn_index]
    spawn = [round(sx, 2), round(sz, 2), round(heading, 1)]

    meta = {
        "name": f"ЮБК · {start:.0f}–{end:.0f} км",
        "origin": [origin_lat, origin_lon],
        "bbox": [west, south, east, north],
        "w": w,
        "h": h,
        "dx": dx,
        "dz": dz,
        "heights": [[round(v, 2) for v in row] for row in ground],
        "spawn": spawn,
        "buildings": [poly for _, poly, _ in buildings if len(poly) >= 3],
        "counts": {"buildings": len(buildings), "road_segments": sum(1 for _ in nav_roads), "vertices": 0},
        "source": "© OpenStreetMap contributors, ODbL 1.0; Mapzen/Tilezen Skadi (SRTM)",
        "route_start_m": start * 1000.0,
        "route_end_m": end * 1000.0,
    }
    meta["base_vertices"] = len(mesh.base) // 24
    meta["window_batches"] = []
    for (cx, cz), data in sorted(mesh.windows.items()):
        if not data:
            continue
        meta["window_batches"].append([len(mesh.base) // 24, len(data) // 24, cx, cz])
        mesh.base.extend(data)
    # The map data is local and deliberately small. No huge region arrays are duplicated.
    nav = {
        "bbox": [west, south, east, north], "origin": [origin_lat, origin_lon], "sx": SX, "sz": SZ,
        "title": meta["name"], "roads": nav_roads, "points": points, "places": places,
        "source": "© OpenStreetMap contributors, ODbL 1.0",
    }
    # Window batches in the current schema are expected after base geometry; rebuild correctly.
    # (The loop above appends data; the batch starts were recorded before each append.)
    # Fix the batch offsets by calculating cumulative positions.
    offset = meta["base_vertices"]
    for batch in meta["window_batches"]:
        count = batch[1]
        batch[0] = offset
        offset += count
    packed = gzip.compress(struct.pack(">i", len(mesh.base) // 24) + mesh.base, mtime=0)
    chunks = [packed[i:i + 262144] for i in range(0, len(packed), 262144)]
    for old in ROUTE_DIR.glob(f"tile_{n:02d}.mesh.*.gzpart"):
        old.unlink()
    for i, chunk in enumerate(chunks):
        (ROUTE_DIR / f"tile_{n:02d}.mesh.{i:03d}.gzpart").write_bytes(chunk)
    meta["mesh_parts"] = len(chunks)
    meta["counts"]["vertices"] = len(mesh.base) // 24
    (ROUTE_DIR / f"tile_{n:02d}.json").write_text(json.dumps(meta, ensure_ascii=False, separators=(",", ":")))
    (ROUTE_DIR / f"tile_{n:02d}.navigation.json").write_text(json.dumps(nav, ensure_ascii=False, separators=(",", ":")))
    return meta, nav


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--map-only", action="store_true", help="only rebuild region.json and region_map.png")
    parser.add_argument("--tile", type=int, action="append", help="build only selected chunk number(s)")
    args = parser.parse_args()
    ROUTE_DIR.mkdir(parents=True, exist_ok=True)
    route = load_route()
    region_osm = load_region_osm()
    chunks = load_chunks(route)
    region, rxz, cumulative, headings = build_region(route, region_osm, chunks)
    build_region_map(region_osm)
    print(f"region: {region['length_m']/1000:.1f} km, {len(region['tiles'])} tiles, {len(region['places'])} places")
    if args.map_only:
        return
    selected = set(args.tile) if args.tile else None
    ways = merge_osm(chunks)
    for item in chunks:
        n = item[0]
        if selected is not None and n not in selected:
            continue
        print(f"building tile {n:02d} ...", flush=True)
        meta, nav = build_tile(item, rxz, cumulative, headings, ways)
        print(f"  {meta['counts']['vertices']:,} vertices, {meta['counts']['buildings']} buildings, {meta['counts']['road_segments']} road segments", flush=True)


if __name__ == "__main__":
    main()
