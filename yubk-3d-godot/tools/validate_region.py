"""Fail the export if regional pages or settlement geometry are missing."""
import json
import math
from region_assets import DST, PAGE, grid_bounds, page_id, xz

PLACES = [("sevastopol", 44.6054, 33.5221), ("balaklava", 44.5121, 33.5990),
          ("foros", 44.391, 33.788), ("alupka", 44.4194, 34.0557),
          ("koreiz", 44.433, 34.084), ("yalta", 44.499, 34.169),
          ("gurzuf", 44.5479, 34.2737), ("alushta", 44.6771, 34.4095),
          ("baidar-orlinoe", 44.446, 33.775)]


def main():
    region = json.loads((DST / "region.mobile.json").read_text())
    assert "spatial_grid" in region, "Route-only data cannot be exported as regional coverage"
    x0, z0, x1, z1 = grid_bounds()
    chunks = 0
    for row in range(z0, z1):
        for col in range(x0, x1):
            name = page_id(col, row)
            for suffix in ("json", "navigation.json", "roads.bin.gz", "stream.json"):
                assert (DST / f"{name}.{suffix}").is_file(), (name, suffix)
            manifest = json.loads((DST / f"{name}.stream.json").read_text())
            for chunk in manifest["chunks"]:
                assert 0 < chunk["vertices"] <= 18000 and chunk["vertices"] % 3 == 0
                assert (DST / chunk["file"]).is_file(), chunk["file"]
                chunks += 1
    places = []
    for name, lat, lon in PLACES:
        x, z = xz(lat, lon)
        tile_id = page_id(math.floor(x / PAGE), math.floor(z / PAGE))
        meta = json.loads((DST / (tile_id + ".json")).read_text())
        nav = json.loads((DST / (tile_id + ".navigation.json")).read_text())
        nearby_buildings = sum(1 for poly in meta["buildings"] if
            (sum(p[0] for p in poly) / len(poly) - x) ** 2 + (sum(p[1] for p in poly) / len(poly) - z) ** 2 < 1200 ** 2)
        nearby_roads = sum(1 for a, b, c, d, *_ in nav["roads"] if ((a + c) / 2 - x) ** 2 + ((b + d) / 2 - z) ** 2 < 1200 ** 2)
        assert nearby_buildings >= 20, (name, "missing buildings", nearby_buildings)
        assert nearby_roads >= 20, (name, "missing roads", nearby_roads)
        places.append({"name": name, "buildings_within_1200m": nearby_buildings, "road_segments_within_1200m": nearby_roads})
    report = {**region["coverage"], "pages": len(region["tiles"]), "chunks": chunks, "places": places}
    (DST.parents[1] / "build").mkdir(exist_ok=True)
    (DST.parents[1] / "build/coverage-report.json").write_text(json.dumps(report, indent=2))
    print("REGIONAL_COVERAGE_PASS", json.dumps(report), flush=True)


if __name__ == "__main__":
    main()
