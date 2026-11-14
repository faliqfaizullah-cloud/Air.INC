'use strict';
// Pure Node helpers for the Windows build: library scan, file ids, HTTP range maths, lyrics lookup.
// No Electron imports here so everything can be unit-tested with plain `node`.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const AUDIO = new Set(['.mp3', '.m4a', '.aac', '.flac', '.wav', '.ogg', '.oga', '.opus']);
const SKIP_DIR = /^(\$recycle\.bin|system volume information|node_modules|\.git|appdata)$/i;

/** Stable positive 31-bit id from a file path (alarm + stats refer to tracks by id). */
function idOf(p) {
  const h = crypto.createHash('md5').update(String(p).toLowerCase()).digest();
  return (h.readUInt32BE(0) & 0x7fffffff) || 1;
}

/** Walk folders (breadth first), yield audio file paths. Never follows symlink loops. */
async function listAudio(folders, isCancelled) {
  const out = [], seen = new Set();
  const q = folders.slice();
  while (q.length) {
    if (isCancelled && isCancelled()) break;
    const dir = q.shift();
    let real; try { real = fs.realpathSync(dir); } catch (e) { continue; }
    if (seen.has(real)) continue; seen.add(real);
    let ents; try { ents = await fs.promises.readdir(dir, { withFileTypes: true }); } catch (e) { continue; }
    for (const e of ents) {
      const full = path.join(dir, e.name);
      if (e.isDirectory()) { if (!e.name.startsWith('.') && !SKIP_DIR.test(e.name)) q.push(full); }
      else if (e.isFile() && AUDIO.has(path.extname(e.name).toLowerCase())) out.push(full);
    }
  }
  const nameKey = s => s.toLowerCase();
  out.sort((a, b) => nameKey(a).localeCompare(nameKey(b), undefined, { numeric: true }));
  return out;
}

/** Title/artist guessed from the file name when there are no tags: "Artist - Title.mp3". */
function guess(file) {
  const base = path.basename(file, path.extname(file)).replace(/_/g, ' ').trim();
  const m = /^(.{1,60}?)\s+-\s+(.+)$/.exec(base);
  if (m) return { artist: m[1].trim(), title: m[2].trim() };
  return { artist: '', title: base || 'Unknown' };
}

/**
 * Scan folders and report progress.
 *  opts.meta(file) -> Promise<{title,artist,album,genre,dur(ms),picture:Buffer|null,pictureKey}>   (optional)
 *  opts.saveArt(key, buffer) -> 'air://art/…' | ''                                              (optional)
 *  opts.prev: previous library array (reused when path+mtime+size unchanged)
 *  opts.onBatch(arr, done)
 * Order is by path and NEVER changes between the quick and enriched passes, so a playing index stays valid.
 */
async function scan(folders, opts) {
  opts = opts || {};
  const files = await listAudio(folders, opts.isCancelled);
  const prev = new Map((opts.prev || []).map(t => [t.path, t]));
  const arr = [];
  for (const f of files) {
    let st; try { st = fs.statSync(f); } catch (e) { continue; }
    const old = prev.get(f);
    if (old && old.mtime === st.mtimeMs && old.size === st.size && old.meta) { arr.push(old); continue; }
    const g = guess(f);
    arr.push({ id: idOf(f), title: g.title, artist: g.artist, album: path.basename(path.dirname(f)), aid: 0, dur: 0, genre: '', art: '', path: f, mtime: st.mtimeMs, size: st.size, meta: false });
  }
  if (opts.onBatch) opts.onBatch(arr, false);           // pass 1: instant list from file names
  if (!opts.meta) { if (opts.onBatch) opts.onBatch(arr, true); return arr; }
  let n = 0;
  for (let i = 0; i < arr.length; i++) {
    if (opts.isCancelled && opts.isCancelled()) break;
    const t = arr[i];
    if (t.meta) continue;
    try {
      const m = await opts.meta(t.path);
      if (m) {
        if (m.title) t.title = m.title;
        if (m.artist) t.artist = m.artist;
        if (m.album) t.album = m.album;
        if (m.genre) t.genre = m.genre;
        if (m.dur) t.dur = Math.round(m.dur);
        if (m.picture && opts.saveArt) t.art = opts.saveArt(m.pictureKey || String(t.id), m.picture) || '';
      }
    } catch (e) { /* unreadable tags: keep guessed values */ }
    t.meta = true;
    if (++n % 40 === 0 && opts.onBatch) opts.onBatch(arr, false);
  }
  if (opts.onBatch) opts.onBatch(arr, true);
  return arr;
}

/** HTTP Range header -> {status, start, end}. Needed so <audio> can seek in files served by the custom protocol. */
function rangeFor(header, size) {
  if (!header) return { status: 200, start: 0, end: size - 1 };
  const m = /^bytes=(\d*)-(\d*)$/.exec(String(header).trim());
  if (!m || (m[1] === '' && m[2] === '')) return { status: 200, start: 0, end: size - 1 };
  let start, end;
  if (m[1] === '') { const n = parseInt(m[2], 10); start = Math.max(0, size - n); end = size - 1; }
  else { start = parseInt(m[1], 10); end = m[2] === '' ? size - 1 : Math.min(parseInt(m[2], 10), size - 1); }
  if (!(start <= end) || start >= size) return { status: 416, start: 0, end: 0 };
  return { status: 206, start, end };
}

const MIME = { '.mp3': 'audio/mpeg', '.m4a': 'audio/mp4', '.aac': 'audio/aac', '.flac': 'audio/flac', '.wav': 'audio/wav', '.ogg': 'audio/ogg', '.oga': 'audio/ogg', '.opus': 'audio/ogg',
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.ttf': 'font/ttf', '.woff2': 'font/woff2',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.svg': 'image/svg+xml', '.mp4': 'video/mp4', '.webm': 'video/webm' };

/** True when `file` is inside `root` (no ../ escapes). */
function inside(root, file) {
  const r = path.resolve(root), f = path.resolve(file);
  return f === r || f.startsWith(r + path.sep);
}

// ---------- lyrics: cache -> .lrc next to the song -> LRCLIB (same logic as the Android build) ----------
async function http(url, timeout) {
  const ac = new AbortController(), t = setTimeout(() => ac.abort(), timeout || 9000);
  try {
    const r = await fetch(url, { signal: ac.signal, headers: { 'User-Agent': 'Air.INC (Windows)' } });
    return r.ok ? await r.text() : null;
  } finally { clearTimeout(t); }
}
const has = x => x && ((x.syncedLyrics || '') !== '' || (x.plainLyrics || '') !== '');
const pack = (synced, plain, src) => ({ synced: synced || '', plain: plain || '', src });

async function lyrics(dir, req, trackPath, fetchText) {
  fetchText = fetchText || http;
  fs.mkdirSync(dir, { recursive: true });
  const cache = path.join(dir, req.id + '.json');
  try {
    const o = JSON.parse(fs.readFileSync(cache, 'utf8'));
    if (!o.none || Date.now() - (o.ts || 0) < 3 * 86400000) return o;
  } catch (e) { }
  try {
    if (trackPath) {
      const base = trackPath.replace(/\.[^./\\]+$/, '');
      for (const ext of ['.lrc', '.LRC', '.txt']) {
        const f = base + ext;
        if (fs.existsSync(f) && fs.statSync(f).size > 0) {
          const t = fs.readFileSync(f, 'utf8');
          const out = /\[\d+:\d+/.test(t) ? pack(t, '', 'file') : pack('', t, 'file');
          fs.writeFileSync(cache, JSON.stringify(out)); return out;
        }
      }
    }
  } catch (e) { }
  try {
    const q = 'track_name=' + encodeURIComponent(req.title || '') + '&artist_name=' + encodeURIComponent(req.artist || '') +
      '&album_name=' + encodeURIComponent(req.album || '') + (req.dur > 0 ? '&duration=' + req.dur : '');
    const r = await fetchText('https://lrclib.net/api/get?' + q);
    let o = r ? JSON.parse(r) : null;
    if (!has(o)) {
      const s = await fetchText('https://lrclib.net/api/search?q=' + encodeURIComponent((req.title || '') + ' ' + (req.artist || '')));
      o = null;
      if (s) for (const x of JSON.parse(s)) if (has(x) && (!(req.dur > 0) || Math.abs((x.duration == null ? req.dur : x.duration) - req.dur) < 6)) { o = x; break; }
    }
    const out = o ? pack(o.syncedLyrics, o.plainLyrics, 'lrclib') : { none: true, ts: Date.now() };
    fs.writeFileSync(cache, JSON.stringify(out));
    return out;
  } catch (e) { return { err: true }; }
}

/** a < b ? semantic version compare (tags like v3.1 or 3.1.0). */
function newer(latest, cur) {
  const n = v => String(v).replace(/[^0-9.]/g, '').split('.').map(x => parseInt(x, 10) || 0);
  const a = n(latest), b = n(cur);
  for (let i = 0; i < 4; i++) { const x = a[i] || 0, y = b[i] || 0; if (x !== y) return x > y; }
  return false;
}

module.exports = { AUDIO, idOf, listAudio, guess, scan, rangeFor, MIME, inside, lyrics, newer };
