"""Reproducible Yalta pilot: OSM geometry + Skadi elevation; synthetic vegetation.
All geometry in metres, in a local WGS84 projection. No third-party Python modules.
"""
import pathlib, gzip, struct, math, json, random, urllib.request, xml.etree.ElementTree as ET
from collections import Counter
ROOT=pathlib.Path(__file__).resolve().parents[1]; CACHE=ROOT/'.cache'; CACHE.mkdir(exist_ok=True)
ASSETS=ROOT/'app/src/main/assets'
BBOX=(34.145,44.487,34.165,44.497)
source=CACHE/'yalta.osm'
if not source.exists():
    urllib.request.urlretrieve('https://www.openstreetmap.org/api/0.6/map?bbox='+','.join(map(str,BBOX)), source)
dempath=CACHE/'N44E034.hgt.gz'
if not dempath.exists():
    urllib.request.urlretrieve('https://s3.amazonaws.com/elevation-tiles-prod/skadi/N44/N44E034.hgt.gz',dempath)
raw=gzip.decompress(dempath.read_bytes()); side=math.isqrt(len(raw)//2)
phi=math.radians(44.492); a=6378137; e2=.00669437999014
sx=a/math.sqrt(1-e2*math.sin(phi)**2)*math.cos(phi)*math.pi/180
sz=a*(1-e2)/(1-e2*math.sin(phi)**2)**1.5*math.pi/180
W,H=81,61; dx=(BBOX[2]-BBOX[0])*sx/(W-1); dz=(BBOX[3]-BBOX[1])*sz/(H-1)
X=(W-1)*dx/2; Z=(H-1)*dz/2

def xy(lat,lon): return ((lon-34.155)*sx, -(lat-44.492)*sz)
def sample(lat,lon):
    xx=(lon-34)*(side-1); yy=(45-lat)*(side-1); ix=int(xx); iy=int(yy); fx=xx-ix; fy=yy-iy
    q=[struct.unpack_from('>h',raw,2*((iy+j)*side+ix+i))[0] for j in range(2) for i in range(2)]
    if min(q)==-32768: raise ValueError('DEM void')
    return max(0,(q[0]*(1-fx)+q[1]*fx)*(1-fy)+(q[2]*(1-fx)+q[3]*fx)*fy)
heights=[sample(BBOX[3]-r*(BBOX[3]-BBOX[1])/(H-1),BBOX[0]+c*(BBOX[2]-BBOX[0])/(W-1)) for r in range(H) for c in range(W)]
def ground(x,z):
    u=max(0,min(W-1.000001,(x+X)/dx)); v=max(0,min(H-1.000001,(z+Z)/dz)); c=int(u);r=int(v);u-=c;v-=r
    aa=heights[r*W+c];bb=heights[r*W+c+1];cc=heights[(r+1)*W+c];dd=heights[(r+1)*W+c+1]
    return aa+(bb-aa)*u+(cc-aa)*v if u+v<=1 else dd+(cc-dd)*(1-u)+(bb-dd)*(1-v)
def inside(x,z): return -X+2<x<X-2 and -Z+2<z<Z-2
mesh=bytearray(); counts=Counter()
def tri(a,b,c,color):
    # Store x,y,z + rgb, flat directional lighting baked for mobile.
    u=[b[i]-a[i] for i in range(3)];v=[c[i]-a[i] for i in range(3)]
    n=(u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]);length=math.sqrt(sum(t*t for t in n))
    shade=.65+.35*abs(sum(n[i]*(-.4,.82,.4)[i] for i in range(3)))/max(length,1e-9)
    for p in (a,b,c): mesh.extend(struct.pack('>6f',*p,*(v*shade for v in color)))
def quad(a,b,c,d,col): tri(a,b,c,col);tri(a,c,d,col)
def point(x,z,offset=0): return (x,ground(x,z)+offset,z)
for r in range(H-1):
    for c in range(W-1):
        x=-X+c*dx;z=-Z+r*dz
        p=[point(x,z),point(x+dx,z),point(x,z+dz),point(x+dx,z+dz)]
        color=(.12,.35,.45) if max(t[1] for t in p)<.5 else (.38,.46,.28)
        tri(p[0],p[2],p[1],color);tri(p[1],p[2],p[3],color)
root=ET.parse(source).getroot(); nodes={n.attrib['id']:xy(float(n.attrib['lat']),float(n.attrib['lon'])) for n in root.findall('node')}
roads=[];buildings=[];mappedtrees=[]
for node in root.findall('node'):
    tags={t.attrib['k']:t.attrib['v'] for t in node.findall('tag')}
    if tags.get('natural')=='tree' and inside(*nodes[node.attrib['id']]):mappedtrees.append(nodes[node.attrib['id']])
for way in root.findall('way'):
    tags={t.attrib['k']:t.attrib['v'] for t in way.findall('tag')}
    pts=[nodes[n.attrib['ref']] for n in way.findall('nd') if n.attrib['ref'] in nodes]
    if 'building' in tags and tags['building']!='no' and len(pts)>3 and pts[0]==pts[-1] and all(inside(*p) for p in pts):buildings.append((pts[:-1],tags))
    if tags.get('highway') in ('trunk','primary','secondary','tertiary','residential','service','unclassified','living_street','pedestrian','footway') and tags.get('tunnel')!='yes' and tags.get('bridge')!='yes':
        for p,q in zip(pts,pts[1:]):
            if inside(*p) and inside(*q) and math.dist(p,q)>.1:roads.append((p,q,tags))
def cross(a,b,c):return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])
def triangulate(poly):
    p=list(poly)
    if sum(a[0]*b[1]-b[0]*a[1] for a,b in zip(p,p[1:]+p[:1]))<0:p.reverse()
    out=[]
    while len(p)>3:
        found=False
        for i in range(len(p)):
            a,b,c=p[i-1],p[i],p[(i+1)%len(p)]
            if cross(a,b,c)<=1e-8:continue
            if any(cross(a,b,q)>=0 and cross(b,c,q)>=0 and cross(c,a,q)>=0 for q in p if q not in (a,b,c)):continue
            out.append((a,b,c));p.pop(i);found=True;break
        if not found:return []
    return out+[tuple(p)]
colliders=[]
for poly,tags in buildings:
    roof=triangulate(poly)
    if not roof:counts['skipped_buildings']+=1;continue
    try:height=float(tags.get('height',str(float(tags.get('building:levels','3'))*3)).replace(' m',''))
    except ValueError:height=9
    height=max(3,min(80,height));base=max(ground(*p) for p in poly); top=base+height
    for a,b,c in roof:tri((a[0],top,a[1]),(b[0],top,b[1]),(c[0],top,c[1]),(.55,.28,.18))
    for p,q in zip(poly,poly[1:]+poly[:1]):
        quad((p[0],ground(*p)-1,p[1]),(q[0],ground(*q)-1,q[1]),(q[0],top,q[1]),(p[0],top,p[1]),(.79,.73,.60))
        length=math.dist(p,q)
        # Window bands, decorative rather than surveyed facade details.
        if length>3:
            nx=-(q[1]-p[1])/length*.03;nz=(q[0]-p[0])/length*.03
            for level in range(1,int(height/3)):
                for i in range(1,int(length/3)):
                    t=i*3/length; ex=(q[0]-p[0])/length*.55;ez=(q[1]-p[1])/length*.55
                    x=p[0]+(q[0]-p[0])*t;z=p[1]+(q[1]-p[1])*t;y=base+level*3
                    for sign in (-1,1):quad((x-ex+nx*sign,y,z-ez+nz*sign),(x+ex+nx*sign,y,z+ez+nz*sign),(x+ex+nx*sign,y+1.3,z+ez+nz*sign),(x-ex+nx*sign,y+1.3,z-ez+nz*sign),(.16,.25,.29))
    colliders.append(poly); counts['buildings']+=1

def inpoly(p,poly):
    x,z=p;hit=False
    for a,b in zip(poly,poly[1:]+poly[:1]):
        if (a[1]>z)!=(b[1]>z) and x<(b[0]-a[0])*(z-a[1])/(b[1]-a[1])+a[0]:hit=not hit
    return hit

def width(tags):
    default=3 if tags['highway'] in ('footway','pedestrian') else 7
    try:return max(2,min(18,float(tags.get('width',default))))
    except ValueError:return default

def strip(p,q,half,offset,col):
    length=math.dist(p,q);nx=-(q[1]-p[1])/length*half;nz=(q[0]-p[0])/length*half
    quad(point(p[0]-nx,p[1]-nz,offset),point(q[0]-nx,q[1]-nz,offset),point(q[0]+nx,q[1]+nz,offset),point(p[0]+nx,p[1]+nz,offset),col)
for p,q,tags in roads:
    length=math.dist(p,q);steps=math.ceil(length/3);half=width(tags)/2
    for i in range(steps):
        a=tuple(p[k]+(q[k]-p[k])*i/steps for k in range(2));b=tuple(p[k]+(q[k]-p[k])*(i+1)/steps for k in range(2))
        strip(a,b,half+.6,.12,(.65,.62,.52));strip(a,b,half,.20,(.20,.21,.22))
        if tags['highway'] not in ('footway','pedestrian','service') and i%4<2:strip(a,b,.09,.23,(.9,.87,.7))
counts['road_segments']=len(roads)
rng=random.Random(42);trees=[]
# Decorative roadside trees; exclude all road surfaces and building footprints.
def distance(p,a,b):
    d=((b[0]-a[0])**2+(b[1]-a[1])**2);t=max(0,min(1,((p[0]-a[0])*(b[0]-a[0])+(p[1]-a[1])*(b[1]-a[1]))/d));return math.dist(p,(a[0]+t*(b[0]-a[0]),a[1]+t*(b[1]-a[1])))
def clear(p):
    return inside(*p) and ground(*p)>1 and not any(inpoly(p,poly) for poly in colliders) and not any(distance(p,a,b)<width(t)/2+3 for a,b,t in roads) and not any(math.dist(p,q)<7 for q in trees)
for p in mappedtrees:
    if clear(p):trees.append(p);counts['mapped_trees']+=1
for a,b,tags in roads:
    length=math.dist(a,b)
    for i in range(int(length/30)):
        t=(i+.5)*30/length;s=rng.choice((-1,1));off=width(tags)/2+rng.uniform(5,12)
        p=(a[0]+(b[0]-a[0])*t-(b[1]-a[1])/length*off*s,a[1]+(b[1]-a[1])*t+(b[0]-a[0])/length*off*s)
        if clear(p):trees.append(p);counts['synthetic_trees']+=1
for x,z in trees:
    y=ground(x,z);height=rng.uniform(6,11);rad=rng.uniform(1.8,3.3)
    for i in range(6):
        aa=i*math.tau/6;bb=(i+1)*math.tau/6
        p=(x+math.cos(aa)*.22,y,z+math.sin(aa)*.22);q=(x+math.cos(bb)*.22,y,z+math.sin(bb)*.22)
        quad(p,q,(q[0],y+height*.6,q[2]),(p[0],y+height*.6,p[2]),(.32,.22,.12))
        tri((x+math.cos(aa)*rad,y+height*.4,z+math.sin(aa)*rad),(x+math.cos(bb)*rad,y+height*.4,z+math.sin(bb)*rad),(x,y+height,z),(.15,.35+rng.random()*.1,.16))
# Spawn on a road, with a clear body position, facing along its centreline.
valid=[(a,b,t) for a,b,t in roads if not any(inpoly(a,p) for p in colliders)]
assert valid,'No usable roads'
a,b,t=min(valid,key=lambda r:math.dist(r[0],(0,0)))
meta=dict(name='Ялта · пилот 1.6 × 1.1 км',bbox=BBOX,w=W,h=H,dx=dx,dz=dz,heights=heights,spawn=[*a,math.degrees(math.atan2(b[0]-a[0],-(b[1]-a[1])))],buildings=colliders,counts=dict(counts),source='© OpenStreetMap contributors, ODbL 1.0; Mapzen/Tilezen Skadi',osm_timestamp=root.attrib.get('timestamp','See source.osm.gz'))
(ASSETS/'walk.json').write_text(json.dumps(meta,separators=(',',':'),ensure_ascii=False))
packed=gzip.compress(struct.pack('>i',len(mesh)//24)+mesh,mtime=0)
chunks=[packed[i:i+262144] for i in range(0,len(packed),262144)]
for old in ASSETS.glob('walk.mesh.*.gzpart'):old.unlink()
for i,chunk in enumerate(chunks):(ASSETS/f'walk.mesh.{i:03d}.gzpart').write_bytes(chunk)
meta['mesh_parts']=len(chunks)
(ASSETS/'walk.json').write_text(json.dumps(meta,separators=(',',':'),ensure_ascii=False))
# The extracted source database is redistributed under ODbL, with account metadata omitted.
for el in root:
    for key in ('user','uid','changeset'):el.attrib.pop(key,None)
(ROOT/'data').mkdir(exist_ok=True)
(ROOT/'data/yalta.osm.gz').write_bytes(gzip.compress(ET.tostring(root,encoding='utf-8'),mtime=0))
print(json.dumps(dict(meta['counts'],vertices=len(mesh)//24,mesh_bytes=len(mesh)),indent=2))
