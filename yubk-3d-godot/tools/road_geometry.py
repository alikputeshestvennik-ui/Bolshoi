"""Repair the legacy road ribbons without requiring the missing OSM cache.

Roads are identified by their rectangular topology AND their surveyed navigation
centreline. Terrain, vertical facades and off-road objects are left intact.
The source assets are never changed. All output remains XYZRGB triangle data.
"""
from __future__ import annotations

import math
from array import array
from collections import defaultdict
from dataclasses import dataclass


CELL = 32.0
EPS = 1e-6


def ground_height(meta, x, z):
    w, h, dx, dz = (meta[k] for k in ("w", "h", "dx", "dz"))
    u = max(0.0, min(w - 1.00001, x / dx + (w - 1) / 2))
    v = max(0.0, min(h - 1.00001, z / dz + (h - 1) / 2))
    c, r = int(u), int(v)
    u, v = u - c, v - r
    heights = meta["heights"]
    a, b = heights[r][c:c + 2]
    cc, d = heights[r + 1][c:c + 2]
    if u + v <= 1:
        return a + (b - a) * u + (cc - a) * v
    return d + (cc - d) * (1 - u) + (b - d) * (1 - v)


def bounds(points):
    return (min(p[0] for p in points), min(p[2] for p in points),
            max(p[0] for p in points), max(p[2] for p in points))


def cells(box, margin=0.0):
    for x in range(math.floor((box[0] - margin) / CELL), math.floor((box[2] + margin) / CELL) + 1):
        for z in range(math.floor((box[1] - margin) / CELL), math.floor((box[3] + margin) / CELL) + 1):
            yield x, z


def overlaps(a, b):
    return a[0] < b[2] and a[2] > b[0] and a[1] < b[3] and a[3] > b[1]


@dataclass
class Ribbon:
    offset: int
    points: list
    width: float
    road_id: int

    def center(self, end):
        a, b = (self.points[0], self.points[3]) if end == 0 else (self.points[1], self.points[2])
        return tuple((a[i] + b[i]) / 2 for i in range(3))

    def normal(self):
        a, b = self.center(0), self.center(1)
        dx, dz = b[0] - a[0], b[2] - a[2]
        length = math.hypot(dx, dz)
        return -dz / length, dx / length


def find_ribbons(vertices, terrain_vertices, roads):
    index = defaultdict(list)
    for road_id, road in enumerate(roads):
        box = (min(road[0], road[2]), min(road[1], road[3]), max(road[0], road[2]), max(road[1], road[3]))
        for cell in cells(box, 0.04):
            index[cell].append(road_id)
    result = []
    off = terrain_vertices * 6
    while off + 36 <= len(vertices):
        # quad(a,b,c,d) is encoded as a,b,c,a,c,d, with a constant colour.
        if not (vertices[off:off + 3] == vertices[off + 18:off + 21]
                and vertices[off + 12:off + 15] == vertices[off + 24:off + 27]
                and all(vertices[off + 3:off + 6] == vertices[off + k * 6 + 3:off + k * 6 + 6]
                        for k in range(1, 6))):
            off += 18
            continue
        p = [list(vertices[off + k:off + k + 3]) for k in (0, 6, 12, 30)]
        a, b, c, d = p
        x0, z0 = (a[0] + d[0]) / 2, (a[2] + d[2]) / 2
        x1, z1 = (b[0] + c[0]) / 2, (b[2] + c[2]) / 2
        vx, vz, wx, wz = x1 - x0, z1 - z0, d[0] - a[0], d[2] - a[2]
        length, width = math.hypot(vx, vz), math.hypot(wx, wz)
        if (0.8 < width < 30 and 0.05 < length < 35
                and abs(a[1] - d[1]) < 0.0001 and abs(b[1] - c[1]) < 0.0001
                and abs(vx * wx + vz * wz) < 0.002 * length * width
                and math.hypot(c[0] - b[0] - wx, c[2] - b[2] - wz) < 0.002):
            x, z = (x0 + x1) / 2, (z0 + z1) / 2
            for road_id in index[math.floor(x / CELL), math.floor(z / CELL)]:
                r = roads[road_id]
                rx, rz = r[2] - r[0], r[3] - r[1]
                ll = rx * rx + rz * rz
                if ll < EPS:
                    continue
                t = max(0.0, min(1.0, ((x - r[0]) * rx + (z - r[1]) * rz) / ll))
                if (math.hypot(x - r[0] - rx * t, z - r[1] - rz * t) < 0.03
                        and abs(vx * rz - vz * rx) < 0.003 * length * math.sqrt(ll)):
                    result.append(Ribbon(off, p, width, road_id))
                    break
        off += 36
    return result


def join_ribbons(ribbons):
    """Use shared mitres at bends; leave junctions and hairpin reversals alone."""
    ends = defaultdict(list)
    for ribbon in ribbons:
        for end in (0, 1):
            c = ribbon.center(end)
            ends[round(c[0], 2), round(c[2], 2), round(ribbon.width, 2)].append((ribbon, end))
    joined = 0
    for pair in ends.values():
        if len(pair) != 2:
            continue
        (a, ae), (b, be) = pair
        ca, cb = a.center(ae), b.center(be)
        if abs(ca[1] - cb[1]) > 0.4:
            continue  # Preserve grade separation.
        na, nb = a.normal(), b.normal()
        dot = na[0] * nb[0] + na[1] * nb[1]
        outgoing_dot = dot * (1 if ae == be else -1)
        if outgoing_dot > 0.7:
            continue
        if dot < 0:
            nb = (-nb[0], -nb[1])
        mx, mz = na[0] + nb[0], na[1] + nb[1]
        ml = math.hypot(mx, mz)
        if ml < EPS:
            continue
        mx, mz = mx / ml, mz / ml
        projection = mx * na[0] + mz * na[1]
        if projection < 0.4:
            continue
        center = tuple((ca[i] + cb[i]) / 2 for i in range(3))
        for ribbon, end in pair:
            normal = ribbon.normal()
            sign = 1 if mx * normal[0] + mz * normal[1] > 0 else -1
            radius = ribbon.width / (2 * projection)
            low, high = (0, 3) if end == 0 else (1, 2)
            ribbon.points[low] = [center[0] - mx * radius * sign, center[1], center[2] - mz * radius * sign]
            ribbon.points[high] = [center[0] + mx * radius * sign, center[1], center[2] + mz * radius * sign]
        joined += 1
    return joined


def edge_planes(points):
    area = sum(a[0] * b[2] - b[0] * a[2] for a, b in zip(points, points[1:] + points[:1]))
    sign = 1 if area > 0 else -1
    return [(sign * (a[2] - b[2]), sign * (b[0] - a[0]),
             sign * (b[2] * a[0] - b[0] * a[2]))
            for a, b in zip(points, points[1:] + points[:1])
            if math.hypot(a[0] - b[0], a[2] - b[2]) > EPS]


def junction_patches(ribbons, vertices):
    """Close bends/junctions, keeping shoulder, pavement and bridge levels apart."""
    ends = defaultdict(list)
    for ribbon in ribbons:
        for end in (0, 1):
            p = ribbon.center(end)
            ends[round(p[0], 2), round(p[2], 2)].append((ribbon, end))
    patches, junctions = [], 0
    for entries in ends.values():
        levels = []
        for ribbon, end in sorted(entries, key=lambda e: e[0].center(e[1])[1]):
            height = ribbon.center(end)[1]
            # Shoulders are 14 cm below the asphalt in the source mesh. Mixing
            # them makes one oversized asphalt patch float over the verge.
            if not levels or height - levels[-1][0][0].center(levels[-1][0][1])[1] > 0.07:
                levels.append([])
            levels[-1].append((ribbon, end))
        for level in levels:
            arms = {}
            for ribbon, end in level:
                a, b = ribbon.center(end), ribbon.center(1 - end)
                angle = round(math.atan2(b[2] - a[2], b[0] - a[0]), 2)
                if angle not in arms or ribbon.width < arms[angle][0].width:
                    arms[angle] = (ribbon, end)
            if len(arms) < 2:
                continue
            points, colors = [], []
            for ribbon, end in arms.values():
                low, high = (0, 3) if end == 0 else (1, 2)
                points.extend((tuple(ribbon.points[low]), tuple(ribbon.points[high])))
                colors.append(vertices[ribbon.offset + 3:ribbon.offset + 6])
            ordered = sorted(set((p[0], p[2]) for p in points))

            def cross(o, a, b):
                return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

            chains = []
            for sequence in (ordered, ordered[::-1]):
                chain = []
                for p in sequence:
                    while len(chain) >= 2 and cross(chain[-2], chain[-1], p) <= 0:
                        chain.pop()
                    chain.append(p)
                chains.extend(chain[:-1])
            if len(chains) < 3:
                continue
            heights = {(x, z): sum(p[1] for p in points if p[0] == x and p[2] == z) /
                       sum(1 for p in points if p[0] == x and p[2] == z) + 0.01 for x, z in chains}
            center = (sum(p[0] for p in chains) / len(chains), sum(heights.values()) / len(chains),
                      sum(p[1] for p in chains) / len(chains))
            color = tuple(sum(c[i] for c in colors) / len(colors) for i in range(3))
            for a, b in zip(chains, chains[1:] + chains[:1]):
                pa, pb = (a[0], heights[a], a[1]), (b[0], heights[b], b[1])
                patches.append((Ribbon(-1, [center, pa, pb, pb], 1.0, -1), color))
            junctions += 1
    return patches, junctions


def split_polygon(poly, plane):
    """Split XYZRGB vertices, interpolating both heights and colours."""
    inside, outside = [], []
    nx, nz, c = plane
    prev = poly[-1]
    pd = nx * prev[0] + nz * prev[2] + c
    for point in poly:
        d = nx * point[0] + nz * point[2] + c
        if (d > 0) != (pd > 0):
            t = pd / (pd - d)
            crossing = tuple(prev[i] + (point[i] - prev[i]) * t for i in range(len(point)))
            inside.append(crossing)
            outside.append(crossing)
        (inside if d > 0 else outside).append(point)
        prev, pd = point, d
    return inside, outside


def subtract_footprint(poly, planes):
    return split_footprint(poly, planes)[0]


def split_footprint(poly, planes):
    # Fast rejection avoids splitting a triangle that misses a road entirely.
    for nx, nz, c in planes:
        if max(nx * p[0] + nz * p[2] + c for p in poly) <= EPS:
            return [poly], []
    outside = []
    remaining = poly
    for plane in planes:
        remaining, piece = split_polygon(remaining, plane)
        if len(piece) >= 3:
            outside.append(piece)
        if len(remaining) < 3:
            break
    return outside, remaining if len(remaining) >= 3 else []


def pavement_plane(points):
    """Height plane for a ribbon (both vertices of each cross section agree)."""
    a, b, c = points[:3]
    determinant = (b[0] - a[0]) * (c[2] - a[2]) - (c[0] - a[0]) * (b[2] - a[2])
    if abs(determinant) < EPS:
        return 0.0, 0.0, a[1] - 0.06
    gx = ((b[1] - a[1]) * (c[2] - a[2]) - (c[1] - a[1]) * (b[2] - a[2])) / determinant
    gz = ((b[0] - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (b[1] - a[1])) / determinant
    return gx, gz, a[1] - gx * a[0] - gz * a[2] - 0.06


def grade_footprint(poly, planes, plane, walls, wall_planes=None):
    """Keep the roadbed, and seal its boundary against the unchanged hillside."""
    outside, inside = split_footprint(poly, planes)
    if not inside:
        return outside
    gx, gz, base = plane
    bed = [(p[0], gx * p[0] + gz * p[2] + base, *p[2:]) for p in inside]
    if wall_planes is None:
        wall_planes = planes
    for i, (a, b) in enumerate(zip(inside, inside[1:] + inside[:1])):
        # Only footprint edges need a retaining face. Interior edges shared
        # by neighboring DEM triangles already have matching roadbed heights.
        if any(abs(nx * a[0] + nz * a[2] + c) < EPS * math.hypot(nx, nz) and
               abs(nx * b[0] + nz * b[2] + c) < EPS * math.hypot(nx, nz)
               for nx, nz, c in wall_planes):
            aa, bb = bed[i], bed[(i + 1) % len(bed)]
            if max(abs(a[1] - aa[1]), abs(b[1] - bb[1])) > 0.001:
                # Vertical faces have zero XZ area: append_polygon deliberately
                # rejects those, so emit them explicitly with a stone tint.
                for p in (a, b, bb, a, bb, aa):
                    walls.extend((*p[:3], 0.38, 0.36, 0.31))
    return outside + [bed]


def exposed_walls(walls, cutters, index):
    """Remove retaining faces inside another same-level road at a junction."""
    out = array("f")
    for off in range(0, len(walls), 36):
        quad = [tuple(walls[off + k:off + k + 6]) for k in (0, 6, 12, 30)]
        box = bounds(quad)
        x, z = (quad[0][0] + quad[1][0]) / 2, (quad[0][2] + quad[1][2]) / 2
        bed_y = (quad[2][1] + quad[3][1]) / 2
        candidates = set()
        for cell in cells(box):
            candidates.update(index.get(cell, ()))
        pieces = [quad]
        for cut_id in sorted(candidates):
            cut_box, planes, plane, _ = cutters[cut_id]
            if box[0] > cut_box[2] or box[2] < cut_box[0] or box[1] > cut_box[3] or box[3] < cut_box[1]:
                continue
            if abs(plane[0] * x + plane[1] * z + plane[2] - bed_y) > 0.4:
                continue
            pieces = [part for piece in pieces for part in subtract_footprint(piece, planes)]
            if not pieces:
                break
        for piece in pieces:
            for i in range(1, len(piece) - 1):
                for p in (piece[0], piece[i], piece[i + 1]):
                    out.extend(p)
    return out


def append_polygon(out, poly):
    for i in range(1, len(poly) - 1):
        a, b, c = poly[0], poly[i], poly[i + 1]
        if abs((b[0] - a[0]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[0] - a[0])) > EPS:
            for vertex in (a, b, c):
                out.extend(vertex)


def repair_geometry(vertices, meta, roads):
    terrain_count = (meta["w"] - 1) * (meta["h"] - 1) * 6
    ribbons = find_ribbons(vertices, terrain_count, roads)
    joined = join_ribbons(ribbons)
    for ribbon in ribbons:
        for k, corner in enumerate((0, 1, 2, 0, 2, 3)):
            offset = ribbon.offset + k * 6
            vertices[offset:offset + 3] = array("f", ribbon.points[corner])

    patches, junctions = junction_patches(ribbons, vertices)
    for ribbon, color in patches:
        for corner in (0, 1, 2):
            vertices.extend((*ribbon.points[corner], *color))
        ribbons.append(ribbon)

    # Grade only ground-level roads. Bridges
    # keep the terrain below them. Concentric shoulder/asphalt quads share one
    # outer footprint, reducing the work and avoiding cracks between layers.
    outer = {}
    for ribbon in ribbons:
        a, b = ribbon.center(0), ribbon.center(1)
        key = tuple(sorted(((round(a[0], 2), round(a[2], 2)), (round(b[0], 2), round(b[2], 2)))))
        if key not in outer or ribbon.width > outer[key].width:
            outer[key] = ribbon
    cutters, index = [], defaultdict(list)
    for ribbon in outer.values():
        a, b = ribbon.center(0), ribbon.center(1)
        x, z = (a[0] + b[0]) / 2, (a[2] + b[2]) / 2
        if (a[1] + b[1]) / 2 - ground_height(meta, x, z) > 2.5:
            continue
        samples = ribbon.points + [tuple((a[i] + b[i]) / 2 for i in range(3))]
        samples += [tuple((p[i] + q[i]) / 2 for i in range(3))
                    for p, q in ((ribbon.points[0], ribbon.points[1]), (ribbon.points[3], ribbon.points[2]))]
        if max(abs(ground_height(meta, p[0], p[2]) - p[1] + 0.06) for p in samples) < 0.025:
            continue
        box = bounds(ribbon.points)
        cut_id = len(cutters)
        planes = edge_planes(ribbon.points)
        # End caps are shared with the next ribbon. A retaining face there
        # would put a wall across the carriageway on every short OSM segment.
        side_planes = [planes[0], planes[2]] if ribbon.offset >= 0 else []
        cutters.append((box, planes, pavement_plane(ribbon.points), side_planes))
        for cell in cells(box):
            index[cell].append(cut_id)

    terrain, walls, graded = array("f"), array("f"), 0
    for off in range(0, terrain_count * 6, 18):
        triangle = [tuple(vertices[off + k:off + k + 6]) for k in (0, 6, 12)]
        box = bounds(triangle)
        candidates = set()
        for cell in cells(box):
            candidates.update(index.get(cell, ()))
        pieces = [triangle]
        for cut_id in sorted(candidates):
            cut_box, planes, plane, side_planes = cutters[cut_id]
            if overlaps(box, cut_box):
                pieces = [part for piece in pieces for part in grade_footprint(piece, planes, plane, walls, side_planes)]
                if not pieces:
                    break
        if pieces != [triangle]:
            graded += 1
        for piece in pieces:
            append_polygon(terrain, piece)
    walls = exposed_walls(walls, cutters, index)
    terrain.extend(walls)
    terrain.extend(vertices[terrain_count * 6:])

    # Exact pavement triangles drive camera height, including raised bridges.
    surfaces = array("f")
    for ribbon in ribbons:
        for p in ribbon.points:
            surfaces.extend(p)
    return terrain, surfaces, {"ribbons": len(ribbons), "joins": joined,
                               "junctions": junctions, "graded_triangles": graded,
                               "retaining_triangles": len(walls) // 18}
