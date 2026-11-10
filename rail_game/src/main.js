import * as T from 'three';
import {OrbitControls} from 'three/examples/jsm/controls/OrbitControls.js';
import {scene,lengths,sample,placeTrain,animateWorld,setLighting,signalState,createCab,cars,sun} from './world.js';
import {contracts,createState,step,setRoute,canSwitch,limitAt,targetDistance,targetPosition} from './simulation.js';
const $=id=>document.getElementById(id),V=(x,y,z)=>new T.Vector3(x,y,z);
let renderer;
try{renderer=new T.WebGLRenderer({antialias:true,powerPreference:'high-performance'});}catch(e){$('loading').innerHTML='<span>Нужен WebGL</span><p>Обнови Android System WebView и снова открой игру.</p>';throw e;}
renderer.setPixelRatio(Math.min(devicePixelRatio,1.5));renderer.setSize(innerWidth,innerHeight);renderer.shadowMap.enabled=true;renderer.shadowMap.type=T.PCFSoftShadowMap;renderer.outputColorSpace=T.SRGBColorSpace;renderer.toneMapping=T.ACESFilmicToneMapping;renderer.toneMappingExposure=1.05;$('world').append(renderer.domElement);
const camera=new T.PerspectiveCamera(48,innerWidth/innerHeight,.1,700);scene.add(camera);const cab=createCab();camera.add(cab);cab.visible=false;
const controls=new OrbitControls(camera,renderer.domElement);controls.enableDamping=true;controls.dampingFactor=.09;controls.minDistance=9;controls.maxDistance=200;controls.maxPolarAngle=Math.PI*.47;controls.minPolarAngle=.16;controls.enablePan=false;
let s=createState(lengths),cameraMode='follow',last=0,time=0,menuPosition=21,dusk=false,quality=true,free=false,best=0,toastUntil=0,lastUI=0;
let sound=false,audio,engineGain,engineOsc;try{best=Number(localStorage.getItem('peregon-best'))||0;}catch(_){}
function soundInit(){if(audio)return;try{audio=new AudioContext();engineOsc=audio.createOscillator();engineOsc.type='sawtooth';engineGain=audio.createGain();engineGain.gain.value=0;const filter=audio.createBiquadFilter();filter.type='lowpass';filter.frequency.value=180;engineOsc.connect(filter);filter.connect(engineGain);engineGain.connect(audio.destination);engineOsc.start();}catch(_){}}
function horn(){if(!sound)return;soundInit();if(!audio)return;audio.resume();for(const f of [195,245,292]){const o=audio.createOscillator(),g=audio.createGain();o.type='triangle';o.frequency.value=f;g.gain.setValueAtTime(.001,audio.currentTime);g.gain.exponentialRampToValueAtTime(.055,audio.currentTime+.08);g.gain.exponentialRampToValueAtTime(.001,audio.currentTime+1.4);o.connect(g);g.connect(audio.destination);o.start();o.stop(audio.currentTime+1.5);}}
$('sound').onclick=()=>{sound=!sound;$('sound').textContent=sound?'Звук вкл.':'Звук выкл.';if(sound){soundInit();audio?.resume();horn();}else if(engineGain)engineGain.gain.value=0;};$('horn').onclick=horn;
$('light').onclick=()=>{dusk=!dusk;setLighting(dusk);$('light').textContent=dusk?'Утро':'Закат';};
function setQuality(high){quality=high;renderer.shadowMap.enabled=high;renderer.setPixelRatio(Math.min(devicePixelRatio,high?1.5:1));$('quality').textContent=high?'Графика: высокая':'Графика: лёгкая';scene.traverse(o=>{if(o.material){const a=Array.isArray(o.material)?o.material:[o.material];a.forEach(m=>m.needsUpdate=true);}});}
$('quality').onclick=()=>setQuality(!quality);
function toast(text){$('toast').textContent=text;$('toast').hidden=false;toastUntil=time+4;}
function modal(title,text,label,button,action,home=true){$('modal').hidden=false;$('modal-title').textContent=title;$('modal-text').textContent=text;$('modal-label').textContent=label;$('modal-primary').textContent=button;$('modal-primary').onclick=action;$('modal-home').hidden=!home;}
function hideModal(){$('modal').hidden=true;last=performance.now();}
function cameraTo(mode){cameraMode=mode;cab.visible=mode==='cab';cars[0].visible=mode!=='cab';controls.enabled=mode!=='cab';for(const key of ['follow','cab','map'])$('cam-'+key).classList.toggle('active',mode===key);const {p,t}=sample(s.route,s.position),side=V(t.z,0,-t.x);if(mode==='map'){controls.target.set(0,0,12);camera.position.set(104,118,135);}else if(mode==='follow'){controls.target.copy(p).addScaledVector(t,-8);camera.position.copy(p).addScaledVector(t,17).addScaledVector(side,26).add(V(0,12,0));}controls.update();}
for(const mode of ['follow','cab','map'])$('cam-'+mode).onclick=()=>cameraTo(mode);
function start(isFree=false){s=createState(lengths);s.mode='play';s.free=isFree;free=isFree;menuPosition=21;$('menu').hidden=true;$('dashboard').hidden=false;$('pause').hidden=false;$('power').value=0;cameraTo('follow');hideModal();updateUI();horn();}
function briefing(isFree){s.mode='briefing';modal('Почувствуй вес состава.','Добавь тягу ползунком. Перед платформой убери тягу и нажми «Тормоз». Для разгрузки остановись в пределах 7 метров от знака «СТОП» на 3 секунды.\n\nСтрелка выбирает Нагорную или Порт. Переводи её заранее: под составом она блокируется. На ветках лимит 36 км/ч. Камеру можно вращать пальцем и приближать щипком.','ИНСТРУКТАЖ МАШИНИСТА','В путь ↗',()=>start(isFree));}
$('start').onclick=()=>briefing(false);$('free').onclick=()=>briefing(true);
function home(){s=createState(lengths);free=false;$('menu').hidden=false;$('dashboard').hidden=true;$('pause').hidden=true;$('toast').hidden=true;hideModal();cameraTo('follow');}
$('modal-home').onclick=home;
function pause(){if(s.mode!=='play')return;s.mode='pause';modal('Поезд подождёт.','Смена приостановлена. Тяга, скорость и положение состава сохранены.','ПАУЗА','Продолжить',()=>{s.mode='play';hideModal();});if(engineGain)engineGain.gain.value=0;}
$('pause').onclick=pause;window.railPause=pause;window.railBack=()=>{if(s.mode==='play')pause();else if(s.mode==='pause'){s.mode='play';hideModal();}else if(s.mode==='briefing')home();};
addEventListener('visibilitychange',()=>{if(document.hidden)pause();});addEventListener('blur',pause);
$('power').oninput=()=>{if(s.mode!=='play')return;s.throttle=Number($('power').value);s.brake=0;updateUI();};
$('coast').onclick=()=>{if(s.mode!=='play')return;s.throttle=0;s.brake=0;updateUI();};
$('brake').onclick=()=>{if(s.mode!=='play')return;s.throttle=0;s.brake=s.brake===65?0:65;updateUI();};
$('emergency').onclick=()=>{if(s.mode!=='play')return;s.throttle=0;s.brake=100;toast('Экстренное торможение');updateUI();};
function route(value){if(s.mode!=='play')return;if(setRoute(s,value)){toast(value==='north'?'Стрелка: на Нагорную':'Стрелка: в Речной порт');updateUI();}else toast('Стрелка занята составом');}
$('north').onclick=()=>route('north');$('south').onclick=()=>route('south');
addEventListener('keydown',e=>{if(['ArrowUp','ArrowDown',' '].includes(e.key))e.preventDefault();if(e.key==='Escape')window.railBack();if(s.mode!=='play')return;if(e.key==='ArrowUp'){s.throttle=Math.min(100,s.throttle+10);s.brake=0;}if(e.key==='ArrowDown'){s.throttle=Math.max(0,s.throttle-10);}if(e.key===' ')$('brake').click();if(e.key.toLowerCase()==='h')horn();updateUI();});
function updateUI(){
 const c=contracts[Math.min(s.delivered,5)],locked=!canSwitch(s),dist=targetDistance(s),speed=Math.round(s.speed*3.6);
 $('speed').textContent=speed;$('speed').style.color=s.overspeed>0?'#ff9b73':'';$('power-value').textContent=s.throttle+'%';$('power').value=s.throttle;$('limit').textContent=limitAt(s);$('safety').style.width=s.safety+'%';$('safety').style.background=s.safety<35?'#e68a69':'#acd39b';
 $('brake').classList.toggle('active',s.brake===65);$('emergency').classList.toggle('active',s.brake===100);$('coast').classList.toggle('active',s.brake===0&&s.throttle===0);
 $('destination').textContent=free?'Свободный рейс':c.name;$('cargo').textContent=free?'Исследуй долину в своём темпе':c.cargo;$('distance').textContent=free?'∞':dist===null?'↔':Math.round(Math.min(dist,lengths[s.route].total-dist)<7?0:dist);$('distance-unit').textContent=free?'без расписания':dist===null?'нужна другая ветка':'м до платформы';
 $('instruction').textContent=s.dwell>0?'Разгрузка… '+Math.ceil(3-s.dwell)+' с':s.overspeed>0?'Превышение! Снизь скорость':dist!==null&&dist<45&&s.speed>2?'Сбрось тягу и тормози у платформы':'Остановка у знака «СТОП» · 3 секунды';$('dwell').style.width=(s.dwell/3*100)+'%';
 $('progress').textContent=free?'СВОБОДНЫЙ РЕЙС':'РЕЙС '+Math.min(6,s.delivered+1)+' / 6';$('station-label').textContent=free?'ДОЛИНА СОСЕН · КОЛЬЦЕВОЙ МАРШРУТ':'ЗАДАНИЕ → '+c.name.toUpperCase();
 const sec=Math.max(0,Math.ceil(s.deadline));$('timer').textContent=free?'∞':Math.floor(sec/60)+':'+String(sec%60).padStart(2,'0');const minutes=360+Math.floor(s.elapsed*.4);$('clock').textContent=String(Math.floor(minutes/60)%24).padStart(2,'0')+':'+String(minutes%60).padStart(2,'0');
 $('lock').textContent=locked?'ЗАБЛОКИРОВАНА':'ДОСТУПНА';for(const r of ['north','south']){$(r).disabled=locked;$(r).classList.toggle('active',s.route===r);}
 $('route-line').setAttribute('d',s.route==='north'?'M5 29H60Q80 29 96 9H195':'M5 29H60Q80 29 96 49H195');$('route-note').textContent=s.route==='north'?'Горная ветка · через мост':'Южная ветка · грузовой терминал';signalState(locked);
}
function end(won){best=Math.max(best,s.score);try{localStorage.setItem('peregon-best',String(best));}catch(_){}modal(won?'Хорошая смена, машинист.':'Состав требует проверки.',won?'Все шесть рейсов выполнены. Долина благодарит за работу.\nОчки: '+s.score+' · Лучший результат: '+best:'Запас безопасности исчерпан. Держи скорость ниже ограничения и следи за расписанием.\nВыполнено рейсов: '+s.delivered+' / 6',won?'СМЕНА ЗАВЕРШЕНА':'СМЕНА ОСТАНОВЛЕНА','Новая смена',()=>start(false));}
renderer.domElement.addEventListener('webglcontextlost',e=>{e.preventDefault();pause();$('loading').innerHTML='<span>Графика приостановлена</span><p>Переоткрой игру, затем выбери лёгкую графику.</p>';$('loading').hidden=false;});
addEventListener('resize',()=>{camera.aspect=innerWidth/innerHeight;camera.updateProjectionMatrix();renderer.setSize(innerWidth,innerHeight);});
let previousTarget=new T.Vector3();
function frame(now){requestAnimationFrame(frame);const dt=Math.min(.05,(now-last)/1000||0);last=now;time+=dt;
 if(s.mode==='play'){const old=sample(s.route,s.position).p;const events=step(s,dt);for(const event of events){if(event==='delivery'){toast('Рейс выполнен · +очки. Следующее задание в путевом листе');horn();}if(event==='won')end(true);if(event==='lost')end(false);}const current=sample(s.route,s.position).p;if(cameraMode==='follow'){const delta=current.clone().sub(old);camera.position.add(delta);controls.target.add(delta);}}
 if(s.mode==='menu'||s.mode==='briefing'){menuPosition=(menuPosition+dt*1.2)%lengths.north.total;placeTrain('north',menuPosition);const {p,t}=sample('north',menuPosition),side=V(t.z,0,-t.x);controls.target.copy(p).addScaledVector(t,-6);camera.position.copy(p).addScaledVector(t,18).addScaledVector(side,32).add(V(0,15,0));controls.enabled=false;}
 else {placeTrain(s.route,s.position);if(cameraMode==='cab'){const {p,t}=sample(s.route,s.position);camera.position.copy(p).addScaledVector(t,2.5).add(V(0,3.35,0));camera.lookAt(p.clone().addScaledVector(t,35).add(V(0,3.0,0)));}else controls.enabled=true;}
 if(cameraMode!=='cab'||s.mode==='menu')controls.update();
 animateWorld(time,s.mode==='menu'?'north':s.route,s.mode==='menu'?menuPosition:s.position,s.speed,s.throttle);
 if(engineGain&&sound){engineGain.gain.setTargetAtTime(s.mode==='play'?.009+s.throttle*.00013:0,audio.currentTime,.1);engineOsc.frequency.setTargetAtTime(32+s.speed*2+s.throttle*.15,audio.currentTime,.1);}
 if(time-lastUI>.12){updateUI();lastUI=time;}if(time>toastUntil)$('toast').hidden=true;
 renderer.render(scene,camera);window.__railRendered=true;
}
placeTrain('north',21);cameraTo('follow');$('loading').hidden=true;requestAnimationFrame(frame);
if(location.search==='?qa')window.railQA={state:()=>s,set:values=>Object.assign(s,values),tick:dt=>{const events=step(s,dt);updateUI();return events;},start,route,canSwitch:()=>canSwitch(s),lengths,sample:(r,p)=>{const q=sample(r,p);return {p:q.p.toArray(),t:q.t.toArray()};},camera:cameraTo,renderInfo:()=>({calls:renderer.info.render.calls,triangles:renderer.info.render.triangles}),station:()=>targetPosition(s),pause};
