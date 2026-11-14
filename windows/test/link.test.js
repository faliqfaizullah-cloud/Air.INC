'use strict';
const assert = require('assert'), fs = require('fs'), os = require('os'), path = require('path');
const K = require('../src/link');
(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'airlink-')), song = path.join(dir, 'a.mp3'); fs.writeFileSync(song, Buffer.alloc(5000, 7));
  const cmds = [], casts = [], pulled = []; let saved = {};
  const mk = (name, platform, extra) => new K.LinkServer(Object.assign({
    name, platform, state: () => JSON.stringify({ idx: 0, playing: true, pos: 5, dur: 100 }),
    lib: () => [{ id: 77, title: 'T', artist: 'A', album: 'B', dur: 5, genre: '', art: '' }], fileOf: id => id === 77 ? song : null, artOf: () => null,
    cmd: (a, o) => cmds.push([a, o]), cast: async i => { casts.push(i); return true; }, pullDir: () => path.join(dir, 'got'), onPulled: (f, n) => pulled.push(n),
    loadKeys: () => saved, saveKeys: k => { saved = JSON.parse(JSON.stringify(k)); }, onChange: () => { },
  }, extra || {}));
  // code helpers
  assert.strictEqual(K.makeCode('192.168.1.23', '71560'), '00279 71560');
  assert.deepStrictEqual(K.parseCode('00279 71560', '192.168.9.9'), { ip: '192.168.1.23', port: 0, secret: '71560' });
  assert.deepStrictEqual(K.parseCode('airlink://10.0.0.5:38212/12345', ''), { ip: '10.0.0.5', port: 38212, secret: '12345' });
  assert.strictEqual(K.parseCode('123', '1.2.3.4'), null);
  assert.strictEqual(K.lanIp({ eth0: [{ family: 'IPv4', address: '10.1.2.3', internal: false }], 'vEthernet (WSL)': [{ family: 'IPv4', address: '172.20.0.1', internal: false }], wifi: [{ family: 'IPv4', address: '192.168.0.9', internal: false }] }), '192.168.0.9');
  const A = await mk('PC', 'windows').start('127.0.0.1'), base = 'http://127.0.0.1:' + A.port;
  const get = (p, k) => K.request(base, p, { headers: k ? { 'X-Air-Key': k } : {} });
  assert.strictEqual(JSON.parse((await get('/info')).text).platform, 'windows');
  assert.strictEqual((await get('/state')).status, 401);                                           // locked without a key
  assert.strictEqual((await K.request(base, '/pair', { method: 'POST', body: { secret: '00000' } })).status, 403);
  const good = await K.request(base, '/pair', { method: 'POST', body: { secret: A.secret, name: 'Pixel', platform: 'android' } });
  assert.strictEqual(good.status, 200); const { key, peerId } = JSON.parse(good.text);
  assert.strictEqual(saved[peerId].name, 'Pixel');
  assert.strictEqual(JSON.parse((await get('/state', key)).text).libV, 1);
  assert.strictEqual(JSON.parse((await get('/lib', key)).text).tracks[0].title, 'T');
  assert.strictEqual((await K.request(base, '/cmd', { method: 'POST', body: { a: 'next' }, headers: { 'X-Air-Key': key } })).status, 200);
  assert.strictEqual((await K.request(base, '/cmd', { method: 'POST', body: { a: 'alarm' }, headers: { 'X-Air-Key': key } })).status, 400);  // not whitelisted
  assert.deepStrictEqual(cmds, [['next', {}]]);
  // media with range, both by key and by minted token; wrong token refused
  const rr = await K.request(base, '/media/77?k=' + key, { headers: { Range: 'bytes=10-19' } }); assert.strictEqual(rr.status, 206); assert.strictEqual(rr.text.length, 10);
  const t = A.mint(77); assert.strictEqual((await K.request(base, new URL(t.m).pathname + new URL(t.m).search)).status, 200);
  assert.strictEqual((await K.request(base, '/media/77?t=bad')).status, 401); assert.strictEqual((await K.request(base, '/media/78?k=' + key)).status, 404);
  // pull: B (phone) mints a URL, A (pc) pulls it; caller must be the URL's host
  const B = await mk('Phone', 'android', { loadKeys: () => ({}), saveKeys: () => { } }).start('127.0.0.1');
  const bt = B.mint(77), pk = JSON.parse((await K.request('http://127.0.0.1:' + A.port, '/pull', { method: 'POST', body: { url: bt.m, name: 'Song One.mp3' }, headers: { 'X-Air-Key': key } })).text);
  assert(pk.ok); let job; for (let i = 0; i < 40; i++) { job = JSON.parse((await get('/job/' + pk.job, key)).text); if (job.done) break; await new Promise(r => setTimeout(r, 50)); }
  assert(job.done && !job.err, JSON.stringify(job)); assert.strictEqual(fs.statSync(path.join(dir, 'got', 'Song One.mp3')).size, 5000); assert.deepStrictEqual(pulled, ['Song One.mp3']);
  for (const bad of [{ url: 'http://8.8.8.8:38211/media/1', name: 'a.mp3' }, { url: bt.m, name: 'evil.exe' }, { url: 'http://127.0.0.1:9999/x', name: 'a.mp3' }, { url: 'file:///etc/passwd', name: 'a.mp3' }]) assert.strictEqual((await K.request(base, '/pull', { method: 'POST', body: bad, headers: { 'X-Air-Key': key } })).status, 400);
  // cast (windows host) + unsupported host
  assert.strictEqual((await K.request(base, '/cast', { method: 'POST', body: { url: bt.m, title: 'x' }, headers: { 'X-Air-Key': key } })).status, 200); assert.strictEqual(casts[0].title, 'x');
  const C = await mk('Phone2', 'android', { cast: null }).start('127.0.0.1'); const ck = JSON.parse((await K.request('http://127.0.0.1:' + C.port, '/pair', { method: 'POST', body: { secret: C.secret } })).text).key;
  assert.strictEqual((await K.request('http://127.0.0.1:' + C.port, '/cast', { method: 'POST', body: { url: bt.m }, headers: { 'X-Air-Key': ck } })).status, 501);
  // brute force: 6 wrong codes lock pairing and rotate the secret
  const old = B.secret; for (let i = 0; i < 6; i++) await K.request('http://127.0.0.1:' + B.port, '/pair', { method: 'POST', body: { secret: '00000' } });
  assert.strictEqual((await K.request('http://127.0.0.1:' + B.port, '/pair', { method: 'POST', body: { secret: old } })).status, 429); assert.notStrictEqual(B.secret, old);
  B.stop(); C.stop();   // only 5 fixed ports exist
  // mutual pairing: D connects to A and offers its own server; A pairs back and ends up with an outgoing record for D
  const outs = []; const D = await mk('Phone3', 'android', { loadKeys: () => ({}), saveKeys: () => { } }).start('127.0.0.1');
  const A2 = await mk('PC2', 'windows', { loadKeys: () => ({}), saveKeys: () => { }, addOut: r => outs.push(r) }).start('127.0.0.1');
  const mp = await K.request('http://127.0.0.1:' + A2.port, '/pair', { method: 'POST', body: { secret: A2.secret, name: 'Phone3', platform: 'android', reverse: { ip: '127.0.0.1', port: D.port, secret: D.secret } } });
  assert.strictEqual(mp.status, 200); for (let i = 0; i < 40 && !outs.length; i++) await new Promise(r => setTimeout(r, 25));
  assert.strictEqual(outs.length, 1); assert.strictEqual(outs[0].name, 'Phone3'); assert.strictEqual(outs[0].port, D.port);
  assert.strictEqual(JSON.parse((await K.request('http://127.0.0.1:' + D.port, '/state', { headers: { 'X-Air-Key': outs[0].key } })).text).idx, 0);   // key really works on D
  const A3 = await mk('PC3', 'windows', { loadKeys: () => ({}), saveKeys: () => { }, addOut: r => outs.push(r) }).start('127.0.0.1');
  await K.request('http://127.0.0.1:' + A3.port, '/pair', { method: 'POST', body: { secret: A3.secret, reverse: { ip: '8.8.8.8', port: 38211, secret: '1' } } }); await new Promise(r => setTimeout(r, 150)); assert.strictEqual(outs.length, 1, 'reverse to a foreign ip is ignored');
  [A2, A3, D].forEach(x => x.stop());
  // unlink removes the key
  assert.strictEqual((await K.request(base, '/unlink', { method: 'POST', body: {}, headers: { 'X-Air-Key': key } })).status, 200); assert.strictEqual((await get('/state', key)).status, 401);
  [A, B, C].forEach(s => s.stop()); fs.rmSync(dir, { recursive: true, force: true });
  console.log('link tests passed');
})().catch(e => { console.error(e); process.exit(1); });
