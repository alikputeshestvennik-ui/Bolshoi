import * as T from 'three';
import {mergeGeometries} from 'three/examples/jsm/utils/BufferGeometryUtils.js';
const V=(x,y,z)=>new T.Vector3(x,y,z);
export const scene=new T.Scene();scene.background=new T.Color(0xb8d0d3);scene.fog=new T.FogExp2(0xb8d0d3,.0034);
const mats={};
function material(name,color,roughness=.8,metalness=0){return mats[name]=new T.MeshStandardMaterial({color,roughness,metalness});}
material('rail',0x7d8581,.35,.8);material('sleeper',0x625242);material('ballast',0x7c8071);material('concrete',0xb2ada0);material('stone',0x727b70);material('brick',0xb2987c);material('roof',0x485961,.55,.15);material('wood',0x7f684a);material('white',0xded9bb);material('glass',0x3d6571,.22,.4);material('red',0xaa513b);material('steel',0x35484a,.5,.5);material('gold',0xd9ae65);material('leaf',0x4a6352);material('darkleaf',0x35574b);material('paleleaf',0x6e805a);material('bark',0x6f5944);material('green',0x315f51,.4,.35);material('cream',0xd0c7a5,.55,.1);material('black',0x253133,.6,.4);
const buckets=new Map(),boxGeo=new T.BoxGeometry(1,1,1);
function staticMesh(g,mat,pos,rot=null){const m=new T.Mesh(g,mats[mat]);m.position.copy(pos);if(rot)m.rotation.set(...rot);m.updateMatrix();const clone=g.index?g.toNonIndexed():g.clone();const geo=clone.applyMatrix4(m.matrix);if(!buckets.has(mat))buckets.set(mat,[]);buckets.get(mat).push(geo);return m;}
function box(mat,x,y,z,w,h,d,ry=0){const g=boxGeo.clone();g.scale(w,h,d);staticMesh(g,mat,V(x,y,z),[0,ry,0]);g.dispose();}
function cyl(mat,x,y,z,rt,rb,h,n=10,rotation=null){const g=new T.CylinderGeometry(rt,rb,h,n);staticMesh(g,mat,V(x,y,z),rotation);g.dispose();}
function between(a,b,width,height,mat){const dir=b.clone().sub(a),mid=a.clone().add(b).multiplyScalar(.5);const m=new T.Mesh(new T.BoxGeometry(width,height,dir.length()),mats[mat]);m.position.copy(mid);m.quaternion.setFromUnitVectors(V(0,0,1),dir.normalize());m.updateMatrix();const geo=m.geometry.toNonIndexed().applyMatrix4(m.matrix);if(!buckets.has(mat))buckets.set(mat,[]);buckets.get(mat).push(geo);m.geometry.dispose();}
let seed=81;function rnd(){seed=(seed*1664525+1013904223)>>>0;return seed/4294967296;}
const common=new T.LineCurve3(V(-62,1.3,0),V(-12,1.3,0));
const north=new T.CatmullRomCurve3([V(-12,1.3,0),V(0,2.4,-15),V(19,3,-28),V(40,3,-29),V(58,1.8,-16),V(66,1.3,0)],false,'centripetal');
const south=new T.CatmullRomCurve3([V(-12,1.3,0),V(0,1.6,16),V(23,1.6,29),V(47,1.6,25),V(62,1.3,12),V(66,1.3,0)],false,'centripetal');
const returning=new T.CatmullRomCurve3([V(66,1.3,0),V(78,1.3,28),V(57,1.3,57),V(7,1.3,66),V(-49,1.3,52),V(-74,1.3,28),V(-76,1.3,8),V(-62,1.3,0)],false,'centripetal');
const cl=common.getLength(),nl=north.getLength(),sl=south.getLength(),rl=returning.getLength();
export const lengths={north:{split:cl,merge:cl+nl,total:cl+nl+rl,station:cl+nl*.66},south:{split:cl,merge:cl+sl,total:cl+sl+rl,station:cl+sl*.62}};
export function sample(route,distance){const l=lengths[route],branch=route==='north'?north:south;let d=((distance%l.total)+l.total)%l.total;if(d<cl)return {p:common.getPointAt(d/cl),t:common.getTangentAt(d/cl)};d-=cl;if(d<branch.getLength())return {p:branch.getPointAt(d/branch.getLength()),t:branch.getTangentAt(d/branch.getLength())};d-=branch.getLength();return {p:returning.getPointAt(Math.min(1,d/rl)),t:returning.getTangentAt(Math.min(1,d/rl))};}
const trackPoints=[];
function track(curve){const len=curve.getLength(),count=Math.ceil(len/.9);for(let i=0;i<count;i++){const u=i/count,un=(i+1)/count,p=curve.getPointAt(u),n=curve.getPointAt(un),t=curve.getTangentAt(u),side=V(t.z,0,-t.x).normalize();trackPoints.push(p);
 const angle=Math.atan2(t.x,t.z);box('sleeper',p.x,p.y-.15,p.z,2.5,.2,.24,angle);between(p.clone().add(V(0,-.38,0)),n.clone().add(V(0,-.38,0)),3.3,.35,'ballast');
 for(const sign of [-1,1]){const offset=side.clone().multiplyScalar(.76*sign);between(p.clone().add(offset),n.clone().add(offset),.10,.15,'rail');}
 }}
for(const c of [common,north,south,returning])track(c);
function riverX(z){return 10+Math.sin(z*.035)*5;}
function groundY(x,z){const river=Math.abs(x-riverX(z));let y=-.45;if(river<7)y=-2.5+(river/7)**2*1.9;const dist=Math.max(Math.abs(x)/110,Math.abs(z-10)/100);if(dist>.85)y+=Math.pow(dist-.85,1.8)*28;return y;}
const terrain=new T.PlaneGeometry(500,420,110,100);terrain.rotateX(-Math.PI/2);const pos=terrain.attributes.position;const colors=[];
for(let i=0;i<pos.count;i++){const x=pos.getX(i),z=pos.getZ(i);let y=groundY(x,z);if(Math.abs(x)>95||z<-65||z>100)y+=Math.sin(x*.07)*Math.cos(z*.055)*6;pos.setY(i,y);const c=new T.Color(Math.abs(x-riverX(z))<9?0x95917a:0x687c51);c.multiplyScalar(.88+rnd()*.22);colors.push(c.r,c.g,c.b);}
terrain.setAttribute('color',new T.Float32BufferAttribute(colors,3));terrain.computeVertexNormals();const groundCanvas=document.createElement('canvas');groundCanvas.width=256;groundCanvas.height=256;const gc=groundCanvas.getContext('2d');const pixels=gc.createImageData(256,256);for(let i=0;i<pixels.data.length;i+=4){const n=195+rnd()*55;pixels.data[i]=n;pixels.data[i+1]=n;pixels.data[i+2]=n;pixels.data[i+3]=255;}gc.putImageData(pixels,0,0);const groundTexture=new T.CanvasTexture(groundCanvas);groundTexture.wrapS=groundTexture.wrapT=T.RepeatWrapping;groundTexture.repeat.set(55,46);groundTexture.colorSpace=T.SRGBColorSpace;const land=new T.Mesh(terrain,new T.MeshStandardMaterial({vertexColors:true,map:groundTexture,roughness:1}));land.receiveShadow=true;scene.add(land);
const riverGeom=new T.BufferGeometry();const rv=[],ri=[];for(let i=0;i<=100;i++){const z=-210+i*4.2;rv.push(riverX(z)-5.7,-1.45,z,riverX(z)+5.7,-1.45,z);if(i<100){const k=i*2;ri.push(k,k+1,k+2,k+1,k+3,k+2);}}
riverGeom.setAttribute('position',new T.Float32BufferAttribute(rv,3));riverGeom.setIndex(ri);riverGeom.computeVertexNormals();export const water=new T.Mesh(riverGeom,new T.MeshStandardMaterial({color:0x5b9b9e,roughness:.3,metalness:.08,transparent:true,opacity:.87,side:T.DoubleSide}));scene.add(water);
// Low ripples catch the sun, while keeping the river entirely procedural and offline.
for(let i=0;i<75;i++){const z=-180+i*4.8,x=riverX(z);box('glass',x+Math.sin(i)*2,-1.40,z,3+rnd()*4,.015,.08);}
// Bridge decks, piers and steel trusses on each river crossing.
for(const curve of [north,south,returning]){const pts=[];for(let i=0;i<=150;i++){const p=curve.getPointAt(i/150);if(Math.abs(p.x-riverX(p.z))<9)pts.push(p);}if(pts.length<2)continue;
 const a=pts[0],b=pts[pts.length-1],dir=b.clone().sub(a).normalize(),side=V(dir.z,0,-dir.x);between(a.clone().add(V(0,-.9,0)),b.clone().add(V(0,-.9,0)),4.3,.8,'concrete');
 for(let i=0;i<=4;i++){const p=a.clone().lerp(b,i/4);box('stone',p.x,(p.y-3)/2,p.z,1.8,p.y+1,3.3,Math.atan2(dir.x,dir.z));}
 for(const sign of [-1,1]){const a1=a.clone().addScaledVector(side,sign*2),b1=b.clone().addScaledVector(side,sign*2);between(a1.clone().add(V(0,.3,0)),b1.clone().add(V(0,.3,0)),.18,.2,'steel');between(a1.clone().add(V(0,3,0)),b1.clone().add(V(0,3,0)),.16,.16,'steel');for(let i=0;i<6;i++){const p=a1.clone().lerp(b1,i/6),q=a1.clone().lerp(b1,(i+1)/6);between(p.clone().add(V(0,.3,0)),q.clone().add(V(0,3,0)),.14,.14,'steel');between(p.clone().add(V(0,3,0)),q.clone().add(V(0,.3,0)),.14,.14,'steel');}}
}
function label(text,width=10,height=2.1){const c=document.createElement('canvas');c.width=512;c.height=128;const x=c.getContext('2d');x.fillStyle='#203c45';x.fillRect(0,0,512,128);x.strokeStyle='#dbc89b';x.lineWidth=5;x.strokeRect(7,7,498,114);x.fillStyle='#f4e8c9';x.textAlign='center';x.textBaseline='middle';x.font='bold 44px Arial';x.fillText(text,256,66);const tex=new T.CanvasTexture(c);tex.colorSpace=T.SRGBColorSpace;const mesh=new T.Mesh(new T.PlaneGeometry(width,height),new T.MeshStandardMaterial({map:tex,side:T.DoubleSide,roughness:.9}));return mesh;}
const stationDefs=[{id:'depot',name:'СОСНОВАЯ',route:'north',distance:21,side:1},{id:'summit',name:'НАГОРНАЯ',route:'north',distance:lengths.north.station,side:-1},{id:'port',name:'РЕЧНОЙ ПОРТ',route:'south',distance:lengths.south.station,side:1}];
export const stationMarkers=[];
function station(def){const {p,t}=sample(def.route,def.distance),side=V(t.z,0,-t.x).normalize().multiplyScalar(def.side),angle=Math.atan2(t.x,t.z);const at=(x,z,y=0)=>p.clone().addScaledVector(side,x).addScaledVector(t,z).add(V(0,y,0));
 const platform=at(4,0,-.1);box('concrete',platform.x,platform.y,platform.z,4.5,.7,23,angle);for(let i=-10;i<=10;i++){const a=at(2.0,i,.29);box('gold',a.x,a.y,a.z,.24,.04,.65,angle);}
 const b=at(10,3,2.1);box('brick',b.x,b.y,b.z,7,4.7,14,angle);const roof=at(10,3,4.9);box('roof',roof.x,roof.y,roof.z,8,.55,15.5,angle);const chimney=at(11,6,6);box('brick',chimney.x,chimney.y,chimney.z,.9,2,.9,angle);
 for(let z=-2;z<=8;z+=3.2){const w=at(6.45,z,2.4);box('glass',w.x,w.y,w.z,.05,1.5,1.7,angle);const frame=at(6.42,z,2.4);box('white',frame.x,frame.y,frame.z,.08,1.65,.10,angle);}
 const canopy=at(4,0,4);box('roof',canopy.x,canopy.y,canopy.z,4.6,.18,21,angle);for(const z of [-9,-3,3,9]){const q=at(5.5,z,1.9);box('steel',q.x,q.y,q.z,.12,3.8,.12);}
 const sign=label(def.name,8,1.7);sign.position.copy(at(4,0,4.9));sign.rotation.y=angle-(def.side===1?Math.PI/2:-Math.PI/2);scene.add(sign);
 const stop=at(1.8,0,1.1);box('white',stop.x,stop.y,stop.z,.14,2,.14);const board=label('СТОП',1.6,.7);board.position.copy(at(1.8,0,2.2));board.rotation.y=angle+Math.PI;scene.add(board);
 stationMarkers.push({id:def.id,p:p.clone()});
 for(let z=-10;z<=10;z+=10){const q=at(3.6,z,.7);box('wood',q.x,q.y,q.z,1.4,.2,2.1,angle);}
}
stationDefs.forEach(station);
// Freight terminal: stacked containers, gantry crane, warehouse and quay.
box('concrete',29,-.3,40,29,.5,17);box('brick',51,2.6,45,13,5.8,12);box('roof',51,5.6,45,14,.3,13);
for(let i=0;i<8;i++){const x=18+(i%4)*6.3,z=39+Math.floor(i/4)*4.4;box(i%2?'red':'steel',x,1.3,z,5.8,2.7,2.7);for(let j=-2;j<=2;j++)box('black',x+j,1.3,z+1.38,.09,2.5,.05);}
for(const x of [21,38]){box('gold',x,5,47,.55,11,.55);box('gold',x,5,36,.55,11,.55);}box('gold',29.5,10.5,41.5,18,.7,12);box('steel',29.5,9.5,41.5,2,1.4,2);box('black',29.5,6.6,41.5,.06,5,.06);
// Telegraph poles and lines.
for(let i=0;i<trackPoints.length;i+=26){const p=trackPoints[i];box('wood',p.x+4,p.y+2.5,p.z+4,.16,5.7,.16);box('wood',p.x+4,p.y+5.2,p.z+4,2,.14,.14);if(i+26<trackPoints.length){const n=trackPoints[i+26];if(p.distanceTo(n)<30)between(p.clone().add(V(4,5.2,4)),n.clone().add(V(4,5.2,4)),.025,.025,'black');}}
// Forest kept clear of tracks and stations; merged geometry reduces GPU draw calls.
for(let i=0;i<510;i++){const x=-150+rnd()*300,z=-105+rnd()*240;if(Math.abs(x-riverX(z))<11)continue;if(trackPoints.some(p=>(p.x-x)**2+(p.z-z)**2<55))continue;if(stationMarkers.some(s=>(s.p.x-x)**2+(s.p.z-z)**2<330))continue;if(x>10&&x<62&&z>32&&z<55)continue;
 const y=groundY(x,z),h=4+rnd()*8;cyl('bark',x,y+h*.32,z,.16,.24,h*.65,6);for(let k=0;k<3;k++)cyl(i%3===0?'paleleaf':i%2?'leaf':'darkleaf',x,y+h*(.45+k*.2),z,0,h*(.29-k*.055),h*.57,8);}
// Rocky ridgelines frame the playable valley.
for(let i=0;i<32;i++){const angle=i/32*Math.PI*2,dist=170+rnd()*30,x=Math.cos(angle)*dist,z=Math.sin(angle)*dist;const height=28+rnd()*38,base=groundY(x,z)-18;const g=new T.ConeGeometry(22+rnd()*18,height,9,3);g.rotateY(rnd()*Math.PI);staticMesh(g,'stone',V(x,base+height/2,z));g.dispose();}

for(const [name,geos] of buckets){const merged=mergeGeometries(geos,false);if(!merged)throw new Error('Geometry merge failed: '+name);const mesh=new T.Mesh(merged,mats[name]);mesh.castShadow=true;mesh.receiveShadow=true;scene.add(mesh);geos.forEach(g=>g.dispose());}buckets.clear();
const hemi=new T.HemisphereLight(0xdcebf1,0x687050,1.8);scene.add(hemi);export const sun=new T.DirectionalLight(0xffedcf,2.9);sun.position.set(-65,110,60);sun.castShadow=true;sun.shadow.mapSize.set(2048,2048);Object.assign(sun.shadow.camera,{left:-115,right:115,top:100,bottom:-100,near:1,far:330});sun.shadow.normalBias=.06;sun.shadow.bias=-.00015;scene.add(sun);scene.add(sun.target);
export function setLighting(dusk){scene.background.set(dusk?0xb6a393:0xb8d0d3);scene.fog.color.copy(scene.background);sun.color.set(dusk?0xffc790:0xffedcf);sun.position.set(dusk?-115:-65,dusk?52:110,60);sun.intensity=dusk?2.5:2.9;hemi.intensity=dusk?1.4:1.8;}
// A detailed diesel and three coaches, using bogies, roof equipment, handrails and glazing.
function carBox(group,mat,x,y,z,w,h,d){const mesh=new T.Mesh(new T.BoxGeometry(w,h,d),mats[mat]);mesh.position.set(x,y,z);mesh.castShadow=true;mesh.receiveShadow=true;group.add(mesh);return mesh;}
function carCylinder(group,mat,x,y,z,r,h,rotation=0){const mesh=new T.Mesh(new T.CylinderGeometry(r,r,h,12),mats[mat]);mesh.position.set(x,y,z);mesh.rotation.z=rotation;mesh.castShadow=true;group.add(mesh);return mesh;}
function mergeCar(group){const map=new Map();for(const m of [...group.children]){if(!m.isMesh||m.userData.keep)continue;m.updateMatrix();const key=m.material;if(!map.has(key))map.set(key,[]);map.get(key).push(m.geometry.clone().applyMatrix4(m.matrix));group.remove(m);m.geometry.dispose();}for(const [mat,geos] of map){const m=new T.Mesh(mergeGeometries(geos),mat);m.castShadow=true;m.receiveShadow=true;group.add(m);geos.forEach(g=>g.dispose());}}
export const train=new T.Group();scene.add(train);export const cars=[],wheelSets=[];
for(let i=0;i<4;i++){
 const group=new T.Group(),loco=i===0;train.add(group);cars.push(group);carBox(group,'black',0,.68,0,2.45,.4,7.9);carBox(group,'steel',0,1,0,2.55,.2,8.2);
 if(loco){
  carBox(group,'green',0,1.9,-.65,2.4,1.8,5.8);carBox(group,'cream',0,2.23,-.65,2.43,.27,5.8);carBox(group,'green',0,2.35,2.4,2.5,2.7,2.25);carBox(group,'roof',0,3.78,2.35,2.7,.22,2.6);
  for(const x of [-.63,.63])carBox(group,'glass',x,3.03,3.54,1.08,.83,.035);
  carBox(group,'cream',0,2.45,3.56,2.5,.18,.06);carBox(group,'red',0,1.23,3.63,2.6,.36,.25);
  for(const x of [-1.27,1.27]){carBox(group,'glass',x,3,2.4,.035,.9,1.6);carBox(group,'cream',x,2.3,2.4,.045,.16,2.1);for(let z=-3;z<1;z+=.35)carBox(group,'black',x*.95,2.1,z,.05,.9,.12);carBox(group,'gold',x*1.1,1.85,-.6,.06,.06,5.9);for(const z of [-3.3,-1,1])carBox(group,'gold',x*1.1,1.4,z,.055,1,.055);}
  for(const z of [-2.3,-.4])carCylinder(group,'black',0,2.91,z,.65,.13);
  carCylinder(group,'black',.5,3.2,-1.5,.18,.65);carBox(group,'steel',0,.7,-.1,1.4,.85,2.2);
  const num=label('ТЭМ • 042',1.4,.38);num.position.set(0,1.85,3.59);group.add(num);num.userData.keep=true;
  const lampMat=new T.MeshStandardMaterial({color:0xffecc0,emissive:0xffd787,emissiveIntensity:3});for(const x of [-.8,.8]){const lamp=new T.Mesh(new T.SphereGeometry(.14,10,8),lampMat);lamp.position.set(x,1.8,3.64);lamp.userData.keep=true;group.add(lamp);}
 }else if(i<3){carBox(group,'cream',0,2,0,2.5,2.1,7.8);carBox(group,'green',0,1.28,0,2.54,.65,7.9);carBox(group,'roof',0,3.13,0,2.65,.26,8.0);for(const x of [-1.26,1.26])for(let z=-2.7;z<=2.8;z+=1.1){carBox(group,'glass',x,2.38,z,.035,.88,.8);carBox(group,'white',x*1.005,2.38,z+.43,.04,1.05,.055);}for(const z of [-3.91,3.91]){carBox(group,'black',0,1.9,z,.9,1.8,.12);carBox(group,'glass',0,2.4,z*1.02,.5,.6,.04);}for(const z of [-2,0,2])carBox(group,'concrete',0,3.35,z,.9,.2,.65);
 }else {carBox(group,'red',0,1.1,0,2.7,.25,8);carBox(group,'red',0,2,0,2.3,1.6,6.5);carBox(group,'roof',0,2.86,0,2.35,.15,6.6);for(const x of [-1.18,1.18])for(let z=-3;z<=3;z+=.5)carBox(group,'cream',x,2,z,.04,1.5,.04);}
 for(const z of [-2.45,2.45]){carBox(group,'black',0,.49,z,2.4,.6,1.65);for(const dz of [-.48,.48]){const ax=carCylinder(group,'steel',0,.42,z+dz,.42,2.85,Math.PI/2);wheelSets.push(ax);}}
 for(const z of [-4.35,4.35]){carBox(group,'black',0,.7,z,.3,.24,.8);for(const x of [-.84,.84]){const buff=carCylinder(group,'steel',x,.87,z,.18,.18);buff.rotation.x=Math.PI/2;}}
 mergeCar(group);
}
const headlight=new T.SpotLight(0xffe7b6,30,45,.45,.6,1);headlight.position.set(0,2.1,4);headlight.target.position.set(0,.2,30);cars[0].add(headlight,headlight.target);
export function placeTrain(route,distance){for(let i=0;i<cars.length;i++){const {p,t}=sample(route,distance-i*9);cars[i].position.copy(p).add(V(0,.22,0));cars[i].rotation.y=Math.atan2(t.x,t.z);}}
// Signal at the turnout, animated with the route lock.
const signal=new T.Group();signal.position.set(-20,1.3,3);scene.add(signal);carBox(signal,'steel',0,1.8,0,.12,3.6,.12);carBox(signal,'black',0,3.65,0,.55,1.25,.35);
const redMat=new T.MeshStandardMaterial({color:0x651e13,emissive:0xff3e16,emissiveIntensity:0});const greenMat=new T.MeshStandardMaterial({color:0x99b669,emissive:0xb0f784,emissiveIntensity:2});
for(const [y,mat] of [[3.95,redMat],[3.4,greenMat]]){const m=new T.Mesh(new T.SphereGeometry(.16,10,8),mat);m.position.set(0,y,.22);signal.add(m);}
export function signalState(locked){redMat.emissiveIntensity=locked?2:0;greenMat.emissiveIntensity=locked?0:2;}
// Smoke is instanced and stays bounded, even on a long free ride.
const smokeMaterial=new T.MeshBasicMaterial({color:0xb8b7aa,transparent:true,opacity:.12,depthWrite:false});const smoke=new T.InstancedMesh(new T.IcosahedronGeometry(1,1),smokeMaterial,18);smoke.frustumCulled=false;scene.add(smoke);const dummy=new T.Object3D();
export function animateWorld(time,route,distance,speed,throttle){water.material.color.setHSL(.49,.22,.35+Math.sin(time*.5)*.025);const {p,t}=sample(route,distance-1.5);for(let i=0;i<18;i++){const age=(time*.55+i/18)%1;dummy.position.copy(p).addScaledVector(t,-age*speed*1.6).add(V(age*3,4+age*8,age*2));dummy.scale.setScalar((.24+age*1.9)*(throttle>0?1:.35));dummy.updateMatrix();smoke.setMatrixAt(i,dummy.matrix);}smoke.instanceMatrix.needsUpdate=true;}
export function createCab(){
 const cab=new T.Group(),panelMat=new T.MeshStandardMaterial({color:0x263d3d,roughness:.8});
 const panel=new T.Mesh(new T.BoxGeometry(2.15,.55,.8),panelMat);panel.position.set(0,-.5,-2.6);cab.add(panel);
 const c=document.createElement('canvas');c.width=1024;c.height=256;const g=c.getContext('2d'),tex=new T.CanvasTexture(c);tex.colorSpace=T.SRGBColorSpace;
 cab.userData.update=(speed,brake,throttle)=>{
  g.fillStyle='#203334';g.fillRect(0,0,1024,256);
  const values=[speed,5-brake*.04,throttle,72,2+throttle*.04],max=[90,6,100,100,8],units=['км/ч','бар','тяга %','вольт','масло'];
  for(let i=0;i<5;i++){const x=110+i*200,y=112,r=76;g.fillStyle='#132328';g.beginPath();g.arc(x,y,r,0,Math.PI*2);g.fill();g.strokeStyle='#d9cc9a';g.lineWidth=3;g.stroke();
   for(let j=0;j<=10;j++){const a=Math.PI*.75+j/10*Math.PI*1.5;g.strokeStyle=j>8?'#bd7751':'#b9c4af';g.lineWidth=2;g.beginPath();g.moveTo(x+Math.cos(a)*61,y+Math.sin(a)*61);g.lineTo(x+Math.cos(a)*69,y+Math.sin(a)*69);g.stroke();}
   const angle=Math.PI*.75+Math.min(1,values[i]/max[i])*Math.PI*1.5;g.strokeStyle='#e9bc70';g.lineWidth=4;g.beginPath();g.moveTo(x,y);g.lineTo(x+Math.cos(angle)*55,y+Math.sin(angle)*55);g.stroke();g.fillStyle='#e6dcb1';g.textAlign='center';g.font='20px Arial';g.fillText(units[i],x,160);g.font='22px monospace';g.fillText(Math.round(values[i]),x,224);
  }tex.needsUpdate=true;
 };
 cab.userData.update(0,0,0);
 const face=new T.Mesh(new T.PlaneGeometry(1.8,.45),new T.MeshBasicMaterial({map:tex}));face.position.set(0,-.15,-2.17);face.rotation.x=-.12;cab.add(face);
 for(const x of [-1.13,1.13]){const frame=new T.Mesh(new T.BoxGeometry(.09,1.9,.12),panelMat);frame.position.set(x,.15,-1.35);cab.add(frame);}return cab;
}
