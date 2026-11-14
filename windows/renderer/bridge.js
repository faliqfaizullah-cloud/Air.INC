/* Air.INC for Windows: replaces the Android native layer (MediaService + MainActivity bridges) for the shared UI.
   Exposes window.AirPlayer / window.AirHaptic with the same contract, built on <audio> + Web Audio. */
(function () {
  'use strict';
  if (window.AirPlayer || !window.AirHost) return;
  var H = window.AirHost, LS = window.localStorage;
  var jget = function (k, d) { try { var v = LS.getItem(k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } };
  var jset = function (k, v) { try { LS.setItem(k, JSON.stringify(v)); } catch (e) { } };
  var call = function (n) { try { var f = window[n]; if (f) f.apply(null, Array.prototype.slice.call(arguments, 1)); } catch (e) { console.error(n, e); } };
  var invoke = function () { try { return H.invoke.apply(H, arguments); } catch (e) { return Promise.reject(e); } };
  var mediaUrl = function (id) { return (H.mediaUrl || function (i) { return 'air://media/' + i; })(id); };
  var abs = function (u) { try { return new URL(u, document.baseURI).href; } catch (e) { return u; } };

  /* ---------------- state ---------------- */
  var lib = [];
  var S = { idx: jget('lastIdx', -1), playing: false, vol: jget('vol', 1), shuf: jget('shuf', false), rep: jget('rep', false), speed: jget('speed', 1),
    sleepEnd: 0, kar: !!jget('kar', false), karst: 'idle', karp: 0, key: jget('key', 0) | 0 };
  var queue = null, loaded = -1, sleepT = 0;
  var A = new Audio(); A.crossOrigin = 'anonymous'; A.preload = 'auto';
  try { A.preservesPitch = true; } catch (e) { }

  /* ---------------- Web Audio graph ----------------
     src -> up(stereo) -> split -> [L,R] -> merger -> (pitch?) -> eq x5 -> bass -> loudness -> limiter -> master -> out
                              \-> mid -> HP140 -> LP8500 -> -beta ------^   (vocal remover: cancels the centre band)   */
  var ctx = null, G = {};
  var EQF = [60, 230, 910, 3600, 14000];
  var eqLv = jget('eq', [0, 0, 0, 0, 0]), bass = jget('bass', 0), loud = jget('loud', 0), vlimit = jget('vlimit', 1);
  function ensure() {
    if (ctx) return;
    var AC = window.AudioContext || window.webkitAudioContext;
    ctx = new AC({ latencyHint: 'playback' });
    G.src = ctx.createMediaElementSource(A);
    G.up = ctx.createGain(); G.up.channelCount = 2; G.up.channelCountMode = 'explicit'; G.up.channelInterpretation = 'speakers';
    G.split = ctx.createChannelSplitter(2); G.merge = ctx.createChannelMerger(2);
    G.mid = ctx.createGain(); G.mid.gain.value = 0.5;
    G.hp = ctx.createBiquadFilter(); G.hp.type = 'highpass'; G.hp.frequency.value = 140; G.hp.Q.value = 0.707;
    G.lp = ctx.createBiquadFilter(); G.lp.type = 'lowpass'; G.lp.frequency.value = 8500; G.lp.Q.value = 0.707;
    G.cut = ctx.createGain(); G.cut.gain.value = 0;
    G.src.connect(G.up); G.up.connect(G.split);
    G.split.connect(G.merge, 0, 0); G.split.connect(G.merge, 1, 1);
    G.split.connect(G.mid, 0, 0); G.split.connect(G.mid, 1, 0);
    G.mid.connect(G.hp); G.hp.connect(G.lp); G.lp.connect(G.cut);
    G.cut.connect(G.merge, 0, 0); G.cut.connect(G.merge, 0, 1);
    G.eqIn = ctx.createGain(); G.eq = [];
    var prev = G.eqIn;
    for (var i = 0; i < 5; i++) { var f = ctx.createBiquadFilter(); f.type = 'peaking'; f.frequency.value = EQF[i]; f.Q.value = 1.0; f.gain.value = (eqLv[i] || 0) / 100; prev.connect(f); prev = f; G.eq.push(f); }
    G.bass = ctx.createBiquadFilter(); G.bass.type = 'lowshelf'; G.bass.frequency.value = 90; prev.connect(G.bass);
    G.loud = ctx.createGain(); G.bass.connect(G.loud);
    G.lim = ctx.createDynamicsCompressor(); G.lim.threshold.value = -4; G.lim.knee.value = 6; G.lim.ratio.value = 14; G.lim.attack.value = 0.003; G.lim.release.value = 0.25;
    G.loud.connect(G.lim);
    G.master = ctx.createGain(); G.lim.connect(G.master); G.master.connect(ctx.destination);
    G.merge.connect(G.eqIn); G.pitchOn = false;
    applyFx(); applyKar(); applyKey();
    try {
      ctx.audioWorklet.addModule('pitch-worklet.js').then(function () {
        G.pitch = new AudioWorkletNode(ctx, 'air-pitch', { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [2] });
        applyKey();
      }).catch(function () { G.pitchFail = true; applyKey(); });
    } catch (e) { G.pitchFail = true; }
  }
  function applyFx() {
    if (!ctx) return;
    for (var i = 0; i < 5; i++) G.eq[i].gain.value = (eqLv[i] || 0) / 100;
    G.bass.gain.value = (bass / 1000) * 14;
    G.loud.gain.value = Math.pow(10, (loud / 100) / 20);
    G.master.gain.value = S.vol * vlimit;
  }
  function applyKar() { if (ctx) G.cut.gain.value = S.kar ? -0.97 : 0; }
  function applyKey() {
    var ratio = Math.pow(2, S.key / 12), sp = S.speed;
    var worklet = !!G.pitch && !G.pitchFail;
    if (S.key !== 0 && G.pitchFail) { try { A.preservesPitch = false; } catch (e) { } A.playbackRate = sp * ratio; }
    else { try { A.preservesPitch = true; } catch (e) { } A.playbackRate = sp; }
    if (!ctx) return;
    var wantOn = S.key !== 0 && worklet;
    if (wantOn !== G.pitchOn && (worklet || !wantOn)) {
      try { G.merge.disconnect(); } catch (e) { }
      if (wantOn) { G.merge.connect(G.pitch); try { G.pitch.disconnect(); } catch (e) { } G.pitch.connect(G.eqIn); }
      else { G.merge.connect(G.eqIn); if (G.pitch) try { G.pitch.disconnect(); } catch (e) { } }
      G.pitchOn = wantOn;
    }
    if (worklet) G.pitch.parameters.get('ratio').value = ratio;
  }

  /* ---------------- stats (same shape as MediaService) ---------------- */
  var ST = jget('stats', {});
  (function () { if (ST.p == null) ST.p = 0; if (ST.s == null) ST.s = 0; if (ST.ms == null) ST.ms = 0; if (!ST.w) ST.w = [0, 0, 0, 0, 0, 0, 0]; if (!ST.days) ST.days = []; })();
  var flushT = 0, flush = function () { clearTimeout(flushT); flushT = setTimeout(function () { jset('stats', ST); }, 800); };
  function monthObj(d) {
    if (!ST.months) ST.months = {};
    var k = d.getFullYear() + '-' + (d.getMonth() + 1), m = ST.months[k];
    if (!m) { m = ST.months[k] = { dp: [], dm: [], w: [0, 0, 0, 0, 0, 0, 0] }; for (var i = 0; i < 31; i++) { m.dp.push(0); m.dm.push(0); } }
    return m;
  }
  function addPlay() {
    var d = new Date(), mo = d.getMonth() + 1, dm = d.getDate();
    ST.p++; ST.w[d.getDay()]++;
    if (ST.mo !== mo) { ST.mo = mo; ST.days = []; }
    if (ST.days.indexOf(dm) < 0) ST.days.push(dm);
    var m = monthObj(d); m.dp[dm - 1]++; m.w[d.getDay()]++; flush();
  }
  setInterval(function () {
    if (!S.playing) return;
    ST.ms += 1000; var d = new Date(); monthObj(d).dm[d.getDate() - 1] += 1000; flush();
  }, 1000);

  /* ---------------- playback ---------------- */
  function track(i) { return i >= 0 && i < lib.length ? lib[i] : null; }
  function push() {
    var o = state();
    LASTJ = JSON.stringify(o); call('airState', o); mediaSessionPos();
  }
  var LASTJ = '';
  function state() {
    var t = track(S.idx), dur = isFinite(A.duration) && A.duration > 0 ? Math.round(A.duration * 1000) : (t ? t.dur : 0);
    return { idx: S.idx, playing: S.playing, pos: Math.round((loaded === S.idx ? A.currentTime : 0) * 1000), dur: dur, vol: S.vol, shuf: S.shuf, rep: S.rep, speed: S.speed,
      sleep: S.sleepEnd > 0 ? Math.max(0, S.sleepEnd - Date.now()) : 0, kar: S.kar, karst: S.karst, karp: S.karp, key: S.key };
  }
  function load(i) {
    var t = track(i); if (!t) return false;
    S.idx = i; jset('lastIdx', i); jset('lastId', t.id);
    if (loaded !== i) { A.src = mediaUrl(t.id); loaded = i; try { A.currentTime = 0; } catch (e) { } applyKey(); mediaSessionMeta(t); }
    return true;
  }
  function playIdx(i, play) {
    var n = lib.length; if (!n) return;
    i = ((i % n) + n) % n;
    var wasSame = loaded === i;
    if (!load(i)) return;
    if (wasSame) { try { A.currentTime = 0; } catch (e) { } } else addPlay();
    if (play) resume(); else { S.playing = false; push(); }
  }
  function resume() {
    if (S.idx < 0 && lib.length) { playIdx(0, true); return; }
    if (S.idx >= 0 && loaded !== S.idx) { if (!load(S.idx)) return; addPlay(); }
    ensure(); try { ctx.resume(); } catch (e) { }
    var p = A.play(); if (p && p.catch) p.catch(function () { });
    S.playing = true; push();
  }
  function pause() { A.pause(); S.playing = false; push(); }
  function next(auto) {
    var n = lib.length; if (!n) return;
    if (!auto && loaded === S.idx && A.currentTime < 20) { ST.s++; flush(); }
    var ni;
    if (queue && queue.length) {
      var m = queue.length, pos = queue.indexOf(S.idx);
      ni = queue[S.shuf && m > 1 ? (Math.max(pos, 0) + 1 + Math.floor(Math.random() * (m - 1))) % m : (pos + 1) % m];
    } else ni = S.shuf && n > 1 ? (S.idx + 1 + Math.floor(Math.random() * (n - 1))) % n : S.idx + 1;
    playIdx(ni, true);
  }
  function prev() {
    if (loaded === S.idx && A.currentTime > 3) { A.currentTime = 0; push(); return; }
    if (queue && queue.length) { var m = queue.length, pos = Math.max(0, queue.indexOf(S.idx)); playIdx(queue[(pos - 1 + m) % m], true); }
    else playIdx(S.idx - 1, true);
  }
  function seek(ms) { if (loaded !== S.idx) load(S.idx); try { A.currentTime = Math.max(0, ms / 1000); } catch (e) { } push(); }
  function setVol(v) { S.vol = Math.max(0, Math.min(1, v)); jset('vol', S.vol); applyFx(); push(); }
  A.addEventListener('ended', function () { if (S.rep) { A.currentTime = 0; A.play(); } else next(true); });
  ['play', 'pause', 'seeked', 'loadedmetadata', 'durationchange'].forEach(function (ev) { A.addEventListener(ev, function () { if (ev === 'pause' && A.ended) return; S.playing = !A.paused && !A.ended ? true : (ev === 'pause' ? false : S.playing); push(); }); });
  A.addEventListener('error', function () { if (S.playing) { call('airMemErr', ''); setTimeout(function () { next(true); }, 400); } });
  setInterval(function () { if (S.playing) push(); }, 500);
  A.volume = 1;

  /* ---------------- Windows media overlay / media keys (SMTC) ---------------- */
  function mediaSessionMeta(t) {
    if (!('mediaSession' in navigator) || !window.MediaMetadata) return;
    try { navigator.mediaSession.metadata = new MediaMetadata({ title: t.title, artist: t.artist, album: t.album, artwork: t.art ? [{ src: abs(t.art), sizes: '480x480', type: 'image/jpeg' }] : [] }); } catch (e) { }
  }
  function mediaSessionPos() {
    if (!('mediaSession' in navigator)) return;
    try { navigator.mediaSession.playbackState = S.playing ? 'playing' : 'paused'; if (isFinite(A.duration) && A.duration > 0) navigator.mediaSession.setPositionState({ duration: A.duration, position: Math.min(A.currentTime, A.duration), playbackRate: A.playbackRate || 1 }); } catch (e) { }
  }
  if ('mediaSession' in navigator) {
    var h = function (n, f) { try { navigator.mediaSession.setActionHandler(n, f); } catch (e) { } };
    h('play', resume); h('pause', pause); h('nexttrack', function () { next(false); }); h('previoustrack', prev);
    h('seekto', function (d) { if (d && d.seekTime != null) seek(d.seekTime * 1000); });
  }

  /* ---------------- sleep timer + alarm ---------------- */
  function setSleep(m) { clearTimeout(sleepT); S.sleepEnd = m > 0 ? Date.now() + m * 60000 : 0; if (m > 0) sleepT = setTimeout(function () { pause(); S.sleepEnd = 0; push(); }, m * 60000); push(); }
  var AL = function () { return jget('alarm', { on: false, h: 10, m: 15, vol: 0.6, vib: true, aid: -1 }); };
  var alFired = '';
  function alarmFire() {
    var a = AL(); a.on = false; jset('alarm', a); invoke('alarm:armed', false);
    queue = null;
    var ai = -1; for (var q = 0; q < lib.length; q++) if (a.aid >= 0 && lib[q].id === a.aid) { ai = q; break; }
    S.vol = 0.1; applyFx();
    if (lib.length) playIdx(ai >= 0 ? ai : (S.idx >= 0 ? S.idx : 0), true);
    var t = setInterval(function () { S.vol = Math.min(a.vol, S.vol + 0.06); applyFx(); push(); if (S.vol >= a.vol) clearInterval(t); }, 1000);
    invoke('alarm:show'); call('airAlarm');
  }
  setInterval(function () {
    var a = AL(); if (!a.on) return; var d = new Date(), k = d.getFullYear() + '-' + d.getMonth() + '-' + d.getDate() + '-' + d.getHours() + '-' + d.getMinutes();
    if (d.getHours() === a.h && d.getMinutes() === a.m && alFired !== k) { alFired = k; alarmFire(); }
  }, 10000);

  /* ---------------- library ---------------- */
  function setLibrary(arr, done) {
    var id = S.idx >= 0 && lib[S.idx] ? lib[S.idx].id : jget('lastId', -1);
    lib = arr || [];
    var ni = -1; for (var i = 0; i < lib.length; i++) if (lib[i].id === id) { ni = i; break; }
    if (ni !== loaded && loaded >= 0 && ni >= 0) loaded = ni;       // same song, new position
    if (ni < 0 && loaded >= 0) { loaded = -1; }
    S.idx = ni; queue = null;
    call('airLib', lib, !!done); push();
  }
  H.on('lib:update', function (arr, done) { ready(function () { setLibrary(arr, done); }); });
  var isReady = document.readyState === 'complete', waiters = [];
  function ready(f) { if (isReady) f(); else waiters.push(f); }
  window.addEventListener('load', function () { isReady = true; waiters.splice(0).forEach(function (f) { f(); }); });
  invoke('lib:load').then(function (arr) { if (arr && arr.length) ready(function () { setLibrary(arr, false); }); });

  /* ---------------- memories (index in localStorage, media files saved by the host) ---------------- */
  var MEMS = jget('mems', []);
  var mu = function (f) { return f ? 'air://mem/' + f : ''; };
  var decorate = function (m) { var o = {}; for (var k in m) o[k] = m[k]; if (m.file) o.path = mu(m.file); if (m.thumb) o.tpath = mu(m.thumb); return o; };
  var memList = function () { return MEMS.slice().sort(function (a, b) { return b.ts - a.ts; }).map(decorate); };
  var memSave = function () { jset('mems', MEMS); };
  var memPush = function (id) { call('airMem', memList(), id || null); };
  var newId = function () { return 'm' + Date.now() + Math.floor(Math.random() * 99); };
  function memAdd(o) { var m = Object.assign({ id: newId(), ts: Date.now(), fav: false, title: '', text: '' }, o); MEMS.push(m); memSave(); memPush(m.id); return m.id; }
  function vidThumb(url) {
    return new Promise(function (res) {
      var v = document.createElement('video'); v.muted = true; v.preload = 'metadata'; v.src = url; v.crossOrigin = 'anonymous';
      var done = function (t, d) { res({ thumb: t || '', dur: d || 0 }); };
      v.onloadedmetadata = function () { try { v.currentTime = Math.min(0.5, (v.duration || 1) / 2); } catch (e) { done(); } };
      v.onseeked = function () { try { var c = document.createElement('canvas'), k = Math.min(1, 480 / Math.max(v.videoWidth || 480, v.videoHeight || 480)); c.width = Math.round((v.videoWidth || 480) * k); c.height = Math.round((v.videoHeight || 270) * k); c.getContext('2d').drawImage(v, 0, 0, c.width, c.height); done(c.toDataURL('image/jpeg', 0.84), Math.round((v.duration || 0) * 1000)); } catch (e) { done('', Math.round((v.duration || 0) * 1000)); } };
      v.onerror = function () { done(); }; setTimeout(function () { done(); }, 4000);
    });
  }
  var blobBytes = function (b) { return b.arrayBuffer().then(function (x) { return new Uint8Array(x); }); };
  function memImport() {
    invoke('mem:pick').then(function (files) {
      (files || []).reduce(function (p, f) {
        return p.then(function () {
          if (f.type === 'video') return vidThumb(mu(f.file)).then(function (v) { return (v.thumb ? invoke('mem:thumb', { file: f.file, thumb: v.thumb }) : Promise.resolve('')).then(function (t) { memAdd({ type: 'video', file: f.file, thumb: t, dur: v.dur }); }); });
          memAdd({ type: 'photo', file: f.file, thumb: f.thumb }); return null;
        });
      }, Promise.resolve());
    }).catch(function () { call('airMemErr', 'Could not add that file'); });
  }

  /* camera sheet: photo + video from the webcam */
  var cam = null;
  function camStop() { if (cam) { try { cam.stream.getTracks().forEach(function (t) { t.stop(); }); } catch (e) { } if (cam.el.parentNode) cam.el.parentNode.removeChild(cam.el); cam = null; } }
  function camOpen(kind) {
    if (cam) return;
    navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 1280 }, height: { ideal: 720 } }, audio: kind === 'video' }).then(function (stream) {
      var el = document.createElement('div'); el.id = 'camM';
      el.innerHTML = '<div class="cb"><video autoplay muted playsinline></video><div class="cr"><button class="x" aria-label="Close">✕</button><button class="shot" aria-label="' + (kind === 'video' ? 'Record' : 'Capture') + '"><i></i></button><span class="ct"></span></div></div>';
      document.body.appendChild(el); var v = el.querySelector('video'); v.srcObject = stream; cam = { el: el, stream: stream, rec: null };
      el.querySelector('.x').onclick = camStop; el.onclick = function (e) { if (e.target === el) camStop(); };
      var shot = el.querySelector('.shot'), ct = el.querySelector('.ct');
      shot.onclick = function () {
        if (kind === 'photo') {
          var c = document.createElement('canvas'); c.width = v.videoWidth || 1280; c.height = v.videoHeight || 720; c.getContext('2d').drawImage(v, 0, 0, c.width, c.height);
          var th = document.createElement('canvas'), k = Math.min(1, 480 / Math.max(c.width, c.height)); th.width = Math.round(c.width * k); th.height = Math.round(c.height * k); th.getContext('2d').drawImage(c, 0, 0, th.width, th.height);
          c.toBlob(function (b) { blobBytes(b).then(function (bytes) { return invoke('mem:save', { bytes: bytes, ext: 'jpg', thumb: th.toDataURL('image/jpeg', 0.84) }); }).then(function (r) { memAdd({ type: 'photo', file: r.file, thumb: r.thumb }); camStop(); }); }, 'image/jpeg', 0.92);
        } else if (!cam.rec) {
          var chunks = [], t0 = Date.now(), mr = new MediaRecorder(stream, { mimeType: MediaRecorder.isTypeSupported('video/webm;codecs=vp9,opus') ? 'video/webm;codecs=vp9,opus' : 'video/webm' });
          cam.rec = mr; shot.classList.add('on'); mr.ondataavailable = function (e) { if (e.data.size) chunks.push(e.data); };
          var tm = setInterval(function () { var s = Math.floor((Date.now() - t0) / 1000); ct.textContent = Math.floor(s / 60) + ':' + ('0' + (s % 60)).slice(-2); }, 250);
          mr.onstop = function () {
            clearInterval(tm); var blob = new Blob(chunks, { type: 'video/webm' }), dur = Date.now() - t0;
            blobBytes(blob).then(function (bytes) { return invoke('mem:save', { bytes: bytes, ext: 'webm' }); }).then(function (r) {
              return vidThumb(mu(r.file)).then(function (v2) { return (v2.thumb ? invoke('mem:thumb', { file: r.file, thumb: v2.thumb }) : Promise.resolve('')).then(function (t) { memAdd({ type: 'video', file: r.file, thumb: t, dur: dur }); }); });
            }); camStop();
          };
          mr.start(250);
        } else cam.rec.stop();
      };
    }).catch(function () { call('airMemErr', 'No camera found — adding from files instead'); memImport(); });
  }

  /* microphone: memories voice notes + karaoke sing-along (memAmp = live level) */
  var mic = null, rec = null;
  function micInit(cb) {
    if (mic) return cb(true);
    navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false } }).then(function (stream) {
      var ac = new (window.AudioContext || window.webkitAudioContext)(), an = ac.createAnalyser(); an.fftSize = 1024; ac.createMediaStreamSource(stream).connect(an);
      mic = { stream: stream, an: an, buf: new Float32Array(an.fftSize) }; cb(true);
    }).catch(function () { cb(false); });
  }

  /* ---------------- the AirPlayer contract ---------------- */
  var prefs = function () { return jget('prefs', {}); };
  var toast = function (m) { window.dispatchEvent(new CustomEvent('airnote', { detail: m })); };
  window.AirPlayer = {
    cmd: function (a, json) {
      var o = {}; try { o = JSON.parse(json || '{}'); } catch (e) { }
      switch (a) {
        case 'play': playIdx(o.i | 0, true); break;
        case 'toggle': if (S.playing) pause(); else resume(); break;
        case 'resume': resume(); break;
        case 'pause': pause(); break;
        case 'next': next(false); break;
        case 'prev': prev(); break;
        case 'seek': seek(+o.ms || 0); break;
        case 'vol': setVol(+o.v); break;
        case 'shuf': S.shuf = !!o.b; jset('shuf', S.shuf); push(); break;
        case 'rep': S.rep = !!o.b; jset('rep', S.rep); push(); break;
        case 'queue': queue = o.q && o.q.length ? o.q.slice() : null; break;
        case 'eq': eqLv[o.b] = o.mb | 0; jset('eq', eqLv); applyFx(); break;
        case 'eqall': if (o.lv) { for (var i = 0; i < 5; i++) eqLv[i] = o.lv[i] | 0; jset('eq', eqLv); applyFx(); } break;
        case 'bass': bass = o.s | 0; jset('bass', bass); applyFx(); break;
        case 'loud': loud = o.g | 0; jset('loud', loud); applyFx(); break;
        case 'limit': vlimit = Math.max(0.4, Math.min(1, +o.v)); jset('vlimit', vlimit); applyFx(); break;
        case 'kar': ensure(); S.kar = o.on === 1; jset('kar', S.kar); S.karst = S.kar ? 'ready' : 'idle'; S.karp = S.kar ? 100 : 0; applyKar(); push(); break;
        case 'key': S.key = o.reset === 1 ? 0 : Math.max(-6, Math.min(6, S.key + (o.n | 0))); jset('key', S.key); ensure(); applyKey(); push(); break;
        case 'speed': S.speed = Math.max(0.5, Math.min(2, +o.v)); jset('speed', S.speed); applyKey(); push(); break;
        case 'sleep': setSleep(o.m | 0); break;
        case 'reload': H.invoke('lib:scan'); break;
      }
    },
    state: function () { return LASTJ || JSON.stringify(state()); },
    library: function () { return JSON.stringify(lib); },
    scan: function () { invoke('lib:scan'); },
    perm: function () { return true; }, askPerm: function () { invoke('lib:scan'); },
    stats: function () { return JSON.stringify(ST); },
    eqInfo: function () { return JSON.stringify({ n: 5, min: -1500, max: 1500, freqs: EQF, lv: eqLv.slice(0, 5), bass: bass, loud: loud }); },
    theme: function (m) { invoke('theme', m); },
    battery: function () { }, askBt: function () { }, openBt: function () { invoke('sys:bt'); },
    headphones: function () { var p = prefs(); return JSON.stringify({ connected: false, perm: true, battery: -1, l: -1, r: -1, 'case': -1, apoff: p.ap_off !== false, apon: !!p.ap_on, limit: vlimit }); },
    setPref: function (k, v) { if (k === 'alarm_vib') { var a = AL(); a.vib = !!v; jset('alarm', a); } else { var p = prefs(); p[k] = !!v; jset('prefs', p); } },
    setNum: function (k, v) { if (k === 'alarm_vol') { var a = AL(); a.vol = +v; jset('alarm', a); } },
    setLong: function (k, v) { if (k === 'alarm_id') { var a = AL(); a.aid = +v; jset('alarm', a); } },
    alarm: function (h, m, on) { var a = AL(); a.h = h; a.m = m; a.on = !!on; jset('alarm', a); invoke('alarm:armed', !!on); },
    getAlarm: function () { return JSON.stringify(AL()); },
    share: function (text) { try { navigator.clipboard.writeText(text); toast('Copied to clipboard'); } catch (e) { } },
    appVer: function () { return H.ver || ''; },
    updCheck: function () { invoke('upd:check').then(function (j) { call('airUpd', j); }); },
    updGet: function (u) { invoke('upd:get', u).then(function (j) { call('airUpd', j); }); },
    updCancel: function () { invoke('upd:cancel'); },
    updInstall: function () { invoke('upd:install').then(function (r) { if (r !== 'ok') call('airUpd', { t: 'fail', why: 'install' }); }); return 'ok'; },
    lyrics: function (id, t, a, al, dur) { invoke('lyrics:get', { id: id, title: t, artist: a, album: al, dur: dur }).then(function (r) { call('airLyrics', String(id), r); }).catch(function () { call('airLyrics', String(id), { err: true }); }); },
    lyricsRetry: function (id) { invoke('lyrics:retry', id); },
    memList: function () { return JSON.stringify(memList()); },
    memCapture: function (k) { if (k === 'pick') memImport(); else camOpen(k === 'video' ? 'video' : 'photo'); },
    memText: function (t, x) { return memAdd({ type: 'text', title: t, text: x }); },
    memEdit: function (id, t, x) { MEMS.forEach(function (m) { if (m.id === id) { if (t != null) m.title = t; if (x != null) m.text = x; } }); memSave(); memPush(null); },
    memFav: function (id, f) { MEMS.forEach(function (m) { if (m.id === id) m.fav = !!f; }); memSave(); memPush(null); },
    memDel: function (id) { var m = MEMS.filter(function (x) { return x.id === id; })[0]; MEMS = MEMS.filter(function (x) { return x.id !== id; }); memSave(); if (m) invoke('mem:del', [m.file, m.thumb]); memPush(null); },
    memShare: function (id) { var m = MEMS.filter(function (x) { return x.id === id; })[0]; if (m && m.file) invoke('mem:show', m.file); },
    memRecStart: function () {
      if (rec) return 'ok';
      if (!mic) { micInit(function (ok) { call('airRecPerm', ok); }); return 'perm'; }
      try {
        var chunks = [], mr = new MediaRecorder(mic.stream, { mimeType: 'audio/webm;codecs=opus' });
        rec = { mr: mr, chunks: chunks, t0: Date.now(), id: '' }; mr.ondataavailable = function (e) { if (e.data.size) chunks.push(e.data); }; mr.start(250); return 'ok';
      } catch (e) { rec = null; return 'err'; }
    },
    memAmp: function () { if (!mic || !rec) return 0; mic.an.getFloatTimeDomainData(mic.buf); var s = 0; for (var i = 0; i < mic.buf.length; i++) s += mic.buf[i] * mic.buf[i]; return Math.min(1, Math.sqrt(s / mic.buf.length) * 5); },
    memRecStop: function (keep) {
      if (!rec) return ''; var r = rec; rec = null; var dur = Date.now() - r.t0;
      if (!keep || dur < 800) { try { r.mr.stop(); } catch (e) { } return ''; }
      var id = newId(), file = id + '.webm';
      var m = { id: id, type: 'voice', ts: Date.now(), fav: false, title: '', text: '', file: file, dur: dur, thumb: '' };
      MEMS.push(m); memSave();
      r.mr.onstop = function () { blobBytes(new Blob(r.chunks, { type: 'audio/webm' })).then(function (bytes) { return invoke('mem:save', { bytes: bytes, ext: 'webm' }); }).then(function (o) {
        // host picks its own name; rename in index and notify
        m.file = o.file; memSave(); memPush(id); }); };
      try { r.mr.stop(); } catch (e) { } return id;
    },
  };
  window.AirHaptic = { hap: function () { } };
  // desktop helpers used by desk.js
  window.AirDeskApi = { graph: function () { return G; }, ctx: function () { return ctx; }, setVol: setVol, state: state, lib: function () { return lib; }, resume: resume, pause: pause, seek: seek, next: next, prev: prev, toast: toast };
  // nothing ever answers the Android back button; Esc is handled in desk.js
})();
