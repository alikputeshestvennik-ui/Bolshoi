"""Regressions for full-area coverage, page seams and relation courtyards."""
import sys
import unittest
from array import array
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from region_assets import (PAGE, STEP, grid_bounds, page_keys, page_id, xz,
                           bake_roads, bake_buildings, roof_triangles, building_features)


class RegionalAssetsTest(unittest.TestCase):
    def test_named_settlements_and_valley_have_pages_far_from_highway(self):
        x0, z0, x1, z1 = grid_bounds()
        for lat, lon in [(44.6054, 33.5221), (44.4194, 34.0557), (44.433, 34.084),
                         (44.499, 34.169), (44.6771, 34.4095), (44.452, 33.785),
                         (44.5121, 33.599), (44.5479, 34.2737)]:
            x, z = xz(lat, lon)
            keys = list(page_keys((x, z, x, z)))
            self.assertEqual(len(keys), 1)
            c, r = keys[0]
            self.assertTrue(x0 <= c < x1 and z0 <= r < z1)
            self.assertTrue(page_id(c, r).startswith("area_"))

    def test_side_street_continues_through_both_page_edges(self):
        # No route centreline is supplied: every local way must still be baked.
        road = (123, [(PAGE - 20, 40), (PAGE + 20, 40)], {"highway": "residential", "name": "Местная улица"})
        endpoints = []
        for col in (0, 1):
            meta = {"w": 2, "h": 2, "dx": PAGE, "dz": PAGE,
                    "heights": [[10, 10], [10, 10]], "center_x": (col + .5) * PAGE,
                    "center_z": PAGE / 2}
            vertices = array("f")
            nav, _ = bake_roads(vertices, meta, [road])
            self.assertTrue(vertices)
            self.assertTrue(nav)
            endpoints.append({round(p + meta["center_x"], 3) for r in nav for p in (r[0], r[2])})
        self.assertIn(PAGE, endpoints[0] & endpoints[1])

    def test_building_on_page_border_is_rendered_once_but_collides_on_both(self):
        feature = (10, [(PAGE - 5, 40), (PAGE + 5, 40), (PAGE + 5, 50), (PAGE - 5, 50)], [], {"building": "yes"})
        counts = []
        for col in (0, 1):
            meta = {"w": 2, "h": 2, "dx": PAGE, "dz": PAGE,
                    "heights": [[10, 10], [10, 10]], "center_x": (col + .5) * PAGE,
                    "center_z": PAGE / 2, "col": col, "row": 0, "buildings": []}
            vertices = array("f")
            counts.append(bake_buildings(vertices, meta, [feature]))
            self.assertEqual(len(meta["buildings"]), 1)
        self.assertEqual(sum(counts), 1)

    def test_multipolygon_rings_join_and_roof_keeps_courtyard_open(self):
        relation = {"id": 1, "type": "relation", "tags": {"building": "yes"}, "members": [
            {"type": "way", "ref": 2, "role": "outer", "geometry": [{"lat": a, "lon": b} for a, b in [(44.5, 34), (44.5, 34.01), (44.51, 34.01)]]},
            {"type": "way", "ref": 3, "role": "outer", "geometry": [{"lat": a, "lon": b} for a, b in [(44.5, 34), (44.51, 34), (44.51, 34.01)]]}]}
        self.assertEqual(len(list(building_features([relation]))), 1)
        outer = [(0, 0), (20, 0), (20, 20), (0, 20)]
        hole = [(8, 8), (12, 8), (12, 12), (8, 12)]
        area = 0
        for a, b, c in roof_triangles(outer, [hole]):
            area += abs((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])) / 2
        self.assertAlmostEqual(area, 384)


if __name__ == "__main__":
    unittest.main()
