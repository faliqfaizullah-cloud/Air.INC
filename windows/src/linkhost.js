'use strict';
// Glue between the Link server and the Electron app. Everything Electron-specific is injected through `d`, so this runs under plain Node in tests.
const os = require('os');
const path = require('path');
const K = require('./link');
const L = require('./lib');

module.exports = function createLinkHost(d) {
  // d = { cfg(), saveCfg(), send(ch,...args), getLib(), byId, artDir, musicDir(), startScan(), name, platform }
  let link = null, starting = false, lastState = '{}';
  const castMap = new Map(); let castN = 0;
  const outPeers = () => d.cfg().linkOut || (d.cfg().linkOut = {});
  const name = () => d.name || os.hostname();

  function addOut(r) {
    const o = outPeers();
    for (const id of Object.keys(o)) if (o[id].ip === r.ip) delete o[id];        // same device paired again
    o[r.id] = r; d.saveCfg(); d.send('link:evt', { t: 'change' });
  }
  function ensure() {
    if (link || starting) return; starting = true;
    const l = new K.LinkServer({
      name: name(), platform: d.platform || 'windows', state: () => lastState, lib: d.getLib,
      fileOf: id => (d.byId.get(id) || {}).path || null,
      artOf: id => { const t = d.byId.get(id); return t && t.art ? path.join(d.artDir, path.basename(t.art)) : null; },
      cmd: (a, o) => d.send('link:cmd', a, o || {}),
      cast: async info => {
        const tok = (++castN).toString(36) + Math.random().toString(36).slice(2, 8);
        castMap.set(tok, info.url); if (info.aurl) castMap.set(tok + 'a', info.aurl);
        if (castMap.size > 40) castMap.delete(castMap.keys().next().value);
        d.send('link:cast', { token: tok, title: info.title, artist: info.artist, album: info.album, dur: info.dur, art: !!info.aurl }); return true;
      },
      pullDir: () => path.join(d.musicDir(), 'Air.INC'),
      onPulled: (file, nm) => {
        const dir = path.dirname(file), c = d.cfg(), fl = (c.folders && c.folders.length) ? c.folders : [d.musicDir()];
        if (!fl.some(f => L.inside(f, dir))) { c.folders = fl.concat([dir]); d.saveCfg(); }
        d.send('link:evt', { t: 'pulled', name: nm }); d.startScan();
      },
      loadKeys: () => d.cfg().linkKeys || {}, saveKeys: k => { d.cfg().linkKeys = k; d.saveCfg(); },
      onChange: () => d.send('link:evt', { t: 'change' }), addOut,
    });
    l.start(d.ipOverride).then(() => { link = l; starting = false; d.send('link:evt', { t: 'change' }); }).catch(() => { starting = false; });
  }
  function info() {
    ensure();
    const base = { name: name(), platform: d.platform || 'windows', out: Object.values(outPeers()) };
    if (!link) return Object.assign(base, { on: false, ip: '', port: 0, code: '', qr: '', secret: '', peers: [] });
    if (!d.ipOverride) link.refreshIp();
    return Object.assign(base, { on: true, ip: link.ip, port: link.port, code: link.code(), qr: link.qr(), secret: link.secret, peers: link.peers() });
  }
  const privateUrl = u => { try { const x = new URL(u); return x.protocol === 'http:' && (K.isPrivate(x.hostname) || x.hostname === '127.0.0.1') && K.PORTS.includes(Number(x.port)); } catch (e) { return false; } };
  return {
    ensure, info,
    newSecret() { if (link) link.newSecret(); return info(); },
    kick(id) { if (link) link.kick(String(id)); },
    mint(id) { const t = d.byId.get(Number(id)); ensure(); return link && t ? Object.assign(link.mint(t.id), { name: path.basename(t.path) }) : null; },
    addOutJson(json) { try { const r = JSON.parse(json); if (r && r.id && r.ip && r.key) addOut({ id: String(r.id), name: String(r.name || 'Device').slice(0, 40), platform: String(r.platform || ''), ip: String(r.ip), port: Number(r.port), key: String(r.key) }); } catch (e) { } },
    delOut(id) { delete outPeers()[String(id)]; d.saveCfg(); },
    setState(json) { lastState = String(json); },
    bump() { if (link) link.bump(); },
    request(url, opts) { if (!privateUrl(url)) return Promise.resolve({ status: 0, text: '' }); const u = new URL(url), o = JSON.parse(opts || '{}'); return K.request(u.origin, u.pathname + u.search, { method: o.method, headers: o.headers, body: o.body == null ? undefined : o.body, timeout: o.timeout }); },
    castUrl: tok => castMap.get(tok),
    peerUrl(rel) { const m = /^([0-9a-f]+)\/(art|media)\/(\d+)$/.exec(rel), p = m && outPeers()[m[1]]; return p ? 'http://' + p.ip + ':' + p.port + '/' + m[2] + '/' + m[3] + '?k=' + p.key : null; },
    stop() { if (link) link.stop(); },
  };
};
