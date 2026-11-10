export const contracts = [
 {station:'summit',name:'Нагорная',cargo:'42 пассажира',description:'Доставь пассажиров на горную станцию',route:'north'},
 {station:'port',name:'Речной порт',cargo:'Контейнеры · 36 т',description:'Груз ждут у речного терминала',route:'south'},
 {station:'depot',name:'Сосновая',cargo:'Смена локомотивной бригады',description:'Верни состав на исходную станцию',route:null},
 {station:'summit',name:'Нагорная',cargo:'Экспресс · 64 пассажира',description:'Второй рейс в горную долину',route:'north'},
 {station:'port',name:'Речной порт',cargo:'Почта и оборудование',description:'Доставь груз к вечерней разгрузке',route:'south'},
 {station:'depot',name:'Сосновая',cargo:'Последний рейс',description:'Заверши смену на станции Сосновая',route:null}
];
export function createState(lengths){return {mode:'menu',route:'north',position:21,speed:0,throttle:0,brake:0,delivered:0,score:0,safety:100,elapsed:0,deadline:180,dwell:0,overspeed:0,missed:0,lastStop:null,lengths};}
export function limitAt(s){const l=s.lengths[s.route];return s.position>l.split-15&&s.position<l.merge+10?36:64;}
export function canSwitch(s){const l=s.lengths[s.route];return s.position<l.split-12||s.position>l.merge+32;}
export function setRoute(s,route){if(!['north','south'].includes(route)||!canSwitch(s))return false;const old=s.lengths[s.route],next=s.lengths[route];if(s.position>old.merge)s.position+=next.merge-old.merge;s.route=route;return true;}
export function targetPosition(s){if(s.free)return null;const c=contracts[s.delivered];if(!c)return null;if(c.station==='depot')return 21;return c.route===s.route?s.lengths[s.route].station:null;}
export function targetDistance(s){const p=targetPosition(s);return p===null?null:(p-s.position+s.lengths[s.route].total)%s.lengths[s.route].total;}
export function step(s,dt){
 if(s.mode!=='play')return [];
 dt=Math.max(0,Math.min(dt,.1));const events=[];s.elapsed+=dt;if(!s.free)s.deadline-=dt;
 const acceleration=s.throttle*.012-s.brake*.038-.095-s.speed*.008;
 s.speed=Math.max(0,Math.min(23,s.speed+acceleration*dt));s.position=(s.position+s.speed*dt)%s.lengths[s.route].total;
 const kmh=s.speed*3.6,limit=limitAt(s);s.overspeed=kmh>limit+3?kmh-limit:0;
 if(s.overspeed>0)s.safety=Math.max(0,s.safety-dt*s.overspeed*.11);
 if(s.deadline<0)s.safety=Math.max(0,s.safety-dt*.5);
 const target=targetPosition(s),length=s.lengths[s.route].total;
 const distance=target===null?Infinity:Math.min(Math.abs(s.position-target),length-Math.abs(s.position-target));
 if(distance<7&&s.speed<.28){s.dwell+=dt;if(s.dwell>=3){const bonus=Math.max(0,Math.round(s.deadline));s.score+=500+bonus+Math.round(s.safety)*2;s.delivered++;s.dwell=0;s.deadline=180;s.safety=Math.min(100,s.safety+12);s.throttle=0;s.brake=100;events.push('delivery');if(s.delivered===contracts.length){s.mode='won';events.push('won');}}}else s.dwell=0;
 if(s.safety<=0){s.mode='lost';s.throttle=0;s.brake=100;events.push('lost');}
 return events;
}
