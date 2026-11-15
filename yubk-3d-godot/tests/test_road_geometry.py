"""Geometry regressions for bends, terrain piercing and raised roads."""
import math
import sys
import unittest
from array import array
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from road_geometry import (repair_geometry, subtract_footprint, edge_planes,
                           grade_footprint, pavement_plane, exposed_walls, bounds, cells)


def quad(out, points, color=(0.3, 0.3, 0.3)):
    for i in (0, 1, 2, 0, 2, 3):
        out.extend((*points[i], *color))


def road(out, a, b, y=10, width=4):
    length = math.dist(a, b)
    nx, nz = -(b[1] - a[1]) / length * width / 2, (b[0] - a[0]) / length * width / 2
    quad(out, [(a[0] - nx, y, a[1] - nz), (b[0] - nx, y, b[1] - nz),
               (b[0] + nx, y, b[1] + nz), (a[0] + nx, y, a[1] + nz)])


def area(vertices):
    total = 0
    for off in range(0, len(vertices), 18):
        ax, az = vertices[off], vertices[off + 2]
        bx, bz = vertices[off + 6], vertices[off + 8]
        cx, cz = vertices[off + 12], vertices[off + 14]
        total += abs((bx - ax) * (cz - az) - (bz - az) * (cx - ax)) / 2
    return total


class RoadGeometryTest(unittest.TestCase):
    def scene(self):
        meta = {"w": 2, "h": 2, "dx": 20.0, "dz": 20.0, "heights": [[10, 10], [12, 12]]}
        data = array("f")
        quad(data, [(-10, 10, -10), (-10, 12, 10), (10, 12, 10), (10, 10, -10)], (0.4, 0.5, 0.2))
        return data, meta

    def test_bend_has_shared_edges_and_pavement_height(self):
        data, meta = self.scene()
        road(data, (-8, 0), (0, 0))
        road(data, (0, 0), (6, 6))
        result, surfaces, stats = repair_geometry(data, meta, [[-8, 0, 0, 0], [0, 0, 6, 6]])
        self.assertEqual(stats["ribbons"], 2)
        self.assertEqual(stats["joins"], 1)
        self.assertEqual(surfaces[3:6], surfaces[12:15])
        self.assertEqual(surfaces[6:9], surfaces[21:24])
        self.assertTrue(all(math.isfinite(v) for v in result))
        # Grading must preserve the entire ground footprint, without sky holes.
        self.assertGreater(stats["graded_triangles"], 0)
        self.assertAlmostEqual(area(result[:-72]), 400, places=4)
        self.assertGreater(stats["retaining_triangles"], 0)

    def test_bridge_preserves_ground_below(self):
        data, meta = self.scene()
        road(data, (-8, 0), (8, 0), y=16)
        result, surfaces, stats = repair_geometry(data, meta, [[-8, 0, 8, 0]])
        self.assertEqual(stats["ribbons"], 1)
        self.assertEqual(stats["graded_triangles"], 0)
        self.assertAlmostEqual(area(result[:-36]), 400)
        self.assertEqual(surfaces[1], 16)

    def test_off_road_rectangles_are_not_pavement(self):
        data, meta = self.scene()
        road(data, (-8, 5), (8, 5))
        result, surfaces, stats = repair_geometry(data, meta, [[-8, 0, 8, 0]])
        self.assertEqual(stats["ribbons"], 0)
        self.assertEqual(len(surfaces), 0)
        self.assertEqual(result, data)

    def test_three_arm_junction_has_pavement_without_connecting_bridge(self):
        data, meta = self.scene()
        for a, b in [((-8, 0), (0, 0)), ((0, 0), (8, 0)), ((0, 0), (0, 8))]:
            road(data, a, b)
        road(data, (0, 0), (0, -8), y=16)
        result, surfaces, stats = repair_geometry(data, meta, [[-8, 0, 0, 0], [0, 0, 8, 0],
                                                               [0, 0, 0, 8], [0, 0, 0, -8]])
        self.assertEqual(stats["junctions"], 1)
        # Every extra triangle belongs to the ground junction, never the bridge.
        patches = surfaces[4 * 12:]
        self.assertGreater(len(patches), 0)
        for i in range(0, len(patches), 3):
            self.assertAlmostEqual(patches[i + 1], 10.01, places=4)
            self.assertLessEqual(abs(patches[i]), 2.01)
            self.assertLessEqual(abs(patches[i + 2]), 2.01)
        self.assertTrue(all(math.isfinite(v) for v in result))

    def test_polygon_cut_conserves_area_and_interpolates(self):
        poly = [(-10, 0, -10, 0, 0, 0), (10, 20, -10, 1, 0, 0),
                (10, 20, 10, 1, 0, 0), (-10, 0, 10, 0, 0, 0)]
        cut = [(-2, 0, -20), (2, 0, -20), (2, 0, 20), (-2, 0, 20)]
        pieces = subtract_footprint(poly, edge_planes(cut))
        from road_geometry import append_polygon
        out = array("f")
        for piece in pieces:
            append_polygon(out, piece)
            for p in piece:
                self.assertAlmostEqual(p[1], p[0] + 10)
                self.assertAlmostEqual(p[3], (p[0] + 10) / 20)
        self.assertAlmostEqual(area(out), 320)

    def test_junction_keeps_asphalt_inside_its_own_shoulders(self):
        data, meta = self.scene()
        segments = [((-8, 0), (0, 0)), ((0, 0), (8, 0)), ((0, 0), (0, 8))]
        for a, b in segments:
            road(data, a, b, y=10, width=6)  # shoulder
            road(data, a, b, y=10.14, width=4)  # pavement
        _, surfaces, stats = repair_geometry(data, meta, [[*a, *b] for a, b in segments])
        self.assertEqual(stats["junctions"], 2)
        patches = surfaces[6 * 12:]
        heights = set()
        for i in range(0, len(patches), 3):
            x, y, z = patches[i:i + 3]
            heights.add(round(y, 2))
            if y > 10.1:
                self.assertLessEqual(max(abs(x), abs(z)), 2.001)
        self.assertEqual(heights, {10.01, 10.15})

    def test_roadbed_follows_grade_and_seals_both_cut_and_fill(self):
        poly = [(-10, 10, -10, .4, .5, .2), (10, 12, -10, .4, .5, .2),
                (10, 12, 10, .4, .5, .2), (-10, 10, 10, .4, .5, .2)]
        ribbon = [(-8, 10, -2), (8, 12, -2), (8, 12, 2), (-8, 10, 2)]
        walls = array("f")
        pieces = grade_footprint(poly, edge_planes(ribbon), pavement_plane(ribbon), walls)
        from road_geometry import append_polygon
        out = array("f")
        for piece in pieces:
            append_polygon(out, piece)
        self.assertAlmostEqual(area(out), 400)
        self.assertGreater(len(walls), 0)
        # Last polygon is the retained bed, 6 cm under the sloping pavement.
        for p in pieces[-1]:
            self.assertAlmostEqual(p[1], 10.94 + p[0] / 8)
        self.assertTrue(all(math.isfinite(v) for v in walls))

    def test_acute_bend_gets_a_bevel_instead_of_an_open_gap(self):
        data, meta = self.scene()
        road(data, (-8, 0), (0, 0))
        road(data, (0, 0), (-7, 3))
        result, surfaces, stats = repair_geometry(data, meta, [[-8, 0, 0, 0], [0, 0, -7, 3]])
        self.assertEqual(stats["joins"], 0)
        self.assertEqual(stats["junctions"], 1)
        self.assertGreater(len(surfaces), 24)
        road_floats = 72 + (stats["ribbons"] - 2) * 18
        self.assertAlmostEqual(area(result[:-road_floats]), 400, places=4)

    def test_retaining_wall_is_clipped_out_of_a_crossing_but_not_a_bridge(self):
        wall = array("f")
        quad(wall, [(-8, 14, 0), (8, 14, 0), (8, 9.94, 0), (-8, 9.94, 0)])
        crossing = [(-2, 10, -8), (2, 10, -8), (2, 10, 8), (-2, 10, 8)]
        box = bounds(crossing)
        index = {cell: [0] for cell in cells(box)}
        cutters = [(box, edge_planes(crossing), pavement_plane(crossing), [])]
        result = exposed_walls(wall, cutters, index)
        self.assertGreater(len(result), 0)
        for off in range(0, len(result), 18):
            xs = [result[off + i] for i in (0, 6, 12)]
            self.assertTrue(max(xs) <= -2 + 1e-5 or min(xs) >= 2 - 1e-5)
        bridge = [(x, y + 6, z) for x, y, z in crossing]
        cutters = [(box, edge_planes(bridge), pavement_plane(bridge), [])]
        self.assertEqual(exposed_walls(wall, cutters, index), wall)


if __name__ == "__main__":
    unittest.main()
