#!/usr/bin/env python3
"""Cache complete OSM building coverage and Skadi elevations for offline baking.

Only preparation machines contact the providers. A failed/partial query never
replaces a cache shard. © OpenStreetMap contributors, ODbL 1.0.
"""
import argparse
import gzip
import json
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "yubk-3d-android/data/region_buildings"
BOUNDS = (33.35, 44.35, 34.50, 44.75)
ENDPOINTS = ["https://overpass-api.de/api/interpreter",
             "https://overpass.private.coffee/api/interpreter"]


def shards():
    west, south, east, north = BOUNDS
    for row in range(2):
        for col in range(5):
            yield f"{col}_{row}", [west + (east - west) * col / 5,
                                   south + (north - south) * row / 2,
                                   west + (east - west) * (col + 1) / 5,
                                   south + (north - south) * (row + 1) / 2]


def validate(data, bbox):
    if data.get("remark") or not data.get("elements"):
        raise ValueError("Empty or incomplete Overpass response: " + data.get("remark", ""))
    return {"bbox": bbox, "osm_base": data.get("osm3s", {}).get("timestamp_osm_base"),
            "source": "© OpenStreetMap contributors, ODbL 1.0", "elements": data["elements"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--shard", help="Fetch only one shard, e.g. 0_0")
    args = parser.parse_args()
    DATA.mkdir(parents=True, exist_ok=True)
    for name, bbox in shards():
        if args.shard and args.shard != name:
            continue
        path = DATA / (name + ".json.gz")
        if path.exists():
            cached = json.loads(gzip.decompress(path.read_bytes()))
            if cached.get("bbox") != bbox:
                raise ValueError(f"Wrong coverage in {path}")
            validate(cached, bbox)
            print(name, "cached", len(cached["elements"]), flush=True)
            continue
        w, s, e, n = bbox
        query = f'[out:json][timeout:240][maxsize:536870912];(way["building"]({s},{w},{n},{e});relation["building"]({s},{w},{n},{e}););out geom;'
        for attempt in range(6):
            endpoint = ENDPOINTS[attempt % len(ENDPOINTS)]
            try:
                req = urllib.request.Request(endpoint,
                    data=urllib.parse.urlencode({"data": query}).encode(),
                    headers={"User-Agent": "YUBK-offline-map/1.5", "Content-Type": "application/x-www-form-urlencoded"})
                with urllib.request.urlopen(req, timeout=300) as response:
                    data = validate(json.load(response), bbox)
                temporary = path.with_suffix(".tmp")
                temporary.write_bytes(gzip.compress(json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode(), mtime=0))
                temporary.replace(path)
                print(name, len(data["elements"]), "features", flush=True)
                break
            except Exception as error:
                print(name, endpoint, str(error), flush=True)
                if attempt == 5:
                    raise
                time.sleep(8)
    cache = ROOT / "yubk-3d-android/.cache"
    cache.mkdir(exist_ok=True)
    for lon in (33, 34):
        path = cache / f"N44E{lon:03d}.hgt.gz"
        if not path.exists():
            urllib.request.urlretrieve("https://s3.amazonaws.com/elevation-tiles-prod/skadi/N44/" + path.name, path)


if __name__ == "__main__":
    main()
