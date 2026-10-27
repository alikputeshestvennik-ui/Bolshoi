/* Кабина Пилота: низкополигональный 3D-полёт (three.js r149).
 * Управление из приложения: setFlying(bool), setWeather(cloudPct, rain, day), setDrift(vx, vz), setGlow("#rrggbb").
 * Обратно: PilotBridge.ready(), onLift() — отрыв, onTouch() — касание полосы, fail() — нет WebGL. */
(function () {
  "use strict";
  var B = window.PilotBridge || { ready: function () {}, onLift: function () {}, onTouch: function () {}, fail: function () {}, diag: function () {} };
  function call(fn) { try { B[fn](); } catch (e) {} }
  function diag(m) { try { B.diag(String(m)); } catch (e) {} }
  window.addEventListener("error", function (e) { diag("js: " + (e.message || e) + " @" + (e.lineno || "?")); });

  var renderer;
  try {
    renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: "low-power" });
    if (!renderer.getContext()) throw new Error("no webgl");
  } catch (e) { diag("webgl: " + e); call("fail"); return; }
  renderer.domElement.addEventListener("webglcontextlost", function (e) { e.preventDefault(); diag("context lost"); call("fail"); }, false);
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.75));
  renderer.setSize(window.innerWidth, window.innerHeight);
  document.body.appendChild(renderer.domElement);

  // ——— случайность с зерном ———
  function rng(seed) { var a = seed >>> 0; return function () { a = (a + 0x6D2B79F5) >>> 0; var t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }
  function flat(geo) { var g = geo.toNonIndexed(); g.computeVertexNormals(); return g; }
  function lerp(a, b, t) { return a + (b - a) * t; }
  function ease(t) { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); }

  // ——— палитры ———
  var PAL = {
    day: { top: 0x2b5f8f, hor: 0xb7d0e2, hemiSky: 0xdfeaf6, hemiGround: 0x4a5a3a, hemiI: 0.9, sun: 0xfff0d2, sunI: 0.95,
      fields: [0x5f8f4e, 0x6f9a52, 0x9aa35e, 0x4f8661, 0xb0a46a, 0x7da55a, 0x8a9a54, 0xa8915e], base: 0x456f3f,
      tree: 0x2f5a34, house: 0xe2d8c6, roof: 0xa04e3c, road: 0x5a5e63, grass: 0x6f9658, apron: 0x8d939a, cloud: 0xf6f8fb, cloudO: 0.96, star: 0 },
    night: { top: 0x050a14, hor: 0x1b3346, hemiSky: 0x7f97c4, hemiGround: 0x16241c, hemiI: 0.85, sun: 0xa9c1ef, sunI: 0.4,
      fields: [0x2d4f4b, 0x355a4a, 0x2a4758, 0x3a5c47, 0x30525e, 0x405f52], base: 0x203a38,
      tree: 0x132a20, house: 0x46536a, roof: 0x2c3240, road: 0x10171f, grass: 0x23402f, apron: 0x2a313b, cloud: 0x8193b2, cloudO: 0.88, star: 1 }
  };
  var wx = { cloud: 35, rain: false, day: true };
  var P = PAL.day;
  var glow = new THREE.Color(0x3e6d92);

  // ——— сцена, камера, свет ———
  var scene = new THREE.Scene();
  var cam = new THREE.PerspectiveCamera(60, window.innerWidth / window.innerHeight, 0.3, 6000);
  cam.rotation.order = "YXZ";
  scene.fog = new THREE.Fog(0x000000, 300, 2700);
  var hemi = new THREE.HemisphereLight(0xffffff, 0x444444, 0.9); scene.add(hemi);
  var sun = new THREE.DirectionalLight(0xffffff, 0.9); scene.add(sun); scene.add(sun.target);

  // небо — купол с градиентом, следует за камерой
  var skyMat = new THREE.ShaderMaterial({
    side: THREE.BackSide, depthWrite: false, fog: false,
    uniforms: { top: { value: new THREE.Color() }, hor: { value: new THREE.Color() }, glow: { value: new THREE.Color() } },
    vertexShader: "varying vec3 vP; void main(){ vP = normalize(position); gl_Position = projectionMatrix*modelViewMatrix*vec4(position,1.0); }",
    fragmentShader: "uniform vec3 top; uniform vec3 hor; uniform vec3 glow; varying vec3 vP;" +
      "void main(){ float h = vP.y; vec3 c = mix(hor, top, smoothstep(0.0, 0.45, h)); c = mix(c, glow, 0.45*exp(-abs(h)*14.0)); if(h<0.0) c = mix(hor, glow, 0.2); gl_FragColor = vec4(c,1.0); }"
  });
  var sky = new THREE.Mesh(new THREE.SphereGeometry(4000, 32, 16), skyMat); sky.renderOrder = -1; scene.add(sky);

  // звёзды
  var starGeo = new THREE.BufferGeometry(), sp = [], sr = rng(7);
  for (var i = 0; i < 600; i++) { var th = sr() * Math.PI * 2, ph = 0.04 + sr() * 1.3; sp.push(Math.cos(th) * Math.cos(ph) * 3800, Math.sin(ph) * 3800, Math.sin(th) * Math.cos(ph) * 3800); }
  starGeo.setAttribute("position", new THREE.Float32BufferAttribute(sp, 3));
  var starMat = new THREE.PointsMaterial({ color: 0xffffff, size: 2.2, sizeAttenuation: false, transparent: true, opacity: 0, fog: false, depthWrite: false });
  var stars = new THREE.Points(starGeo, starMat); scene.add(stars);

  // солнце / луна — мягкий диск
  function discTex() {
    var c = document.createElement("canvas"); c.width = c.height = 128; var g = c.getContext("2d");
    var gr = g.createRadialGradient(64, 64, 0, 64, 64, 64);
    gr.addColorStop(0, "rgba(255,255,255,1)"); gr.addColorStop(0.28, "rgba(255,255,255,0.95)"); gr.addColorStop(0.36, "rgba(255,255,255,0.35)"); gr.addColorStop(1, "rgba(255,255,255,0)");
    g.fillStyle = gr; g.fillRect(0, 0, 128, 128); return new THREE.CanvasTexture(c);
  }
  var orbMat = new THREE.SpriteMaterial({ map: discTex(), color: 0xfff1d0, fog: false, depthWrite: false, transparent: true });
  var orb = new THREE.Sprite(orbMat); orb.scale.set(420, 420, 1); scene.add(orb);
  var SUN_DIR = new THREE.Vector3(-0.45, 0.22, -1).normalize();

  // ——— общие материалы и геометрии ———
  var fieldMat = new THREE.MeshLambertMaterial({ vertexColors: true });
  var baseMat = new THREE.MeshLambertMaterial({ color: 0x456f3f });
  var treeGeo = flat(new THREE.ConeGeometry(4, 13, 6)); treeGeo.translate(0, 6.5, 0);
  var treeMat = new THREE.MeshLambertMaterial({ color: 0xffffff });
  var houseGeo = flat(new THREE.BoxGeometry(10, 7, 12)); houseGeo.translate(0, 3.5, 0);
  var houseMat = new THREE.MeshLambertMaterial({ color: 0xffffff });
  var roofGeo = flat(new THREE.ConeGeometry(9, 5, 4)); roofGeo.rotateY(Math.PI / 4); roofGeo.scale(1, 1, 1.25); roofGeo.translate(0, 9.5, 0);
  var roofMat = new THREE.MeshLambertMaterial({ color: 0xffffff });
  var DOT = discTex();
  var winMat = new THREE.PointsMaterial({ color: 0xffd08a, map: DOT, size: 7, sizeAttenuation: true, transparent: true, opacity: 0, blending: THREE.AdditiveBlending, depthWrite: false });
  var dummy = new THREE.Object3D();
  var tmpC = new THREE.Color();

  // ——— аэропорт (переставляется при посадке) ———
  var RW_LEN = 900, RW_W = 45;
  var airport = new THREE.Group(); scene.add(airport);
  var airZ = 120; // порог полосы (мир); полоса уходит в -Z
  function runwayTex() {
    var c = document.createElement("canvas"); c.width = 128; c.height = 2048; var g = c.getContext("2d");
    var pxm = 2048 / RW_LEN;
    g.fillStyle = "#3d444d"; g.fillRect(0, 0, 128, 2048);
    for (var n = 0; n < 900; n++) { g.fillStyle = "rgba(255,255,255," + (0.02 + Math.random() * 0.03) + ")"; g.fillRect(Math.random() * 128, Math.random() * 2048, 2, 2); }
    g.fillStyle = "#eef2f6";
    g.fillRect(6, 0, 3, 2048); g.fillRect(119, 0, 3, 2048);
    for (var z = 60; z < RW_LEN - 60; z += 50) g.fillRect(62, (z) * pxm, 4, 30 * pxm);
    function keys(z0) { for (var k = 0; k < 8; k++) { var x = 14 + k * 13 + (k >= 4 ? 8 : 0); g.fillRect(x, z0 * pxm, 7, 30 * pxm); } }
    keys(6); keys(RW_LEN - 36);
    function tdz(z0) { g.fillRect(24, z0 * pxm, 14, 22 * pxm); g.fillRect(90, z0 * pxm, 14, 22 * pxm); }
    tdz(150); tdz(RW_LEN - 172); tdz(300); tdz(RW_LEN - 322);
    var t = new THREE.CanvasTexture(c); t.anisotropy = renderer.capabilities.getMaxAnisotropy(); return t;
  }
  var grassMat = new THREE.MeshLambertMaterial({ color: 0x6f9658 });
  var apronMat = new THREE.MeshLambertMaterial({ color: 0x8d939a });
  var rwMat = new THREE.MeshLambertMaterial({ map: runwayTex() });
  var bldMat = new THREE.MeshLambertMaterial({ color: 0xc9d3dc });
  var glassMat = new THREE.MeshLambertMaterial({ color: 0x2b4a63, emissive: 0x000000 });
  var hangarMat = new THREE.MeshLambertMaterial({ color: 0x9aa6b2 });
  (function buildAirport() {
    function plane(w, l, mat, x, z, y) { var m = new THREE.Mesh(new THREE.PlaneGeometry(w, l), mat); m.rotation.x = -Math.PI / 2; m.position.set(x, y, z); airport.add(m); return m; }
    plane(560, RW_LEN + 700, grassMat, 60, -RW_LEN / 2 + 50, 0.15);
    plane(RW_W, RW_LEN, rwMat, 0, -RW_LEN / 2, 0.3);
    plane(12, 520, apronMat, 70, -RW_LEN / 2, 0.25);               // рулёжка
    plane(150, 260, apronMat, 160, -330, 0.25);                     // перрон
    var term = new THREE.Mesh(flat(new THREE.BoxGeometry(34, 16, 170)), bldMat); term.position.set(250, 8, -330); airport.add(term);
    var band = new THREE.Mesh(new THREE.BoxGeometry(35, 5, 160), glassMat); band.position.set(250, 9, -330); airport.add(band);
    var tower = new THREE.Mesh(flat(new THREE.CylinderGeometry(3.5, 4.5, 42, 8)), bldMat); tower.position.set(120, 21, -150); airport.add(tower);
    var cab = new THREE.Mesh(flat(new THREE.CylinderGeometry(9, 7, 8, 8)), glassMat); cab.position.set(120, 46, -150); airport.add(cab);
    for (var h = 0; h < 2; h++) {
      var hg = new THREE.Mesh(flat(new THREE.CylinderGeometry(20, 20, 46, 10, 1, false, 0, Math.PI)), hangarMat);
      hg.rotation.z = Math.PI / 2; hg.rotation.y = Math.PI / 2; hg.position.set(215 + h * 52, 0, -560); airport.add(hg);
    }
  })();
  // огни полосы: кромки (тёплые), порог (зелёные), торец (красные), огни подхода (белые)
  var lightPos = { warm: [], green: [], red: [], white: [] };
  for (var z = 0; z <= RW_LEN; z += 30) { lightPos.warm.push(-RW_W / 2 - 1, -z); lightPos.warm.push(RW_W / 2 + 1, -z); }
  for (var x = -21; x <= 21; x += 3.5) { lightPos.green.push(x, 1.5); lightPos.red.push(x, -RW_LEN - 1.5); }
  for (var a = 30; a <= 330; a += 30) lightPos.white.push(0, a);
  for (var xb = -18; xb <= 18; xb += 4.5) lightPos.white.push(xb, 150);
  var lampGeo = new THREE.SphereGeometry(0.75, 6, 4);
  var halos = [];
  function lamps(arr, color) {
    var n = arr.length / 2, mesh = new THREE.InstancedMesh(lampGeo, new THREE.MeshBasicMaterial({ color: color }), n), pts = [];
    for (var i = 0; i < n; i++) { dummy.position.set(arr[i * 2], 0.9, arr[i * 2 + 1]); dummy.scale.setScalar(1); dummy.rotation.set(0, 0, 0); dummy.updateMatrix(); mesh.setMatrixAt(i, dummy.matrix); pts.push(arr[i * 2], 1.2, arr[i * 2 + 1]); }
    airport.add(mesh);
    var g = new THREE.BufferGeometry(); g.setAttribute("position", new THREE.Float32BufferAttribute(pts, 3));
    var hm = new THREE.PointsMaterial({ color: color, map: DOT, size: 10, sizeAttenuation: true, transparent: true, opacity: 0, blending: THREE.AdditiveBlending, depthWrite: false });
    var halo = new THREE.Points(g, hm); airport.add(halo); halos.push(hm);
  }
  lamps(lightPos.warm, 0xffe2a0); lamps(lightPos.green, 0x6dff9a); lamps(lightPos.red, 0xff5a4a); lamps(lightPos.white, 0xffffff);
  function placeAirport(z) { airZ = z; airport.position.z = z; refillOverlapping(); }

  function inAirport(x, z) { return x > -300 && x < 340 && z < airZ + 360 && z > airZ - RW_LEN - 300; }

  // ——— земля: куски по 400 м вперёд, переиспользуются ———
  var CZ = 400, NCH = 8, HALFW = 800, CELL = 50;
  var ROAD_X = [-330, 420];
  var chunks = [];
  function makeChunk() {
    var g = new THREE.Group();
    var base = new THREE.Mesh(new THREE.PlaneGeometry(HALFW * 2, CZ), baseMat); base.rotation.x = -Math.PI / 2; g.add(base);
    var fg = new THREE.BufferGeometry(); var fields = new THREE.Mesh(fg, fieldMat); g.add(fields);
    var trees = new THREE.InstancedMesh(treeGeo, treeMat, 260); g.add(trees);
    var houses = new THREE.InstancedMesh(houseGeo, houseMat, 60); g.add(houses);
    var roofs = new THREE.InstancedMesh(roofGeo, roofMat, 60); g.add(roofs);
    var wg = new THREE.BufferGeometry(); var wins = new THREE.Points(wg, winMat); g.add(wins);
    scene.add(g);
    return { g: g, fg: fg, trees: trees, houses: houses, roofs: roofs, wg: wg, k: null };
  }
  function fill(ch, k) {
    ch.k = k;
    var zc = -(k + 0.5) * CZ; ch.g.position.z = zc;
    var r = rng(k * 7919 + 13);
    // поля с межами + дороги — одна геометрия с цветами вершин
    var pos = [], col = [];
    function quad(x1, x2, z1, z2, y, c) {
      tmpC.setHex(c);
      pos.push(x1, y, z1, x2, y, z1, x2, y, z2, x1, y, z1, x2, y, z2, x1, y, z2);
      for (var v = 0; v < 6; v++) col.push(tmpC.r, tmpC.g, tmpC.b);
    }
    var gap = 2.2;
    for (var cx = -HALFW; cx < HALFW; cx += CELL) for (var cz = -CZ / 2; cz < CZ / 2; cz += CELL) {
      var c = P.fields[Math.floor(r() * P.fields.length)];
      quad(cx + gap, cx + CELL - gap, cz + CELL - gap, cz + gap, 0.05, c);
    }
    var crossZ = -CZ / 2 + 40 + r() * (CZ - 80);
    quad(-HALFW, HALFW, crossZ + 5, crossZ - 5, 0.09, P.road);
    for (var ri = 0; ri < ROAD_X.length; ri++) quad(ROAD_X[ri] - 5, ROAD_X[ri] + 5, CZ / 2, -CZ / 2, 0.1, P.road);
    ch.fg.setAttribute("position", new THREE.Float32BufferAttribute(pos, 3));
    ch.fg.setAttribute("color", new THREE.Float32BufferAttribute(col, 3));
    ch.fg.computeVertexNormals();
    ch.fg.computeBoundingSphere();
    // лесополосы и рощи
    var nt = 0;
    function tree(x, z) {
      if (nt >= 260 || inAirport(x, zc + z) || Math.abs(x - ROAD_X[0]) < 9 || Math.abs(x - ROAD_X[1]) < 9 || Math.abs(z - crossZ) < 8) return;
      var s = 0.7 + r() * 0.8;
      dummy.position.set(x, 0, z); dummy.rotation.set(0, r() * 6.28, 0); dummy.scale.set(s, s * (0.8 + r() * 0.5), s); dummy.updateMatrix();
      ch.trees.setMatrixAt(nt, dummy.matrix);
      tmpC.setHex(P.tree).offsetHSL((r() - 0.5) * 0.04, 0, (r() - 0.5) * 0.08); ch.trees.setColorAt(nt, tmpC); nt++;
    }
    for (var p = 0; p < 3; p++) { var px = -HALFW + 60 + r() * (HALFW * 2 - 120), pz = -CZ / 2 + 30 + r() * (CZ - 60); for (var t = 0; t < 55; t++) tree(px + (r() - 0.5) * 120, pz + (r() - 0.5) * 90); }
    var lineZ = -CZ / 2 + r() * CZ; for (var lx = -HALFW; lx < HALFW; lx += 9) if (r() < 0.75) tree(lx, lineZ + (r() - 0.5) * 3);
    ch.trees.count = nt; ch.trees.instanceMatrix.needsUpdate = true; if (ch.trees.instanceColor) ch.trees.instanceColor.needsUpdate = true;
    // посёлки
    var nh = 0, wp = [];
    for (var vi = 0; vi < 2; vi++) {
      var vx = -HALFW + 80 + r() * (HALFW * 2 - 160), vz = -CZ / 2 + 50 + r() * (CZ - 100);
      for (var hh = 0; hh < 14 && nh < 60; hh++) {
        var hx = vx + (hh % 4) * 26 + (r() - 0.5) * 8, hz = vz + Math.floor(hh / 4) * 28 + (r() - 0.5) * 8;
        if (inAirport(hx, zc + hz) || Math.abs(hx - ROAD_X[0]) < 12 || Math.abs(hx - ROAD_X[1]) < 12) continue;
        var hs = 0.8 + r() * 0.5, rot = (r() < 0.5 ? 0 : Math.PI / 2) + (r() - 0.5) * 0.2;
        dummy.position.set(hx, 0, hz); dummy.rotation.set(0, rot, 0); dummy.scale.set(hs, hs * (0.9 + r() * 0.6), hs); dummy.updateMatrix();
        ch.houses.setMatrixAt(nh, dummy.matrix); ch.roofs.setMatrixAt(nh, dummy.matrix);
        tmpC.setHex(P.house).offsetHSL(0, 0, (r() - 0.5) * 0.08); ch.houses.setColorAt(nh, tmpC);
        tmpC.setHex(P.roof).offsetHSL((r() - 0.5) * 0.05, 0, (r() - 0.5) * 0.1); ch.roofs.setColorAt(nh, tmpC);
        if (r() < 0.8) wp.push(hx + (r() - 0.5) * 4, 4 * hs, hz + (r() - 0.5) * 4);
        nh++;
      }
    }
    ch.houses.count = nh; ch.roofs.count = nh;
    ch.houses.instanceMatrix.needsUpdate = true; ch.roofs.instanceMatrix.needsUpdate = true;
    if (ch.houses.instanceColor) ch.houses.instanceColor.needsUpdate = true; if (ch.roofs.instanceColor) ch.roofs.instanceColor.needsUpdate = true;
    ch.wg.setAttribute("position", new THREE.Float32BufferAttribute(wp, 3)); ch.wg.computeBoundingSphere();
  }
  for (var ci = 0; ci < NCH; ci++) chunks.push(makeChunk());
  function updateChunks(force) {
    var first = Math.floor(-camZ / CZ) - 1;
    var need = {}; for (var k = first; k < first + NCH; k++) need[k] = true;
    var free = [];
    for (var i = 0; i < chunks.length; i++) { var c = chunks[i]; if (c.k === null || !need[c.k]) free.push(c); else delete need[c.k]; }
    for (var kk in need) { var ch = free.pop(); if (ch) fill(ch, parseInt(kk, 10)); }
    if (force) for (var j = 0; j < chunks.length; j++) if (chunks[j].k !== null) fill(chunks[j], chunks[j].k);
  }
  function refillOverlapping() {
    for (var i = 0; i < chunks.length; i++) {
      var c = chunks[i]; if (c.k === null) continue;
      var z1 = -(c.k + 1) * CZ, z2 = -c.k * CZ;
      if (z2 > airZ - RW_LEN - 300 && z1 < airZ + 360) fill(c, c.k);
    }
  }

  // ——— облака ———
  var cloudGeo = flat(new THREE.IcosahedronGeometry(1, 1));
  var cloudMat = new THREE.MeshLambertMaterial({ color: 0xffffff, emissive: 0x000000, transparent: true, opacity: 0.95 });
  var clouds = [];
  function buildClouds() {
    for (var i = 0; i < clouds.length; i++) scene.remove(clouds[i]);
    clouds = [];
    var n = Math.round(3 + wx.cloud / 5.5), r = rng(99);
    for (var c = 0; c < n; c++) {
      var g = new THREE.Group(), parts = 4 + Math.floor(r() * 5), big = 0.7 + r() * 0.9;
      for (var p = 0; p < parts; p++) {
        var m = new THREE.Mesh(cloudGeo, cloudMat), s = (18 + r() * 26) * big;
        m.scale.set(s * (1.2 + r() * 0.6), s * (0.55 + r() * 0.3), s * (1 + r() * 0.5));
        m.position.set((p - parts / 2) * 26 * big + (r() - 0.5) * 18, (r() - 0.3) * 10, (r() - 0.5) * 30);
        m.rotation.y = r() * 3; g.add(m);
      }
      g.position.set((r() - 0.5) * 1800, 150 + r() * 60, camZ - 150 - r() * 2500);
      scene.add(g); clouds.push(g);
    }
  }
  var rainGeo = new THREE.BufferGeometry(), rp = [];
  for (var ri2 = 0; ri2 < 500; ri2++) { var rx = (Math.random() - 0.5) * 80, ry = Math.random() * 50, rz = -Math.random() * 80; rp.push(rx, ry, rz, rx - 0.4, ry - 2.2, rz); }
  rainGeo.setAttribute("position", new THREE.Float32BufferAttribute(rp, 3));
  var rain = new THREE.LineSegments(rainGeo, new THREE.LineBasicMaterial({ color: 0xb8cbe0, transparent: true, opacity: 0.35 }));
  rain.visible = false; scene.add(rain);

  // ——— полёт ———
  var EYE = 5.5, CRUISE_ALT = 250;
  var PARK_YAW = -0.6;
  var sim = { yaw: PARK_YAW, st: "PARKED", t: 0, clock: 0, speed: 0, alt: EYE, pitch: 0, roll: 0, flying: false, landQ: false, lifted: false, touched: false, startAlt: CRUISE_ALT, v0: 110 };
  var camZ = 0;
  var drift = { x: 3, z: 2 };
  function step(dt) {
    var s = sim; s.clock += dt; s.t += dt;
    if (s.st === "PARKED") {
      s.speed = 0; s.alt = EYE; s.pitch = 0.004 * Math.sin(s.clock * 0.6); s.roll = 0; s.yaw = PARK_YAW;
      if (s.flying) { s.st = "TAKEOFF"; s.t = 0; s.lifted = false; s.landQ = false; }
    } else if (s.st === "TAKEOFF") {
      if (!s.flying) s.landQ = true;
      s.yaw = PARK_YAW * (1 - ease(s.t / 1.6));
      if (s.t < 3.4) { s.speed = 85 * Math.pow(s.t / 3.4, 1.5); s.alt = EYE; s.pitch = 0; }
      else {
        if (!s.lifted) { s.lifted = true; call("onLift"); }
        var p = Math.min(1, (s.t - 3.4) / 4.4);
        s.speed = lerp(85, 110, p); s.alt = EYE + (CRUISE_ALT - EYE) * ease(p);
        s.pitch = 0.17 * Math.sin(Math.PI * Math.min(1, p * 1.05)) ;
        s.roll = 0.04 * Math.sin(Math.PI * p);
      }
      if (s.t >= 7.8) { s.st = (s.landQ || !s.flying) ? "LANDING" : "CRUISE"; s.t = 0; if (s.st === "LANDING") beginLanding(); }
    } else if (s.st === "CRUISE") {
      s.speed = 110; s.alt = CRUISE_ALT + 6 * Math.sin(s.clock * 0.4); s.pitch = 0.015 * Math.sin(s.clock * 0.7); s.roll = 0.05 * Math.sin(s.clock * 0.33);
      if (!s.flying) { s.st = "LANDING"; s.t = 0; beginLanding(); }
    } else if (s.st === "LANDING") {
      if (s.t < 5.5) {
        var q = s.t / 5.5;
        s.speed = lerp(s.v0, 72, q);
        s.alt = lerp(s.startAlt, EYE, 1 - Math.pow(1 - q, 1.6) * (1 - q) - 0 * q);
        s.alt = s.startAlt + (EYE - s.startAlt) * (1 - Math.pow(1 - q, 2.2));
        s.pitch = q < 0.82 ? -0.06 * Math.sin(Math.PI * q / 0.82) : 0.05 * Math.sin(Math.PI * (q - 0.82) / 0.18);
        s.roll = 0.03 * Math.sin(Math.PI * q) * (q < 0.7 ? 1 : 0);
      } else {
        if (!s.touched) { s.touched = true; call("onTouch"); }
        var u = Math.min(1, (s.t - 5.5) / 3.6);
        s.yaw = PARK_YAW * ease((u - 0.55) / 0.45);
        s.speed = 72 * Math.pow(1 - u, 1.7); s.alt = EYE + 0.35 * Math.max(0, Math.sin(u * 22)) * (1 - u) * (1 - u) * (u < 0.25 ? 1 : 0); s.pitch = 0; s.roll = 0;
        if (u >= 1) { s.st = "PARKED"; s.t = 0; s.speed = 0; }
      }
    }
    camZ -= s.speed * dt;
  }
  function beginLanding() {
    sim.touched = false; sim.startAlt = sim.alt; sim.v0 = Math.max(sim.speed, 80);
    var dist = (sim.v0 + 72) / 2 * 5.5;          // путь до касания
    placeAirport(camZ - dist + 300);              // касание — в 300 м за порогом
  }

  // ——— внешний вид по погоде ———
  function applyLook() {
    P = wx.day ? PAL.day : PAL.night;
    var top = new THREE.Color(P.top), hor = new THREE.Color(P.hor);
    if (wx.rain) { top.lerp(new THREE.Color(0x3c4a58), 0.6); hor.lerp(new THREE.Color(0x7d8996), 0.5); }
    skyMat.uniforms.top.value.copy(top); skyMat.uniforms.hor.value.copy(hor); skyMat.uniforms.glow.value.copy(glow);
    scene.fog.color.copy(hor).lerp(glow, 0.15);
    scene.fog.near = wx.rain ? 120 : 300; scene.fog.far = wx.rain ? 1600 : 2700;
    hemi.color.setHex(P.hemiSky); hemi.groundColor.setHex(P.hemiGround); hemi.intensity = P.hemiI * (wx.rain ? 0.85 : 1);
    sun.color.setHex(P.sun); sun.intensity = P.sunI * (wx.rain ? 0.5 : 1);
    baseMat.color.setHex(P.base); grassMat.color.setHex(P.grass); apronMat.color.setHex(P.apron);
    var cc = new THREE.Color(P.cloud); if (wx.rain) cc.lerp(new THREE.Color(0x707d8c), 0.6);
    cloudMat.color.copy(cc); cloudMat.opacity = P.cloudO; cloudMat.emissive.copy(cc).multiplyScalar(wx.day ? 0.45 : 0.25);
    starMat.opacity = (P.star && !wx.rain) ? 0.85 : 0;
    orbMat.color.setHex(wx.day ? 0xfff1d0 : 0xdfe8ff); orb.visible = !wx.rain && wx.cloud < 85; orb.scale.setScalar(wx.day ? 420 : 260);
    winMat.opacity = wx.day ? 0 : 0.95;
    for (var i = 0; i < halos.length; i++) halos[i].opacity = wx.day ? 0.15 : 0.75;
    glassMat.emissive.setHex(wx.day ? 0x000000 : 0x8a6a2c);
    rain.visible = !!wx.rain;
    updateChunks(true); buildClouds();
  }

  // ——— API для приложения ———
  window.setFlying = function (b) { sim.flying = !!b; };
  window.setWeather = function (cloud, rainy, day) { wx = { cloud: Math.max(0, Math.min(100, cloud | 0)), rain: !!rainy, day: !!day }; applyLook(); };
  window.setDrift = function (vx, vz) { drift.x = vx; drift.z = vz; };
  window.setGlow = function (hex) { glow.set(hex); skyMat.uniforms.glow.value.copy(glow); scene.fog.color.copy(skyMat.uniforms.hor.value).lerp(glow, 0.15); };

  function resize() { renderer.setSize(window.innerWidth, window.innerHeight); cam.aspect = window.innerWidth / Math.max(1, window.innerHeight); cam.updateProjectionMatrix(); }
  window.addEventListener("resize", resize);

  placeAirport(120);
  applyLook();

  var last = performance.now(), acc = 0, readySent = false, lastW = 0, lastH = 0;
  function frame(now) {
    requestAnimationFrame(frame);
    var dt = Math.min(0.05, (now - last) / 1000); last = now;
    step(dt);
    // облака: на стоянке плывут по ветру, в полёте остаются позади
    for (var i = 0; i < clouds.length; i++) {
      var c = clouds[i];
      if (sim.st === "PARKED") { c.position.x += drift.x * dt; c.position.z += drift.z * dt; }
      if (c.position.z > camZ + 250) { c.position.z -= 2700; c.position.x = (Math.random() - 0.5) * 1800; }
      if (c.position.z < camZ - 2800) c.position.z += 2700;
      if (c.position.x > 1000) c.position.x -= 2000; else if (c.position.x < -1000) c.position.x += 2000;
    }
    updateChunks(false);
    cam.position.set(0, sim.alt, camZ);
    var basePitch = sim.st === "PARKED" ? -0.03 : sim.alt > 60 ? -0.11 : -0.04;
    cam.rotation.set(sim.pitch + basePitch, sim.yaw, sim.roll);
    sky.position.copy(cam.position); stars.position.copy(cam.position);
    orb.position.copy(cam.position).addScaledVector(SUN_DIR, 3500);
    sun.position.copy(cam.position).addScaledVector(SUN_DIR, 800); sun.target.position.copy(cam.position);
    if (rain.visible) { rain.position.set(0, sim.alt - 10 + ((sim.clock * 60) % 25), camZ); }
    // стоянка — 30 кадров/с, полёт — каждый кадр
    acc += dt;
    // размер окна мог прийти позже (WebView создаётся до раскладки) — подстраиваемся каждый кадр
    var W = window.innerWidth, H = window.innerHeight;
    if (W > 0 && H > 0 && (W !== lastW || H !== lastH)) { lastW = W; lastH = H; resize(); }
    if (W === 0 || H === 0) return;
    if (sim.st !== "PARKED" || acc >= 0.033) {
      acc = 0; renderer.render(scene, cam);
      if (!readySent) {
        readySent = true;
        var info = W + "x" + H + " dpr " + (window.devicePixelRatio || 1);
        try {
          var gl = renderer.getContext(), px = new Uint8Array(4);
          gl.readPixels(gl.drawingBufferWidth >> 1, gl.drawingBufferHeight >> 2, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
          var ext = gl.getExtension("WEBGL_debug_renderer_info");
          info += " buf " + gl.drawingBufferWidth + "x" + gl.drawingBufferHeight + " px " + px[0] + "," + px[1] + "," + px[2] +
            (ext ? " gpu " + gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : "");
        } catch (e) { info += " read: " + e; }
        diag(info); call("ready");
      }
    }
  }
  requestAnimationFrame(frame);
})();
