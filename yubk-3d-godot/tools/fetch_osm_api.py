#!/usr/bin/env python3
"""Bounded OSM API fallback for one region when Overpass is unavailable.

Keeps successful XML cells in the preparation cache and subdivides cells that
exceed the API node limit. Resolves complete building relations before saving.
"""
import argparse
import gzip
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

from fetch_region import ROOT, DATA, shards, save, validate

CACHE = ROOT / "yubk-3d-android/.cache/osm_api"
BASE = "https://api.openstreetmap.org/api/0.6/"


def request(path, url):
    if path.exists():
        return ET.fromstring(gzip.decompress(path.read_bytes()))
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "YUBK-offline-map/1.5"})
            with urllib.request.urlopen(req, timeout=90) as response:
                raw = response.read()
            root = ET.fromstring(raw)
            path.write_bytes(gzip.compress(raw, mtime=0))
            time.sleep(0.25)
            return root
        except urllib.error.HTTPError as error:
            if error.code == 400:
                raise
            if attempt == 2:
                raise
            time.sleep(5 * (attempt + 1))
        except (TimeoutError, urllib.error.URLError):
            if attempt == 2:
                raise
            time.sleep(5 * (attempt + 1))


def map_cells(name, bbox, depth=0):
    w, s, e, n = bbox
    path = CACHE / (name + ".osm.gz")
    try:
        root = request(path, BASE + f"map?bbox={w},{s},{e},{n}")
        print(name, len(root.findall("node")), "nodes", flush=True)
        yield root
    except urllib.error.HTTPError as error:
        if error.code != 400 or depth >= 6:
            raise
        for row in range(2):
            for col in range(2):
                box = [w + (e - w) * col / 2, s + (n - s) * row / 2,
                       w + (e - w) * (col + 1) / 2, s + (n - s) * (row + 1) / 2]
                yield from map_cells(f"{name}_{col}_{row}", box, depth + 1)


def tags(element):
    return {t.get("k"): t.get("v") for t in element.findall("tag")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--shard", default="4_1")
    args = parser.parse_args()
    bbox = dict(shards())[args.shard]
    DATA.mkdir(exist_ok=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    nodes, ways, relations = {}, {}, {}

    def merge(root):
        nodes.update({n.get("id"): {"lat": float(n.get("lat")), "lon": float(n.get("lon"))}
                      for n in root.findall("node")})
        ways.update({w.get("id"): w for w in root.findall("way")})
        relations.update({r.get("id"): r for r in root.findall("relation")
                          if tags(r).get("building", "no") != "no"})

    w, s, e, n = bbox
    for row in range(4):
        for col in range(4):
            box = [w + (e - w) * col / 4, s + (n - s) * row / 4,
                   w + (e - w) * (col + 1) / 4, s + (n - s) * (row + 1) / 4]
            for root in map_cells(f"{args.shard}_{col}_{row}", box):
                merge(root)
    # /map includes parent relations, but may omit their outlying member ways.
    for relation in list(relations.values()):
        refs = [m.get("ref") for m in relation.findall("member") if m.get("type") == "way"]
        if any(ref not in ways for ref in refs):
            ref = relation.get("id")
            merge(request(CACHE / ("relation_" + ref + ".osm.gz"), BASE + f"relation/{ref}/full"))

    def geometry(way):
        return [nodes[n.get("ref")] for n in way.findall("nd")]

    output = []
    for ref, way in ways.items():
        t = tags(way)
        if t.get("building", "no") != "no":
            output.append({"type": "way", "id": int(ref), "tags": t, "geometry": geometry(way)})
    for ref, relation in relations.items():
        members = []
        for member in relation.findall("member"):
            if member.get("type") != "way":
                continue
            way = ways[member.get("ref")]
            members.append({"type": "way", "ref": int(member.get("ref")),
                            "role": member.get("role", "outer"), "geometry": geometry(way)})
        output.append({"type": "relation", "id": int(ref), "tags": tags(relation), "members": members})
    data = {"bbox": bbox, "source": "© OpenStreetMap contributors, ODbL 1.0",
            "source_endpoint": BASE + "map", "elements": output}
    validate(data, bbox)
    save(DATA / (args.shard + ".json.gz"), data)
    print("OSM_API_COVERAGE_PASS", args.shard, len(output), "features", flush=True)


if __name__ == "__main__":
    main()
