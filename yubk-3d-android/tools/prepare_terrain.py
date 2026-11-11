"""Download Skadi DEM and write a metric local terrain grid. Python standard library only."""
import gzip, math, struct, pathlib, urllib.request, json, hashlib
ROOT = pathlib.Path(__file__).resolve().parents[1]
CACHE = ROOT / '.cache'
CACHE.mkdir(exist_ok=True)
tiles = {}
for lon in (33, 34):
    name = f'N44E{lon:03d}.hgt.gz'
    path = CACHE / name
    if not path.exists():
        urllib.request.urlretrieve('https://s3.amazonaws.com/elevation-tiles-prod/skadi/N44/' + name, path)
    raw = gzip.decompress(path.read_bytes())
    side = math.isqrt(len(raw)//2)
    assert side * side * 2 == len(raw)
    tiles[lon] = (raw, side)
def height(lat, lon):
    raw, side = tiles[math.floor(lon)]
    x = round((lon % 1) * (side-1))
    y = round((45-lat) * (side-1))
    value = struct.unpack_from('>h', raw, 2*(y*side+x))[0]
    if value == -32768:
        raise ValueError(f'Missing DEM at {lat}, {lon}')
    return max(0, value)
# Local WGS84 meridional and prime-vertical radii, meters.
phi = math.radians(44.55)
a, e2 = 6378137.0, 0.00669437999014
n = a / math.sqrt(1-e2*math.sin(phi)**2)
m = a*(1-e2)/(1-e2*math.sin(phi)**2)**1.5
W, H = 321, 181
points = []
for row in range(H):
    lat = 44.35 + row * .40/(H-1)
    for col in range(W):
        lon = 33.35 + col * 1.15/(W-1)
        points.append(((math.radians(lon-33.925)*n*math.cos(phi)), height(lat,lon), -math.radians(lat-44.55)*m))
out = ROOT/'app/src/main/assets/terrain.bin'
out.write_bytes(struct.pack('>ii', W,H)+b''.join(struct.pack('>fff',*p) for p in points))
meta = dict(bounds=[33.35,44.35,34.50,44.75], grid=[W,H], height_range=[min(p[1] for p in points),max(p[1] for p in points)], vertical_exaggeration=1, sha256=hashlib.sha256(out.read_bytes()).hexdigest(), source='Mapzen/Tilezen Skadi on AWS', projection='Local WGS84 equirectangular, reference latitude 44.55; horizontal scale error below 0.4% in this extent')
(ROOT/'app/src/main/assets/terrain.json').write_text(json.dumps(meta,indent=2)+'\n')
print(json.dumps(meta, indent=2))
