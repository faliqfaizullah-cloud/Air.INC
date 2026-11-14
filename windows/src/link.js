'use strict';
// Air Link v1: LAN link between two Air.INC devices (Windows <-> Android).  Pure Node, no Electron imports.
// Each device runs this small HTTP server; the device that *shows* the code/QR is the one others connect to.
const http = require('http');
const os = require('os');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const L = require('./lib');

const PORTS = [38211, 38212, 38213, 38214, 38215];
const CMDS = new Set(['play', 'toggle', 'resume', 'pause', 'next', 'prev', 'seek', 'vol', 'shuf', 'rep', 'queue', 'speed', 'sleep', 'kar', 'key']);
const AUDIO_EXT = /\.(mp3|m4a|aac|flac|wav|ogg|oga|opus)$/i;
const MAX_PULL = 600 * 1024 * 1024;

const rnd = n => { let s = ''; for (let i = 0; i < n; i++) s += crypto.randomInt(0, 10); return s; };
const hex = n => crypto.randomBytes(n).toString('hex');

/** Own LAN address: prefer private ranges, skip virtual adapters. */
function lanIp(ifaces) {
  ifaces = ifaces || os.networkInterfaces();
  const c = [];
  for (const name of Object.keys(ifaces)) for (const a of ifaces[name] || []) {
    if (a.family !== 'IPv4' || a.internal) continue;
    if (/vethernet|virtual|vmware|vbox|loopback|docker|wsl|hyper-v|tailscale|zerotier/i.test(name)) continue;
    const p = a.address.split('.').map(Number);
    const rank = p[0] === 192 && p[1] === 168 ? 0 : p[0] === 10 ? 1 : (p[0] === 172 && p[1] >= 16 && p[1] <= 31) ? 2 : p[0] === 169 ? 9 : 5;
    c.push({ ip: a.address, rank });
  }
  c.sort((a, b) => a.rank - b.rank);
  return c.length ? c[0].ip : '';
}
/** "192.168.1.23" + "71560" -> "48151 71560": last two octets as 5 digits, then the secret. */
function makeCode(ip, secret) { const p = ip.split('.').map(Number); return String(p[2] * 256 + p[3]).padStart(5, '0') + ' ' + secret; }
/** Code typed by the user (+ our own LAN ip for the first two octets) -> {ip, secret}; or a QR payload airlink://ip:port/secret */
function parseCode(text, ownIp) {
  text = String(text || '').trim();
  let m = /^airlink:\/\/(\d+\.\d+\.\d+\.\d+)(?::(\d+))?\/(\d{4,8})/.exec(text);
  if (m) return { ip: m[1], port: m[2] ? +m[2] : 0, secret: m[3] };
  const d = text.replace(/\D/g, '');
  if (d.length !== 10 || !ownIp) return null;
  const n = parseInt(d.slice(0, 5), 10); if (n > 65535) return null;
  const o = ownIp.split('.');
  return { ip: o[0] + '.' + o[1] + '.' + (n >> 8) + '.' + (n & 255), port: 0, secret: d.slice(5) };
}
const isPrivate = ip => /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.)/.test(ip);

class LinkServer {
  /**
   * h = {name, platform, state():string, lib():[{id,title,artist,album,dur,genre,art}], fileOf(id):path|null, artOf(id):path|null,
   *      cmd(a,o), cast(info)->bool, pullDir():dir, onPulled(file,name), loadKeys():{id:{key,name,platform}}, saveKeys(obj), onChange()}
   */
  constructor(h) { this.h = h; this.srv = null; this.port = 0; this.ip = ''; this.secret = rnd(5); this.keys = h.loadKeys ? h.loadKeys() : {}; this.tokens = new Map(); this.jobs = new Map(); this.fails = 0; this.lockUntil = 0; this.libV = 1; }

  async start(ipOverride) {
    this.ip = ipOverride || lanIp();
    for (const p of PORTS) {
      try { await new Promise((ok, no) => { const s = http.createServer((q, r) => this.handle(q, r)); s.once('error', no); s.listen(p, '0.0.0.0', () => { this.srv = s; this.port = p; ok(); }); }); break; } catch (e) { this.srv = null; }
    }
    if (!this.srv) throw new Error('no free port');
    this.srv.on('error', () => { });
    return this;
  }
  stop() { try { this.srv && this.srv.close(); } catch (e) { } this.srv = null; }
  refreshIp() { const ip = lanIp(); if (ip) this.ip = ip; return this.ip; }
  newSecret() { this.secret = rnd(5); this.fails = 0; return this.secret; }
  code() { return this.ip ? makeCode(this.ip, this.secret) : ''; }
  qr() { return this.ip ? 'airlink://' + this.ip + ':' + this.port + '/' + this.secret : ''; }
  peers() { return Object.keys(this.keys).map(id => ({ id, name: this.keys[id].name, platform: this.keys[id].platform })); }
  kick(id) { delete this.keys[id]; this.save(); }
  save() { try { this.h.saveKeys && this.h.saveKeys(this.keys); } catch (e) { } try { this.h.onChange && this.h.onChange(); } catch (e) { } }
  bump() { this.libV++; }

  /** One-time-ish URL that lets a paired peer pull one of OUR files (valid 30 min). */
  mint(id) {
    const t = hex(12); this.tokens.set(t, { id: Number(id), exp: Date.now() + 30 * 60000 });
    for (const [k, v] of this.tokens) if (v.exp < Date.now()) this.tokens.delete(k);
    const base = 'http://' + this.ip + ':' + this.port;
    return { m: base + '/media/' + id + '?t=' + t, a: base + '/art/' + id + '?t=' + t };
  }

  cors(r) { r.setHeader('Access-Control-Allow-Origin', '*'); r.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-Air-Key, Range'); r.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS'); r.setHeader('Access-Control-Expose-Headers', 'Content-Range, Accept-Ranges, Content-Length'); }
  json(r, code, o) { const b = Buffer.from(JSON.stringify(o)); this.cors(r); r.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': b.length, 'Cache-Control': 'no-store' }); r.end(b); }
  body(q, max) {
    return new Promise((ok, no) => { const c = []; let n = 0; q.on('data', d => { n += d.length; if (n > (max || 65536)) { no(new Error('big')); q.destroy(); } else c.push(d); }); q.on('end', () => { try { ok(c.length ? JSON.parse(Buffer.concat(c).toString('utf8')) : {}); } catch (e) { no(e); } }); q.on('error', no); });
  }
  who(q, u) {
    const k = q.headers['x-air-key'] || u.searchParams.get('k');
    if (!k) return null;
    for (const id of Object.keys(this.keys)) { const a = Buffer.from(this.keys[id].key), b = Buffer.from(String(k)); if (a.length === b.length && crypto.timingSafeEqual(a, b)) return id; }
    return null;
  }
  tokenOk(u, id) { const t = this.tokens.get(u.searchParams.get('t') || ''); return !!t && t.exp > Date.now() && t.id === Number(id); }

  async handle(q, r) {
    try {
      const u = new URL(q.url, 'http://x'), p = u.pathname;
      if (q.method === 'OPTIONS') { this.cors(r); r.writeHead(204); return r.end(); }
      if (p === '/info') return this.json(r, 200, { app: 'airinc', v: 1, name: this.h.name, platform: this.h.platform, ip: this.ip, port: this.port });
      if (p === '/pair' && q.method === 'POST') return await this.pair(q, r);
      let m = /^\/(media|art)\/(\d+)$/.exec(p);
      if (m && (this.who(q, u) || this.tokenOk(u, m[2]))) return this.file(q, r, m[1], Number(m[2]));
      const peer = this.who(q, u);
      if (!peer) return this.json(r, 401, { err: 'key' });
      if (p === '/state') { let s = {}; try { s = JSON.parse(this.h.state()); } catch (e) { } s.libV = this.libV; return this.json(r, 200, s); }
      if (p === '/lib') return this.json(r, 200, { v: this.libV, tracks: this.h.lib().map(t => ({ id: t.id, title: t.title, artist: t.artist, album: t.album, dur: t.dur, genre: t.genre || '', art: !!t.art })) });
      if (p === '/cmd' && q.method === 'POST') { const b = await this.body(q); if (!CMDS.has(b.a)) return this.json(r, 400, { err: 'cmd' }); this.h.cmd(b.a, b.o || {}); return this.json(r, 200, { ok: true }); }
      if (p === '/cast' && q.method === 'POST') { const b = await this.body(q); return await this.cast(q, r, b); }
      if (p === '/pull' && q.method === 'POST') { const b = await this.body(q); return this.pull(q, r, b); }
      if ((m = /^\/job\/([0-9a-f]+)$/.exec(p))) { const j = this.jobs.get(m[1]); return j ? this.json(r, 200, j) : this.json(r, 404, { err: 'job' }); }
      if (p === '/unlink' && q.method === 'POST') { this.kick(peer); return this.json(r, 200, { ok: true }); }
      return this.json(r, 404, { err: 'nf' });
    } catch (e) { try { this.json(r, 400, { err: 'bad' }); } catch (x) { } }
  }

  async pair(q, r) {
    if (Date.now() < this.lockUntil) return this.json(r, 429, { err: 'locked' });
    const b = await this.body(q);
    const a = Buffer.from(String(b.secret || '')), s = Buffer.from(this.secret);
    if (a.length !== s.length || !crypto.timingSafeEqual(a, s)) {
      if (++this.fails >= 6) { this.lockUntil = Date.now() + 60000; this.newSecret(); try { this.h.onChange && this.h.onChange(); } catch (e) { } }
      return this.json(r, 403, { err: 'code' });
    }
    this.fails = 0;
    const id = hex(6), key = hex(24);
    this.keys[id] = { key, name: String(b.name || 'Device').slice(0, 40), platform: String(b.platform || '').slice(0, 12) };
    this.save();
    this.json(r, 200, { ok: true, key, peerId: id, info: { name: this.h.name, platform: this.h.platform, ip: this.ip, port: this.port } });
    if (b.reverse) this.reversePair(q, b.reverse).catch(() => { });
  }
  /** The device that connected to us also exposes its own server: pair back so BOTH can control / send to each other. */
  async reversePair(q, rv) {
    if (!this.h.addOut || !rv) return;
    const from = String(q.socket.remoteAddress || '').replace(/^::ffff:/, '');
    if (rv.ip !== from || !PORTS.includes(Number(rv.port)) || !(isPrivate(from) || from === '127.0.0.1')) return;
    const r = await request('http://' + rv.ip + ':' + rv.port, '/pair', { method: 'POST', body: { secret: String(rv.secret || ''), name: this.h.name, platform: this.h.platform } });
    if (r.status !== 200) return;
    const j = JSON.parse(r.text);
    if (j && j.ok) this.h.addOut({ id: j.peerId, name: j.info.name, platform: j.info.platform, ip: rv.ip, port: Number(rv.port), key: j.key });
  }

  file(q, r, kind, id) {
    const f = kind === 'media' ? this.h.fileOf(id) : this.h.artOf(id);
    let st; try { st = f && fs.statSync(f); } catch (e) { st = null; }
    if (!st || !st.isFile()) return this.json(r, 404, { err: 'nf' });
    const g = L.rangeFor(q.headers.range, st.size);
    this.cors(r);
    const h = { 'Content-Type': L.MIME[path.extname(f).toLowerCase()] || 'application/octet-stream', 'Accept-Ranges': 'bytes', 'Content-Length': String(g.end - g.start + 1) };
    if (g.status === 416) { r.writeHead(416, { 'Content-Range': 'bytes */' + st.size }); return r.end(); }
    if (g.status === 206) h['Content-Range'] = 'bytes ' + g.start + '-' + g.end + '/' + st.size;
    r.writeHead(g.status, h);
    if (q.method === 'HEAD') return r.end();
    fs.createReadStream(f, { start: g.start, end: g.end }).on('error', () => r.destroy()).pipe(r);
  }

  /** Remote URLs must point back at the caller (no SSRF) and be private-network http. */
  urlOk(q, url) {
    let u; try { u = new URL(url); } catch (e) { return null; }
    const from = String(q.socket.remoteAddress || '').replace(/^::ffff:/, '');
    if (u.protocol !== 'http:' || u.hostname !== from || !isPrivate(from) && from !== '127.0.0.1' || !PORTS.includes(Number(u.port))) return null;
    return u;
  }
  async cast(q, r, b) {
    if (!this.h.cast) return this.json(r, 501, { err: 'unsupported' });
    const u = this.urlOk(q, b.url); if (!u) return this.json(r, 400, { err: 'url' });
    let au = b.aurl ? this.urlOk(q, b.aurl) : null;
    const ok = await this.h.cast({ url: u.href, aurl: au ? au.href : '', title: String(b.title || '').slice(0, 200), artist: String(b.artist || '').slice(0, 200), album: String(b.album || '').slice(0, 200), dur: Number(b.dur) || 0 });
    this.json(r, ok ? 200 : 500, ok ? { ok: true } : { err: 'cast' });
  }
  pull(q, r, b) {
    const u = this.urlOk(q, b.url); if (!u) return this.json(r, 400, { err: 'url' });
    let name = path.basename(String(b.name || 'song.mp3')).replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').slice(0, 120);
    if (!AUDIO_EXT.test(name)) return this.json(r, 400, { err: 'type' });
    const id = hex(6), job = { done: false, pct: 0, name }; this.jobs.set(id, job);
    this.download(u, name, job).catch(e => { job.done = true; job.err = String(e.message || e); });
    this.json(r, 200, { ok: true, job: id });
  }
  download(u, name, job) {
    return new Promise((ok, no) => {
      const dir = this.h.pullDir(); fs.mkdirSync(dir, { recursive: true });
      let dest = path.join(dir, name); const ext = path.extname(name), base = name.slice(0, -ext.length);
      for (let i = 2; fs.existsSync(dest); i++) dest = path.join(dir, base + ' (' + i + ')' + ext);
      const tmp = dest + '.part';
      const req = http.get(u.href, { agent: false, timeout: 15000 }, res => {
        if (res.statusCode !== 200) { res.resume(); return no(new Error('http' + res.statusCode)); }
        const total = Number(res.headers['content-length']) || 0; let got = 0;
        const out = fs.createWriteStream(tmp);
        res.on('data', c => { got += c.length; if (got > MAX_PULL) { req.destroy(new Error('too big')); } job.pct = total ? Math.floor(got * 100 / total) : 0; });
        res.pipe(out);
        out.on('finish', () => { fs.renameSync(tmp, dest); job.done = true; job.pct = 100; job.file = path.basename(dest); try { this.h.onPulled && this.h.onPulled(dest, path.basename(dest)); } catch (e) { } ok(); });
        res.on('error', e => { out.destroy(); try { fs.unlinkSync(tmp); } catch (x) { } no(e); });
      });
      req.on('error', e => { try { fs.unlinkSync(tmp); } catch (x) { } no(e); });
      req.on('timeout', () => req.destroy(new Error('timeout')));
    });
  }
}

/** Client side (used by the Windows main process on behalf of the renderer). */
function request(base, pth, o) {
  o = o || {};
  return new Promise(res => {
    let u; try { u = new URL(pth, base); } catch (e) { return res({ status: 0, text: '' }); }
    const body = o.body == null ? null : Buffer.from(typeof o.body === 'string' ? o.body : JSON.stringify(o.body));
    const hd = Object.assign({}, o.headers || {}); if (body) { hd['Content-Type'] = 'application/json'; hd['Content-Length'] = body.length; }
    const q = http.request({ host: u.hostname, port: u.port, path: u.pathname + u.search, method: o.method || 'GET', headers: hd, agent: false, timeout: o.timeout || 6000 }, r => {
      const c = []; r.on('data', d => c.push(d)); r.on('end', () => res({ status: r.statusCode, text: Buffer.concat(c).toString('utf8') }));
    });
    q.on('error', () => res({ status: 0, text: '' })); q.on('timeout', () => { q.destroy(); res({ status: 0, text: '' }); });
    if (body) q.write(body); q.end();
  });
}
/** Stream a peer URL to a Response-like (status, headers, Node stream) with Range pass-through: used for <audio>/<img> from air://. */
function proxy(url, range) {
  return new Promise(res => {
    let u; try { u = new URL(url); } catch (e) { return res(null); }
    const q = http.get({ host: u.hostname, port: u.port, path: u.pathname + u.search, headers: range ? { Range: range } : {}, agent: false, timeout: 10000 }, r => res(r));
    q.on('error', () => res(null)); q.on('timeout', () => { q.destroy(); res(null); });
  });
}

module.exports = { LinkServer, PORTS, CMDS, lanIp, makeCode, parseCode, isPrivate, request, proxy };
