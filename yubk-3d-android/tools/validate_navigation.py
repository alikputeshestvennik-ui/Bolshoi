"""Verify offline navigation coordinates and named teleport landings."""
import pathlib,json,math
A=pathlib.Path(__file__).resolve().parents[1]/'app/src/main/assets'
n=json.loads((A/'navigation.json').read_text());m=json.loads((A/'walk.json').read_text())
X=(m['w']-1)*m['dx']/2;Z=(m['h']-1)*m['dz']/2
assert n['roads'] and n['places'] and n['points']
assert len({p[3] for p in n['places']})==len(n['places'])
for x,z,angle,name in n['points']:
 assert all(math.isfinite(t) for t in (x,z,angle)) and name
 assert abs(x)<X-3 and abs(z)<Z-3
 lat=44.492-z/n['sz'];lon=34.155+x/n['sx']
 assert n['bbox'][0]<lon<n['bbox'][2] and n['bbox'][1]<lat<n['bbox'][3]
 assert abs((lon-34.155)*n['sx']-x)<1e-6 and abs((44.492-lat)*n['sz']-z)<1e-6
for point in n['places']:
 assert point in n['points'];x,z=point[:2]
 for poly in m['buildings']:
  hit=False
  for a,b in zip(poly,poly[1:]+poly[:1]):
   if (a[1]>z)!=(b[1]>z) and x<(b[0]-a[0])*(z-a[1])/(b[1]-a[1])+a[0]:hit=not hit
   vx,vz=b[0]-a[0],b[1]-a[1];ll=vx*vx+vz*vz;t=max(0,min(1,((x-a[0])*vx+(z-a[1])*vz)/ll)) if ll else 0
   assert math.hypot(x-a[0]-t*vx,z-a[1]-t*vz)>.5
  assert not hit
print('PASS: coordinate round trips, bounds, unique street names, named landings outside buildings')
