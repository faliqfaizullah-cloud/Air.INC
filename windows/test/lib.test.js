'use strict';
const assert = require('assert'), fs = require('fs'), os = require('os'), path = require('path');
const L = require('../src/lib');
(async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'airlib-'));
  const mk = (rel, data) => { const f = path.join(root, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, data || 'x'); return f; };
  const a = mk('Band/Album/02 - Two.mp3'), b = mk('Band/Album/10 - Ten.mp3'), c = mk('Solo - Song One.flac'); mk('notes.txt'); mk('$RECYCLE.BIN/junk.mp3'); mk('.hidden/x.mp3');
  // ids stable + case-insensitive
  assert.strictEqual(L.idOf('C:\\Music\\A.mp3'), L.idOf('c:\\music\\a.MP3')); assert(L.idOf(a) > 0);
  // listing skips junk, natural-sorts
  const files = await L.listAudio([root]);
  assert.deepStrictEqual(files.map(f => path.relative(root, f).replace(/\\/g, '/')), ['Band/Album/02 - Two.mp3', 'Band/Album/10 - Ten.mp3', 'Solo - Song One.flac']);
  assert.deepStrictEqual(L.guess('/x/Solo - Song One.flac'), { artist: 'Solo', title: 'Song One' });
  // scan: quick pass then enriched pass, order never changes
  const batches = [];
  const arr = await L.scan([root], {
    meta: async f => ({ title: 'T:' + path.basename(f), artist: 'Art', dur: 61000.4, picture: Buffer.from('p'), pictureKey: 'k1' }),
    saveArt: (k, buf) => 'air://art/' + k + '.jpg',
    onBatch: (x, done) => batches.push([x.map(t => t.title).join('|'), done]),
  });
  assert.strictEqual(batches[0][1], false); assert.strictEqual(batches[batches.length - 1][1], true);
  assert.strictEqual(arr[0].title, 'T:02 - Two.mp3'); assert.strictEqual(arr[0].art, 'air://art/k1.jpg'); assert.strictEqual(arr[0].dur, 61000);
  assert.deepStrictEqual(arr.map(t => t.id), (await L.scan([root], {})).map(t => t.id));
  // rescan reuses unchanged enriched entries (meta must not be called again)
  let calls = 0; await L.scan([root], { prev: arr, meta: async () => { calls++; return {}; } }); assert.strictEqual(calls, 0);
  // range maths
  assert.deepStrictEqual(L.rangeFor(null, 100), { status: 200, start: 0, end: 99 });
  assert.deepStrictEqual(L.rangeFor('bytes=10-19', 100), { status: 206, start: 10, end: 19 });
  assert.deepStrictEqual(L.rangeFor('bytes=90-', 100), { status: 206, start: 90, end: 99 });
  assert.deepStrictEqual(L.rangeFor('bytes=-20', 100), { status: 206, start: 80, end: 99 });
  assert.deepStrictEqual(L.rangeFor('bytes=10-500', 100), { status: 206, start: 10, end: 99 });
  assert.strictEqual(L.rangeFor('bytes=200-300', 100).status, 416);
  // path guard
  assert(L.inside('/a/b', '/a/b/c.js')); assert(!L.inside('/a/b', '/a/b/../x.js')); assert(!L.inside('/a/b', '/a/bc/x'));
  // versions
  assert(L.newer('v3.1', '3.0.0')); assert(!L.newer('3.0', '3.0.0')); assert(L.newer('3.0.1', '3.0')); assert(!L.newer('2.9', '3.0'));
  // lyrics: sidecar, then lrclib (stubbed), then negative cache
  const ld = path.join(root, 'ly'); fs.writeFileSync(a.replace('.mp3', '.lrc'), '[00:01.00]hello');
  const r1 = await L.lyrics(ld, { id: 1, title: 'x' }, a); assert.strictEqual(r1.src, 'file'); assert(r1.synced.includes('hello'));
  const fake = async u => u.includes('/get?') ? JSON.stringify({ syncedLyrics: '[00:02.00]yo', plainLyrics: 'yo' }) : null;
  const r2 = await L.lyrics(ld, { id: 2, title: 'Song', artist: 'A', dur: 100 }, c, fake); assert.strictEqual(r2.src, 'lrclib');
  const none = async () => null; const r3 = await L.lyrics(ld, { id: 3, title: 'N' }, c, none); assert.strictEqual(r3.none, true);
  const r4 = await L.lyrics(ld, { id: 3, title: 'N' }, c, fake); assert.strictEqual(r4.none, true, 'negative cache holds for 3 days');
  const r5 = await L.lyrics(ld, { id: 9, title: 'N' }, c, async () => { throw new Error('offline'); }); assert.strictEqual(r5.err, true);
  console.log('lib tests passed'); fs.rmSync(root, { recursive: true, force: true });
})().catch(e => { console.error(e); process.exit(1); });
