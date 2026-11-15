"""Offline regressions for mobile streaming, map LOD and recovered OSM data."""
import gzip
import json
import math
import sys
import tempfile
import unittest
from array import array
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from mobile_assets import partition_triangles, bake_map, simplify_run
from scene_data import drape_polygon, recover_buildings
from road_geometry import ground_height


class MobileAssetsTest(unittest.TestCase):
    def test_stream_jobs_preserve_every_triangle_and_obey_budget(self):
        vertices = array("f")
        for i in range(300):
            x = (i % 30) * 30 - 400
            for p in [(x, 0, 0), (x + 20, 0, 0), (x, 0, 20)]:
                vertices.extend((*p, 0.2, 0.3, 0.4))
        chunks = list(partition_triangles(vertices, max_vertices=60))
        original = sorted(tuple(vertices[i:i + 18]) for i in range(0, len(vertices), 18))
        baked = []
        for cell, _, bounds, data in chunks:
            self.assertLessEqual(len(data) // 6, 60)
            self.assertEqual(len(data) % 18, 0)
            for i in range(0, len(data), 18):
                triangle = tuple(data[i:i + 18])
                baked.append(triangle)
                self.assertEqual(cell, (math.floor(sum(triangle[k] for k in (0, 6, 12)) / 3 / 128),
                                        math.floor(sum(triangle[k] for k in (2, 8, 14)) / 3 / 128)))
        self.assertEqual(original, sorted(baked))

    def test_map_lod_does_not_connect_separate_roads(self):
        region = {"road_groups": [{"kind": "primary", "segments":
                   [[0, 0, 10, 0], [10, 0, 20, 0], [100, 100, 110, 100]]}]}
        with tempfile.TemporaryDirectory() as folder:
            dst = Path(folder)
            (dst / "region.json").write_text(json.dumps(region))
            bake_map(dst)
            meta = json.loads((dst / "region.mobile.json").read_text())
            self.assertNotIn("road_groups", meta)
            data = array("f")
            data.frombytes(gzip.decompress((dst / "region.lines.bin.gz").read_bytes()))
            if sys.byteorder != "little":
                data.byteswap()
            overview = [b for b in meta["mobile_map"]["batches"] if b["lod_min"] == 1]
            segments = [tuple(data[i:i + 4]) for b in overview for i in range(b["start"], b["start"] + b["count"], 4)]
            self.assertEqual(segments, [(0, 0, 20, 0), (100, 100, 110, 100)])
        hairpin = [(0, 0), (10, 0), (10, 10), (0, 10)]
        self.assertEqual(simplify_run(hairpin, 1), hairpin)

    def test_water_follows_each_terrain_triangle_on_a_ridge(self):
        meta = {"w": 3, "h": 3, "dx": 10, "dz": 10,
                "heights": [[0, 4, 0], [3, 12, 3], [0, 4, 0]]}
        out = array("f")
        drape_polygon(out, [(-9, -2), (9, -2), (9, 2), (-9, 2)], meta)
        self.assertGreater(len(out), 0)
        area = 0
        for i in range(0, len(out), 18):
            tri = [out[i + j:i + j + 3] for j in (0, 6, 12)]
            for p in tri + [[sum(p[k] for p in tri) / 3 for k in range(3)]]:
                self.assertAlmostEqual(p[1], ground_height(meta, p[0], p[2]) + 0.14, places=4)
            a, b, c = tri
            area += abs((b[0] - a[0]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[0] - a[0])) / 2
        self.assertAlmostEqual(area, 72, places=4)

    def test_yalta_buildings_are_real_and_are_not_duplicated(self):
        meta = {"origin": [44.492, 34.155], "w": 2, "h": 2, "dx": 2000, "dz": 2000,
                "heights": [[20, 20], [20, 20]], "buildings": []}
        out = array("f")
        added = recover_buildings(out, meta)
        self.assertGreater(added, 1000)
        self.assertGreater(len(out), 0)
        self.assertEqual(recover_buildings(out, meta), 0)


if __name__ == "__main__":
    unittest.main()
