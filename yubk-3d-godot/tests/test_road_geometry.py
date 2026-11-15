"""Geometry regressions for bends, terrain piercing and raised roads."""
import math
import sys
import unittest
from array import array
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from road_geometry import repair_geometry, subtract_footprint, edge_planes


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
        # The sloping DEM must lose the area hidden beneath the pavement.
        self.assertGreater(stats["carved_triangles"], 0)
        self.assertLess(area(result[:-72]), 400)
        self.assertGreater(area(result[:-72]), 300)

    def test_bridge_preserves_ground_below(self):
        data, meta = self.scene()
        road(data, (-8, 0), (8, 0), y=16)
        result, surfaces, stats = repair_geometry(data, meta, [[-8, 0, 8, 0]])
        self.assertEqual(stats["ribbons"], 1)
        self.assertEqual(stats["carved_triangles"], 0)
        self.assertAlmostEqual(area(result[:-36]), 400)
        self.assertEqual(surfaces[1], 16)

    def test_off_road_rectangles_are_not_pavement(self):
        data, meta = self.scene()
        road(data, (-8, 5), (8, 5))
        result, surfaces, stats = repair_geometry(data, meta, [[-8, 0, 8, 0]])
        self.assertEqual(stats["ribbons"], 0)
        self.assertEqual(len(surfaces), 0)
        self.assertEqual(result, data)

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


if __name__ == "__main__":
    unittest.main()
