"""Build offline street labels and safe teleport points from the bundled OSM slice."""
import pathlib,json,gzip,xml.etree.ElementTree as ET,math
ROOT=pathlib.Path(__file__).resolve().parents[1];A=ROOT/'app/src/main/assets'
m=json.loads((A/'walk.json').read_text());west,south,east,north=m['bbox'];sx=(m['w']-1)*m['dx']/(east-west);sz=(m['h']-1)*m['dz']/(north-south)
X=(m['w']-1)*m['dx']/2;Z=(m['h']-1)*m['dz']/2
root=ET.fromstring(gzip.decompress((ROOT/'data/yalta.osm.gz').read_bytes()))
nodes={n.attrib['id']:((float(n.attrib['lon'])-(east+west)/2)*sx,((north+south)/2-float(n.attrib['lat']))*sz) for n in root.findall('node')}
def ground(x,z):
 u=max(0,min(m['w']-1.000001,(x+X)/m['dx']));v=max(0,min(m['h']-1.000001,(z+Z)/m['dz']));c=int(u);r=int(v);u-=c;v-=r;w=m['w'];q=m['heights'];a,b,cc,d=q[r*w+c],q[r*w+c+1],q[(r+1)*w+c],q[(r+1)*w+c+1]
 return a+(b-a)*u+(cc-a)*v if u+v<=1 else d+(cc-d)*(1-u)+(b-d)*(1-v)
def blocked(x,z):
 if abs(x)>X-3 or abs(z)>Z-3 or ground(x,z)<.7:return True
 for p in m['buildings']:
  hit=False
  for a,b in zip(p,p[1:]+p[:1]):
   if (a[1]>z)!=(b[1]>z) and x<(b[0]-a[0])*(z-a[1])/(b[1]-a[1])+a[0]:hit=not hit
   vx,vz=b[0]-a[0],b[1]-a[1];ll=vx*vx+vz*vz;t=max(0,min(1,((x-a[0])*vx+(z-a[1])*vz)/ll)) if ll else 0
   if math.hypot(x-a[0]-t*vx,z-a[1]-t*vz)<.6:return True
  if hit:return True
 return False
roads=[];points=[];places={}
for way in root.findall('way'):
 tags={t.attrib['k']:t.attrib['v'] for t in way.findall('tag')}
 if tags.get('highway') not in ('trunk','primary','secondary','tertiary','residential','service','unclassified','living_street','pedestrian','footway') or tags.get('tunnel')=='yes' or tags.get('bridge')=='yes':continue
 pts=[nodes[n.attrib['ref']] for n in way.findall('nd') if n.attrib['ref'] in nodes]
 name=tags.get('name:ru',tags.get('name','Безымянная дорога / дорожка'))
 for a,b in zip(pts,pts[1:]):
  if not all(abs(p[0])<X-2 and abs(p[1])<Z-2 for p in (a,b)) or math.dist(a,b)<.1:continue
  roads.append([*a,*b,name])
  steps=max(1,math.ceil(math.dist(a,b)/20))
  for i in range(steps):
   t=(i+.5)/steps;x=a[0]+(b[0]-a[0])*t;z=a[1]+(b[1]-a[1])*t
   if blocked(x,z):continue
   point=[round(x,3),round(z,3),math.degrees(math.atan2(b[0]-a[0],-(b[1]-a[1]))),name]
   points.append(point)
   if not name.startswith('Безымянная') and (name not in places or x*x+z*z<places[name][0]**2+places[name][1]**2):places[name]=point
assert points and places
out=dict(bbox=m['bbox'],sx=sx,sz=sz,roads=roads,points=points,places=[places[k] for k in sorted(places)],source='© OpenStreetMap contributors, ODbL 1.0')
(A/'navigation.json').write_text(json.dumps(out,ensure_ascii=False,separators=(',',':')))
print(len(roads),'segments',len(points),'safe points',len(places),'named streets')
print('\n'.join(sorted(places)))
