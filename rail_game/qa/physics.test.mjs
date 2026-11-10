import test from 'node:test';import assert from 'node:assert/strict';
import {createState,step,setRoute,canSwitch,targetPosition,limitAt} from '../src/simulation.js';
const lengths={north:{split:50,merge:150,total:400,station:100},south:{split:50,merge:170,total:420,station:110}};
function state(){const s=createState(lengths);s.mode='play';return s;}
function advance(s,seconds){for(let i=0;i<seconds*10;i++)step(s,.1);}
test('diesel accelerates with inertia and coasts without reversing',()=>{const s=state();s.throttle=100;advance(s,5);assert(s.speed>4&&s.speed<7);s.throttle=0;const before=s.speed;advance(s,1);assert(s.speed>0&&s.speed<before);s.brake=100;advance(s,5);assert.equal(s.speed,0);});
test('turnout locks under front and trailing cars',()=>{const s=state();s.position=42;assert.equal(canSwitch(s),false);assert.equal(setRoute(s,'south'),false);s.position=174;assert.equal(canSwitch(s),false);s.position=190;assert.equal(setRoute(s,'south'),true);assert.equal(s.position,210);});
test('route must match destination',()=>{const s=state();setRoute(s,'south');assert.equal(targetPosition(s),null);s.position=110;advance(s,4);assert.equal(s.delivered,0);});
test('station requires low speed and full dwell, then brakes',()=>{const s=state();s.position=100;s.speed=2;step(s,.1);assert.equal(s.dwell,0);s.speed=0;advance(s,2);assert.equal(s.delivered,0);advance(s,1.2);assert.equal(s.delivered,1);assert.equal(s.brake,100);assert(s.score>500);});
test('overspeed reduces safety and can stop the shift',()=>{const s=state();s.position=75;s.speed=20;s.safety=.01;step(s,.1);assert.equal(s.mode,'lost');});
test('pause freezes physics and deadline',()=>{const s=state();s.mode='pause';s.speed=15;const old=structuredClone(s);advance(s,4);assert.deepEqual(s,old);});
test('loop wrap preserves positive position',()=>{const s=state();s.position=399.9;s.speed=10;step(s,.1);assert(s.position>=0&&s.position<2);});
test('six station deliveries end the shift',()=>{const s=state();for(let i=0;i<6;i++){s.route=[0,3].includes(i)?'north':[1,4].includes(i)?'south':'north';s.position=targetPosition(s);s.speed=0;advance(s,3.2);}assert.equal(s.delivered,6);assert.equal(s.mode,'won');});
test('free drive has no contracts or deadline',()=>{const s=state();s.free=true;s.position=100;advance(s,4);assert.equal(s.delivered,0);assert.equal(s.deadline,180);});
test('branch and common track have different speed limits',()=>{const s=state();assert.equal(limitAt(s),64);s.position=70;assert.equal(limitAt(s),36);});
