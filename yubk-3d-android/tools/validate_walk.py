"""Asset integrity and camera-spawn checks, run without Android SDK."""
import pathlib,json,struct,gzip,math
root=pathlib.Path(__file__).resolve().parents[1]/'app/src/main/assets'
m=json.loads((root/'walk.json').read_text());raw=gzip.decompress(b''.join((root/f'walk.mesh.{i:03d}.gzpart').read_bytes() for i in range(m['mesh_parts'])))
n=struct.unpack_from('>i',raw)[0]
assert n>0 and n%3==0 and len(raw)==4+n*24
assert len(m['heights'])==m['w']*m['h']
for x,y,z,r,g,b in struct.iter_unpack('>6f',raw[4:]):
    assert all(math.isfinite(v) for v in (x,y,z,r,g,b))
    assert 0<=r<=1 and 0<=g<=1 and 0<=b<=1
x,z,heading=m['spawn'];assert abs(x)<(m['w']-1)*m['dx']/2 and abs(z)<(m['h']-1)*m['dz']/2
for poly in m['buildings']:
    hit=False
    for a,b in zip(poly,poly[1:]+poly[:1]):
        if (a[1]>z)!=(b[1]>z) and x<(b[0]-a[0])*(z-a[1])/(b[1]-a[1])+a[0]:hit=not hit
        vx,vz=b[0]-a[0],b[1]-a[1];length=vx*vx+vz*vz
        t=max(0,min(1,((x-a[0])*vx+(z-a[1])*vz)/length)) if length else 0
        assert math.hypot(x-a[0]-t*vx,z-a[1]-t*vz)>.5,'Spawn touches wall'
    assert not hit,'Spawn inside building'
print(f'PASS: {n:,} vertices, finite metric geometry, colors, height-grid size, walkable spawn')
