'use strict';
// Test double for one device's native side (what Electron main + preload, or the Android bridge, provide to the page).
// Uses the REAL link server + glue code, so page <-> device <-> network behaviour is exercised end to end.
const http = require('http'), fs = require('fs'), path = require('path');
const L = require('../src/lib'), K = require('../src/link'), create = require('../src/linkhost');
const [, , port, name, platform, dir] = process.argv;
let CFG = {}, lib = [], byId = new Map(), events = [];
const send = (ch, ...a) => events.push([ch, ...a]);
const pub = t => ({ id: t.id, title: t.title, artist: t.artist, album: t.album, aid: 0, dur: t.dur || 8000, genre: '', art: '' });
const host = create({ cfg: () => CFG, saveCfg() { }, send, getLib: () => lib, get byId() { return byId; }, artDir: dir, musicDir: () => dir, startScan: () => rescan(), name, platform, ipOverride: '127.0.0.1' });
if (platform === 'android') { /* phones cannot be cast to yet: the real Android server answers 501 */ }
async function rescan() { lib = await L.scan([dir, path.join(dir, 'Air.INC')]); byId = new Map(lib.map(t => [t.id, t])); host.bump(); send('lib:update', lib.map(pub), true); }
const cors = r => { r.setHeader('Access-Control-Allow-Origin', '*'); r.setHeader('Access-Control-Allow-Headers', '*'); };
http.createServer(async (q, r) => {
  cors(r); const u = new URL(q.url, 'http://x');
  if (q.method === 'OPTIONS') { r.writeHead(204); return r.end(); }
  const out = (o, ct) => { r.writeHead(200, { 'Content-Type': ct || 'application/json' }); r.end(typeof o === 'string' ? o : JSON.stringify(o)); };
  if (u.pathname === '/sync') {
    const ch = u.searchParams.get('ch'), a = JSON.parse(u.searchParams.get('a') || '[]');
    const v = ch === 'link:srv' ? JSON.stringify(host.info()) : ch === 'link:new' ? JSON.stringify(host.newSecret()) : ch === 'link:kick' ? (host.kick(a[0]), 'true')
      : ch === 'link:mint' ? (() => { const m = host.mint(a[0]); return m ? JSON.stringify(m) : ''; })() : ch === 'link:addout' ? (host.addOutJson(a[0]), 'true') : ch === 'link:delout' ? (host.delOut(a[0]), 'true')
      : ch === 'lib' ? JSON.stringify(lib.map(pub)) : null;
    return out({ v });
  }
  if (u.pathname === '/invoke') {
    const b = await new Promise(ok => { const c = []; q.on('data', d => c.push(d)); q.on('end', () => ok(JSON.parse(Buffer.concat(c).toString() || '{}'))); });
    const a = b.args || []; let v = null;
    if (b.ch === 'link:state') host.setState(a[0]); else if (b.ch === 'link:req') v = await host.request(a[0], a[1]); else if (b.ch === 'lib:load') v = lib.map(pub); else if (b.ch === 'lib:scan') { rescan(); v = true; } else if (b.ch === 'alarm:armed') v = true;
    return out({ v });
  }
  if (u.pathname === '/events') { const e = events; events = []; return out(e); }
  if (u.pathname === '/pulled') { let f = []; try { f = fs.readdirSync(path.join(dir, 'Air.INC')); } catch (e) { } return out(f); }
  if (u.pathname.startsWith('/castproxy/')) {
    const url = host.castUrl(u.pathname.slice(11)); const res = url && await K.proxy(url, q.headers.range);
    if (!res) { r.writeHead(404); return r.end(); }
    const h = { 'Access-Control-Allow-Origin': '*' }; for (const k of ['content-type', 'content-length', 'content-range', 'accept-ranges']) if (res.headers[k]) h[k] = res.headers[k];
    r.writeHead(res.statusCode, h); return res.pipe(r);
  }
  r.writeHead(404); r.end();
}).listen(Number(port), () => { rescan().then(() => host.ensure()); console.log('ready ' + name); });
