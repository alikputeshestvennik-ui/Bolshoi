'use strict';
(() => {
const $ = id => document.getElementById(id), canvas = $('space'), ctx = canvas.getContext('2d');
const TAU = Math.PI * 2;
let W, H, cx, cy, radius, time = 0, last = 0, mode = 'menu', sound = false, audio;
let game, particles = [], rings = [], pointer = null, spawnClock = 0, shootClock = 0;
const keys = new Set();
let best = 0;
try { best = Number(localStorage.getItem('echo-best')) || 0; sound = localStorage.getItem('echo-sound') === '1'; } catch (_) {}
const stars = Array.from({length:90}, (_,i) => ({x: Math.sin(i*127.1)*.5+.5, y:Math.cos(i*311.7)*.5+.5, r:.5+(i%3)*.4}));
function resize() {
  W = innerWidth; H = innerHeight; const d = Math.min(devicePixelRatio || 1, 2);
  canvas.width = Math.round(W*d); canvas.height = Math.round(H*d); ctx.setTransform(d,0,0,d,0,0);
  cx = W/2; cy = H*.49; radius = Math.min(W*.36,H*.22);
  if (game) { game.player.x = Math.max(18,Math.min(W-18,game.player.x)); game.player.y = Math.max(185,Math.min(H-125,game.player.y)); }
}
addEventListener('resize',resize); resize();
function beep(freq=440,duration=.1,type='sine',gain=.045) {
  if (!sound) return;
  try { audio ||= new (window.AudioContext || window.webkitAudioContext)(); audio.resume();
    const o=audio.createOscillator(), g=audio.createGain(); o.type=type; o.frequency.value=freq;
    g.gain.setValueAtTime(gain,audio.currentTime); g.gain.exponentialRampToValueAtTime(.001,audio.currentTime+duration);
    o.connect(g);g.connect(audio.destination);o.start();o.stop(audio.currentTime+duration);
  } catch (_) {}
}
function soundUI() { $('sound').textContent = sound ? '♫' : '♪'; $('sound').setAttribute('aria-label',sound ? 'Выключить звук':'Включить звук'); }
$('sound').onclick = () => {sound=!sound;soundUI();try{localStorage.setItem('echo-sound',sound?'1':'0');}catch(_){} beep(540,.2);};soundUI();
function record() { $('record').textContent = String(best).padStart(4,'0'); } record();
function burst(x,y,color,n=16) { for(let i=0;i<n;i++) {const a=Math.random()*TAU,s=25+Math.random()*110;particles.push({x,y,vx:Math.cos(a)*s,vy:Math.sin(a)*s,t:.4+Math.random()*.5,max:1,color});} }
function ring(x,y,color,max=150){rings.push({x,y,r:4,max,t:1,color});}
function overlay(label,title,message,button,action,home=true){
  $('overlay').hidden=false;$('eyebrow').textContent=label;$('title').textContent=title;$('message').textContent=message;
  $('choices').replaceChildren();$('continue').hidden=!button;$('continue').textContent=button || '';$('continue').onclick=action;
  $('home').hidden=!home; pointer=null;keys.clear();
}
function closeOverlay(){$('overlay').hidden=true;last=performance.now();}
function start(){
  mode='play';game={wave:1,score:0,hp:100,energy:100,waveTime:0,total:0,spawned:0,kills:0,damage:1,rate:.48,speed:250,pulsePower:3,regen:8,player:{x:cx,y:cy+radius},enemies:[],shots:[],gems:[],bossSpawned:false};
  particles=[];rings=[];spawnClock=.8;shootClock=0;pointer=null;
  $('menu').hidden=true;$('hud').hidden=false;$('controls').hidden=false;$('pause').hidden=false;closeOverlay();updateUI();
  beep(220,.4);ring(cx,cy,'#a5f4ce',radius*2);
}
$('start').onclick=start;
$('how').onclick=()=>{mode='help';overlay('БОРТОВОЙ ЖУРНАЛ','Сохрани свет.', 'Веди корабль пальцем по экрану. Он автоматически стреляет в ближайшего врага.\n\nНе дай осколкам добраться до маяка в центре. Подбирай светящиеся частицы — они заряжают ЭХО.\n\nКнопка ЭХО выпускает мощную волну от маяка. Между секторами выбирай улучшение. Пройди все 8 секторов.','Понятно',()=>{mode='menu';closeOverlay();},false);};
function home(){mode='menu';game=null;pointer=null;$('menu').hidden=false;$('hud').hidden=true;$('controls').hidden=true;$('pause').hidden=true;closeOverlay();record();}
$('home').onclick=home;
function pause(){if(mode!=='play')return;mode='pause';overlay('СИГНАЛ ПРИОСТАНОВЛЕН','Тишина между звёзд.','Маяк ждёт тебя.','Продолжить',()=>{mode='play';closeOverlay();});}
$('pause').onclick=pause;window.echoPause=pause;
window.echoBack=()=>{if(mode==='play')pause();else if(mode==='pause'){mode='play';closeOverlay();}else if(mode==='help'){mode='menu';closeOverlay();}else if(mode==='menu'){$('how').click();}};
addEventListener('visibilitychange',()=>{if(document.hidden)pause();});
addEventListener('blur',()=>{keys.clear();pause();});
function upgrade(){
  mode='upgrade';overlay('СЕКТОР '+String(game.wave).padStart(2,'0')+' / ЗАВЕРШЁН','Свет становится сильнее.','Выбери один модуль для следующего сектора.',null,null);
  const upgrades=[
    ['Призма','Урон выстрела +1',()=>game.damage++],
    ['Ритм','Скорострельность +25%',()=>game.rate=Math.max(.12,game.rate*.8)],
    ['Регенерация','Восстановить маяку 35% прочности',()=>game.hp=Math.min(100,game.hp+35)],
    ['Резонанс','Урон ЭХО +2, зарядка быстрее',()=>{game.pulsePower+=2;game.regen+=2;}],
    ['Парус','Скорость корабля +20%',()=>game.speed*=1.2]
  ];
  // Repair remains available so a damaged beacon always has a recovery route.
  const picks=[upgrades[2],...upgrades.filter((_,i)=>i!==2).sort(()=>Math.random()-.5).slice(0,2)];
  for(const [title,desc,apply] of picks){const b=document.createElement('button');b.className='choice';const strong=document.createElement('strong');strong.textContent=title;const span=document.createElement('span');span.textContent=desc;b.append(strong,span);b.onclick=()=>{apply();game.wave++;game.waveTime=0;game.spawned=0;game.bossSpawned=false;game.shots=[];spawnClock=1.2;mode='play';closeOverlay();beep(650,.2);updateUI();};$('choices').append(b);}
}
function finish(win){
  if(mode!=='play')return;mode=win?'win':'lost';
  if(win)game.score+=1000+Math.round(game.hp)*10;
  best=Math.max(best,game.score);try{localStorage.setItem('echo-best',String(best));}catch(_){}record();
  overlay(win?'СВЯЗЬ ВОССТАНОВЛЕНА':'СИГНАЛ ПОТЕРЯН',win?'Ты вернул рассвет.':'Даже свету нужна вторая попытка.',
    'Сигнал: '+game.score+'  ·  Сектор: '+game.wave+'/8\n'+(win?'Восемь секторов снова видят маяк.':'Каждый полёт делает тебя точнее.')+'\nЛучший сигнал: '+best, 'Новый полёт ↗',start);
  beep(win?880:100,.6,'sine',.08);
}
function spawn(boss=false){
  const a=Math.random()*TAU, r=Math.max(W,H)*.63;
  const type=boss?'boss':(game.wave>=3 && game.spawned%5===0?'heavy':game.wave>=2 && game.spawned%3===0?'fast':'normal');
  const hp=type==='boss'?65:type==='heavy'?4+Math.floor(game.wave/3):type==='fast'?1:2+Math.floor(game.wave/4);
  game.enemies.push({x:cx+Math.cos(a)*r,y:cy+Math.sin(a)*r,hp,maxHp:hp,type,speed:type==='boss'?9:type==='heavy'?15+game.wave*1.4:type==='fast'?35+game.wave*2:22+game.wave*1.8,r:type==='boss'?30:type==='heavy'?15:type==='fast'?8:11,phase:Math.random()*TAU});
  if(!boss)game.spawned++;
}
function destroy(e){if(e.dead)return;e.dead=true;game.kills++;game.score+=e.type==='boss'?600:e.type==='heavy'?65:35;burst(e.x,e.y,e.type==='boss'?'#f4cd8e':'#a5f4ce',e.type==='boss'?50:14);game.gems.push({x:e.x,y:e.y,t:10});beep(e.type==='heavy'?180:340,.07,'triangle',.022);}
function pulse(){if(mode!=='play'||game.energy<100)return;game.energy=0;ring(cx,cy,'#b5ffe2',Math.max(W,H));burst(cx,cy,'#d8ffdd',35);for(const e of game.enemies){e.hp-=game.pulsePower;if(e.hp<=0)destroy(e);else{const d=Math.hypot(e.x-cx,e.y-cy)||1;e.x+=(e.x-cx)/d*38;e.y+=(e.y-cy)/d*38;}}beep(90,.5,'sine',.12);updateUI();}
$('pulse').onclick=pulse;
canvas.addEventListener('pointerdown',e=>{if(mode!=='play'||pointer)return;pointer={id:e.pointerId,x:e.clientX,y:e.clientY};canvas.setPointerCapture(e.pointerId);});
canvas.addEventListener('pointermove',e=>{if(pointer&&pointer.id===e.pointerId){pointer.x=e.clientX;pointer.y=e.clientY;}});
for(const name of ['pointerup','pointercancel','lostpointercapture'])canvas.addEventListener(name,e=>{if(pointer?.id===e.pointerId)pointer=null;});
addEventListener('keydown',e=>{if(['ArrowUp','ArrowDown','ArrowLeft','ArrowRight',' '].includes(e.key))e.preventDefault();keys.add(e.key.toLowerCase());if(e.key===' ')pulse();if(e.key==='Escape')window.echoBack();});
addEventListener('keyup',e=>keys.delete(e.key.toLowerCase()));
function updateUI(){if(!game)return;$('wave').textContent=String(game.wave).padStart(2,'0')+' / 08';$('score').textContent=String(game.score).padStart(4,'0');$('health').style.width=game.hp+'%';$('health').style.background=game.hp<30?'#ff8b7d':'#a5f4ce';$('hp').textContent=Math.ceil(game.hp)+'%';$('charge').textContent=game.energy>=100?'ЭХО ГОТОВО':Math.floor(game.energy)+'% · ЗАРЯДКА';$('pulse').disabled=game.energy<100;$('hint').textContent=game.total<9?'Веди корабль пальцем. Он стреляет сам.':game.wave===8?'ПОСЛЕДНИЙ СЕКТОР · ПОГАСИ ПОЖИРАТЕЛЯ':game.enemies.length===0?'Сектор очищен…':'Подбирай свет. Защищай маяк.';}
function step(dt){
  if(mode!=='play')return;
  const p=game.player;game.waveTime+=dt;game.total+=dt;game.energy=Math.min(100,game.energy+dt*game.regen);
  let tx=p.x,ty=p.y;
  if(pointer){tx=pointer.x;ty=pointer.y-32;}else {tx+=(Number(keys.has('arrowright')||keys.has('d'))-Number(keys.has('arrowleft')||keys.has('a')))*game.speed*dt;ty+=(Number(keys.has('arrowdown')||keys.has('s'))-Number(keys.has('arrowup')||keys.has('w')))*game.speed*dt;}
  const dx=tx-p.x,dy=ty-p.y,d=Math.hypot(dx,dy),s=Math.min(d,game.speed*dt);if(d>0){p.x+=dx/d*s;p.y+=dy/d*s;}
  p.x=Math.max(18,Math.min(W-18,p.x));p.y=Math.max(180,Math.min(H-125,p.y));
  const count=5+game.wave*2;spawnClock-=dt;
  if(game.spawned<count && spawnClock<=0){spawn();spawnClock=Math.max(.65,2.1-game.wave*.14);}
  if(game.wave===8&&!game.bossSpawned&&game.waveTime>3){spawn(true);game.bossSpawned=true;}
  shootClock-=dt;
  const alive=game.enemies.filter(e=>!e.dead);let nearest=null,nd=Infinity;
  for(const e of alive){const d=Math.hypot(e.x-p.x,e.y-p.y);if(d<nd){nd=d;nearest=e;}}
  if(nearest&&shootClock<=0){const a=Math.atan2(nearest.y-p.y,nearest.x-p.x);game.shots.push({x:p.x,y:p.y,vx:Math.cos(a)*520,vy:Math.sin(a)*520,t:2});shootClock=game.rate;beep(800,.035,'sine',.008);}
  for(const b of game.shots){b.x+=b.vx*dt;b.y+=b.vy*dt;b.t-=dt;for(const e of alive){if(!e.dead&&Math.hypot(b.x-e.x,b.y-e.y)<e.r+6){e.hp-=game.damage;b.t=0;burst(b.x,b.y,'#d9edc5',3);if(e.hp<=0)destroy(e);break;}}}
  for(const e of alive){if(e.dead)continue;const d=Math.hypot(e.x-cx,e.y-cy)||1;e.x+=(cx-e.x)/d*e.speed*dt;e.y+=(cy-e.y)/d*e.speed*dt;
    if(d<e.r+21){game.hp=Math.max(0,game.hp-(e.type==='boss'?60:e.type==='heavy'?18:10));e.dead=true;burst(cx,cy,'#ff8b7d',25);ring(cx,cy,'#ff8b7d',80);beep(70,.2,'sawtooth',.03);}
  }
  for(const g of game.gems){g.t-=dt;const d=Math.hypot(g.x-p.x,g.y-p.y);if(d<95&&d>1){g.x+=(p.x-g.x)/d*220*dt;g.y+=(p.y-g.y)/d*220*dt;}if(d<19){g.t=0;game.score+=10;game.energy=Math.min(100,game.energy+12);beep(1100,.06,'sine',.018);}}
  game.enemies=game.enemies.filter(e=>!e.dead);game.shots=game.shots.filter(b=>b.t>0);game.gems=game.gems.filter(g=>g.t>0);
  if(game.hp<=0)finish(false);
  else if(game.spawned>=count&&game.enemies.length===0&&(game.wave!==8||game.bossSpawned)){if(game.wave===8)finish(true);else upgrade();}
  updateUI();
}
function circle(x,y,r,color,width=1){ctx.strokeStyle=color;ctx.lineWidth=width;ctx.beginPath();ctx.arc(x,y,r,0,TAU);ctx.stroke();}
function draw(dt){
  ctx.fillStyle='#07191f';ctx.fillRect(0,0,W,H);
  const glow=ctx.createRadialGradient(cx,cy,0,cx,cy,Math.max(W,H)*.55);glow.addColorStop(0,'#123d42');glow.addColorStop(1,'#07191f');ctx.fillStyle=glow;ctx.fillRect(0,0,W,H);
  for(const st of stars){ctx.globalAlpha=.25+Math.sin(time*.7+st.x*10)*.15;ctx.fillStyle='#c8e8d5';ctx.fillRect(st.x*W,st.y*H,st.r,st.r);}ctx.globalAlpha=1;
  const menu=mode==='menu'||mode==='help';const ox=menu?W*.69:cx,oy=menu?H*.47:cy,rr=menu?W*.39:radius;
  for(let i=1;i<=3;i++)circle(ox,oy,rr*i/2.1,'#2b55574d');
  ctx.save();ctx.translate(ox,oy);ctx.rotate(-.45);ctx.scale(1,.52);circle(0,0,rr*1.18,'#52878299');ctx.restore();
  ctx.save();ctx.translate(ox,oy);ctx.rotate(time*.1);ctx.strokeStyle='#7ee6c222';ctx.beginPath();ctx.moveTo(-rr*1.35,0);ctx.lineTo(rr*1.35,0);ctx.moveTo(0,-rr*1.35);ctx.lineTo(0,rr*1.35);ctx.stroke();ctx.restore();
  const bloom=ctx.createRadialGradient(ox,oy,0,ox,oy,55);bloom.addColorStop(0,'#a5f4ce55');bloom.addColorStop(1,'#a5f4ce00');ctx.fillStyle=bloom;ctx.fillRect(ox-55,oy-55,110,110);
  circle(ox,oy,21+Math.sin(time*2)*2,'#a5f4ce',1.5);circle(ox,oy,12,'#dcecc8',1);ctx.fillStyle='#e7f4d4';ctx.save();ctx.translate(ox,oy);ctx.rotate(Math.PI/4);ctx.fillRect(-5,-5,10,10);ctx.restore();
  if(menu){for(let i=0;i<3;i++){const a=time*.18+i*2.1;const x=ox+Math.cos(a)*rr,y=oy+Math.sin(a)*rr*.7;circle(x,y,3,'#a5f4ce');}ship(ox-Math.cos(time*.23)*rr,oy+Math.sin(time*.23)*rr*.6,time*.23-1.5);}
  if(game&&!menu){
    for(const g of game.gems){ctx.save();ctx.translate(g.x,g.y);ctx.rotate(time*2);ctx.fillStyle='#c4f4ac';ctx.fillRect(-3,-3,6,6);ctx.restore();}
    for(const b of game.shots){ctx.strokeStyle='#d5ffd8';ctx.lineWidth=2;ctx.beginPath();ctx.moveTo(b.x,b.y);ctx.lineTo(b.x-b.vx*.02,b.y-b.vy*.02);ctx.stroke();}
    for(const e of game.enemies){ctx.save();ctx.translate(e.x,e.y);ctx.rotate(time*(e.type==='heavy'?.5:1)+e.phase);const color=e.type==='boss'?'#f7cd87':e.type==='fast'?'#ffb7a0':'#dc857d';ctx.strokeStyle=color;ctx.fillStyle=e.type==='boss'?'#45362b':'#3d2b30';ctx.lineWidth=1.5;ctx.beginPath();const n=e.type==='boss'?6:e.type==='heavy'?4:3;for(let i=0;i<n;i++){const a=i*TAU/n;ctx.lineTo(Math.cos(a)*e.r,Math.sin(a)*e.r);}ctx.closePath();ctx.fill();ctx.stroke();circle(0,0,e.r*.35,color);ctx.restore();if(e.hp<e.maxHp){ctx.fillStyle='#ecb39a';ctx.fillRect(e.x-e.r,e.y-e.r-8,e.r*2*e.hp/e.maxHp,2);}}
    const p=game.player;let angle=-Math.PI/2;if(game.enemies.length){let target=game.enemies.reduce((a,b)=>Math.hypot(a.x-p.x,a.y-p.y)<Math.hypot(b.x-p.x,b.y-p.y)?a:b);angle=Math.atan2(target.y-p.y,target.x-p.x);}ship(p.x,p.y,angle);
  }
  for(const r of rings){r.r+=dt*r.max*2;r.t-=dt*1.5;ctx.globalAlpha=Math.max(0,r.t);circle(r.x,r.y,r.r,r.color,2);}ctx.globalAlpha=1;rings=rings.filter(r=>r.t>0);
  for(const p of particles){p.x+=p.vx*dt;p.y+=p.vy*dt;p.t-=dt;ctx.globalAlpha=Math.max(0,p.t);ctx.fillStyle=p.color;ctx.fillRect(p.x,p.y,2,2);}ctx.globalAlpha=1;particles=particles.filter(p=>p.t>0);
}
function ship(x,y,a){ctx.save();ctx.translate(x,y);ctx.rotate(a);ctx.fillStyle='#e8f7d8';ctx.shadowColor='#a5f4ce';ctx.shadowBlur=12;ctx.beginPath();ctx.moveTo(13,0);ctx.lineTo(-9,-8);ctx.lineTo(-5,0);ctx.lineTo(-9,8);ctx.closePath();ctx.fill();ctx.shadowBlur=0;ctx.fillStyle='#78dcb7';ctx.fillRect(-15,-2,4+Math.sin(time*30)*2,4);ctx.restore();}
function frame(now){const dt=Math.min((now-last)/1000 || 0,.04);last=now;time+=dt;step(dt);draw(mode==='pause'?0:dt);requestAnimationFrame(frame);}requestAnimationFrame(frame);
// Test hooks exist only in a browser QA session, never in the Android file URL.
if(location.search==='?qa')window.echoQA={state:()=>({mode,...game}),step,spawn,pulse,finish,set:values=>Object.assign(game,values),clear:()=>{game.enemies=[];game.spawned=5+game.wave*2;game.bossSpawned=true;},home};
})();
