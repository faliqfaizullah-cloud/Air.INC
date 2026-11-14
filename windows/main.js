'use strict';
const { app, BrowserWindow, ipcMain, dialog, protocol, shell, nativeTheme, Tray, Menu, nativeImage, session } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { Readable } = require('stream');
const { spawn } = require('child_process');
const L = require('./src/lib');

protocol.registerSchemesAsPrivileged([{ scheme: 'air', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true, corsEnabled: true } }]);

const WWW = path.join(__dirname, 'www');
let win, tray, quitting = false, keepInTray = false;
let DATA, ART, MEM, LYR, CFG = {}, lib = [], scanToken = 0;
const byId = new Map();

// ---------- tiny settings file ----------
const cfgFile = () => path.join(DATA, 'settings.json');
function loadCfg() { try { CFG = JSON.parse(fs.readFileSync(cfgFile(), 'utf8')); } catch (e) { CFG = {}; } }
function saveCfg() { try { fs.writeFileSync(cfgFile(), JSON.stringify(CFG)); } catch (e) { } }
const libFile = () => path.join(DATA, 'library.json');
function setLib(arr) { lib = arr; byId.clear(); for (const t of arr) byId.set(t.id, t); }
const send = (ch, ...a) => { try { if (win && !win.isDestroyed()) win.webContents.send(ch, ...a); } catch (e) { } };
const pub = t => ({ id: t.id, title: t.title, artist: t.artist, album: t.album, aid: 0, dur: t.dur, genre: t.genre, art: t.art });

// ---------- custom protocol: air://app (UI), air://media/<id> (songs, range aware), air://art, air://mem ----------
function serve(file, req) {
  let st; try { st = fs.statSync(file); } catch (e) { return new Response('not found', { status: 404 }); }
  if (!st.isFile()) return new Response('not found', { status: 404 });
  const r = L.rangeFor(req.headers.get('range'), st.size);
  const h = { 'Content-Type': L.MIME[path.extname(file).toLowerCase()] || 'application/octet-stream', 'Accept-Ranges': 'bytes', 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-cache' };
  if (r.status === 416) return new Response('', { status: 416, headers: Object.assign(h, { 'Content-Range': 'bytes */' + st.size }) });
  h['Content-Length'] = String(r.end - r.start + 1);
  if (r.status === 206) h['Content-Range'] = `bytes ${r.start}-${r.end}/${st.size}`;
  if (req.method === 'HEAD') return new Response(null, { status: r.status, headers: h });
  return new Response(Readable.toWeb(fs.createReadStream(file, { start: r.start, end: r.end })), { status: r.status, headers: h });
}
function onAir(req) {
  const u = new URL(req.url), name = decodeURIComponent(u.pathname.replace(/^\/+/, ''));
  switch (u.hostname) {
    case 'app': { const f = path.join(WWW, name || 'index.html'); return L.inside(WWW, f) ? serve(f, req) : new Response('forbidden', { status: 403 }); }
    case 'media': { const t = byId.get(parseInt(name, 10)); return t ? serve(t.path, req) : new Response('not found', { status: 404 }); }
    case 'art': { const f = path.join(ART, path.basename(name)); return serve(f, req); }
    case 'mem': { const f = path.join(MEM, path.basename(name)); return serve(f, req); }
    default: return new Response('not found', { status: 404 });
  }
}

// ---------- library ----------
let mm = null;
try { mm = require('music-metadata'); } catch (e) { mm = null; }
const makeThumb = buf => {
  try { const im = nativeImage.createFromBuffer(buf); if (im.isEmpty()) return null; const s = im.getSize(); const k = Math.min(1, 480 / Math.max(s.width, s.height)); return (k < 1 ? im.resize({ width: Math.round(s.width * k), height: Math.round(s.height * k), quality: 'better' }) : im).toJPEG(86); } catch (e) { return null; }
};
async function readMeta(file) {
  if (!mm) return null;
  const m = await mm.parseFile(file, { skipPostHeaders: true });
  const c = m.common || {}, pic = c.picture && c.picture[0];
  return { title: c.title, artist: c.artist || c.albumartist, album: c.album, genre: c.genre && c.genre[0], dur: (m.format.duration || 0) * 1000,
    picture: pic ? pic.data : null, pictureKey: pic ? require('crypto').createHash('md5').update(pic.data.subarray(0, 4096)).update(String(pic.data.length)).digest('hex') : '' };
}
function saveArt(key, buf) {
  const f = path.join(ART, key + '.jpg');
  if (!fs.existsSync(f)) { const j = makeThumb(buf); if (!j) return ''; fs.writeFileSync(f, j); }
  return 'air://art/' + key + '.jpg';
}
async function startScan() {
  const token = ++scanToken;
  const folders = (CFG.folders && CFG.folders.length) ? CFG.folders : [app.getPath('music')];
  try {
    const arr = await L.scan(folders, {
      prev: lib, meta: readMeta, saveArt, isCancelled: () => token !== scanToken,
      onBatch: (a, done) => { if (token !== scanToken) return; setLib(a); send('lib:update', a.map(pub), done); if (done) fs.writeFile(libFile(), JSON.stringify(a), () => { }); },
    });
    void arr;
  } catch (e) { send('lib:update', lib.map(pub), true); }
}

// ---------- updates (GitHub Releases: the same release also carries the Android APK) ----------
let updFile = null, updCancel = false;
function repo() { try { return JSON.parse(fs.readFileSync(path.join(__dirname, 'repo.json'), 'utf8')).repo || ''; } catch (e) { return ''; } }
async function updCheck() {
  const j = { t: 'check', cur: app.getVersion() };
  const r = repo(); if (!r) { j.err = 'repo'; return j; }
  try {
    const res = await fetch('https://api.github.com/repos/' + r + '/releases/latest', { headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'Air.INC' } });
    if (!res.ok) { j.err = 'http' + res.status; return j; }
    const rel = await res.json(), tag = String(rel.tag_name || '');
    const a = (rel.assets || []).find(x => /setup.*\.exe$/i.test(x.name)) || (rel.assets || []).find(x => /\.exe$/i.test(x.name) && !/portable/i.test(x.name));
    j.latest = tag.replace(/^[vV]/, ''); j.url = a ? a.browser_download_url : ''; j.size = a ? a.size : 0; j.notes = rel.body || '';
    j.newer = !!j.url && L.newer(tag, j.cur);
  } catch (e) { j.err = 'net'; }
  return j;
}
async function updGet(url) {
  if (!/^https:\/\//.test(url)) return { t: 'fail', why: 'url' };
  updCancel = false;
  const f = path.join(os.tmpdir(), 'Air.INC-Setup.exe');
  try {
    const res = await fetch(url, { headers: { 'User-Agent': 'Air.INC' }, redirect: 'follow' });
    if (!res.ok) throw new Error('http');
    const total = Number(res.headers.get('content-length')) || 0; let got = 0, last = -1;
    const out = fs.createWriteStream(f);
    for await (const chunk of res.body) {
      if (updCancel) { out.destroy(); throw new Error('cancel'); }
      out.write(chunk); got += chunk.length;
      const p = total ? Math.floor(got * 100 / total) : 0; if (p !== last) { last = p; send('upd:prog', p); }
    }
    await new Promise((ok, no) => out.end(e => e ? no(e) : ok()));
    if (fs.statSync(f).size < 1024 * 1024) throw new Error('bad');
    updFile = f; return { t: 'done' };
  } catch (e) { try { fs.unlinkSync(f); } catch (x) { } return { t: 'fail', why: String(e.message) }; }
}

// ---------- window ----------
const barColors = dark => ({ color: dark ? '#101015' : '#f1f1f2', symbolColor: dark ? '#e8e8ee' : '#111111', height: 40 });
function createWindow() {
  const dark = nativeTheme.shouldUseDarkColors;
  win = new BrowserWindow({
    width: 1320, height: 860, minWidth: 1000, minHeight: 700, show: false, title: 'Air.INC',
    backgroundColor: dark ? '#101015' : '#f1f1f2', icon: path.join(__dirname, 'build', 'icon.png'),
    titleBarStyle: 'hidden', titleBarOverlay: barColors(dark), autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false, backgroundThrottling: false, autoplayPolicy: 'no-user-gesture-required', spellcheck: false },
  });
  win.once('ready-to-show', () => win.show());
  win.loadURL('air://app/index.html');
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https:\/\//.test(url)) shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', e => e.preventDefault());
  win.on('maximize', () => send('win:state', true)); win.on('unmaximize', () => send('win:state', false));
  win.on('close', e => { if (!quitting && keepInTray) { e.preventDefault(); win.hide(); } });
  win.webContents.on('before-input-event', (e, i) => { if (i.type === 'keyDown' && i.key === 'F11') { win.setFullScreen(!win.isFullScreen()); e.preventDefault(); } if (!app.isPackaged && i.key === 'F12') win.webContents.toggleDevTools(); });
}
function createTray() {
  try {
    const img = nativeImage.createFromPath(path.join(__dirname, 'build', 'icon.png')).resize({ width: 16, height: 16 });
    tray = new Tray(img); tray.setToolTip('Air.INC');
    tray.setContextMenu(Menu.buildFromTemplate([{ label: 'Show Air.INC', click: () => { win.show(); win.focus(); } }, { type: 'separator' }, { label: 'Quit', click: () => { quitting = true; app.quit(); } }]));
    tray.on('click', () => { win.show(); win.focus(); });
  } catch (e) { tray = null; }
}

// ---------- IPC ----------
function ipc() {
  ipcMain.on('app:ver', e => { e.returnValue = app.getVersion(); });
  ipcMain.handle('lib:load', () => lib.map(pub));
  ipcMain.handle('lib:scan', () => { startScan(); return true; });
  ipcMain.handle('lib:folders', () => (CFG.folders && CFG.folders.length) ? CFG.folders : [app.getPath('music')]);
  ipcMain.handle('lib:add', async () => {
    const r = await dialog.showOpenDialog(win, { title: 'Add a music folder', properties: ['openDirectory', 'multiSelections'] });
    if (r.canceled || !r.filePaths.length) return false;
    const cur = (CFG.folders && CFG.folders.length) ? CFG.folders : [app.getPath('music')];
    CFG.folders = Array.from(new Set(cur.concat(r.filePaths))); saveCfg(); startScan(); return true;
  });
  ipcMain.handle('lib:remove', (_e, f) => { CFG.folders = (CFG.folders || []).filter(x => x !== f); saveCfg(); startScan(); return true; });
  ipcMain.handle('lyrics:get', async (_e, q) => { const t = byId.get(Number(q.id)); return L.lyrics(LYR, q, t && t.path); });
  ipcMain.handle('lyrics:retry', (_e, id) => { try { fs.unlinkSync(path.join(LYR, Number(id) + '.json')); } catch (e) { } return true; });
  // memories: the renderer keeps the index, we only store the files
  const stamp = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  ipcMain.handle('mem:save', (_e, o) => {
    const ext = String(o.ext || 'bin').replace(/[^a-z0-9]/gi, '').slice(0, 5), id = stamp(), out = { file: id + '.' + ext, thumb: '' };
    fs.writeFileSync(path.join(MEM, out.file), Buffer.from(o.bytes));
    if (o.thumb) { const m = /^data:image\/\w+;base64,(.+)$/.exec(o.thumb); if (m) { out.thumb = id + '_t.jpg'; fs.writeFileSync(path.join(MEM, out.thumb), Buffer.from(m[1], 'base64')); } }
    return out;
  });
  ipcMain.handle('mem:thumb', (_e, o) => { const m = /^data:image\/\w+;base64,(.+)$/.exec(o.thumb || ''); if (!m) return ''; const n = path.basename(o.file, path.extname(o.file)) + '_t.jpg'; fs.writeFileSync(path.join(MEM, n), Buffer.from(m[1], 'base64')); return n; });
  ipcMain.handle('mem:pick', async () => {
    const r = await dialog.showOpenDialog(win, { title: 'Add to Memories', properties: ['openFile', 'multiSelections'], filters: [{ name: 'Photos & videos', extensions: ['jpg', 'jpeg', 'png', 'webp', 'gif', 'mp4', 'webm', 'mov', 'm4v'] }] });
    if (r.canceled) return [];
    return r.filePaths.slice(0, 20).map(p => {
      const ext = path.extname(p).slice(1).toLowerCase(), id = stamp(), file = id + '.' + ext, video = /^(mp4|webm|mov|m4v)$/.test(ext);
      fs.copyFileSync(p, path.join(MEM, file));
      let thumb = '';
      if (!video) { const im = nativeImage.createFromPath(p); if (!im.isEmpty()) { const s = im.getSize(), k = Math.min(1, 480 / Math.max(s.width, s.height)); thumb = id + '_t.jpg'; fs.writeFileSync(path.join(MEM, thumb), (k < 1 ? im.resize({ width: Math.round(s.width * k), height: Math.round(s.height * k) }) : im).toJPEG(84)); } }
      return { file, thumb, type: video ? 'video' : 'photo' };
    });
  });
  ipcMain.handle('mem:del', (_e, files) => { for (const f of files || []) { if (f) try { fs.unlinkSync(path.join(MEM, path.basename(f))); } catch (e) { } } return true; });
  ipcMain.handle('mem:show', (_e, f) => { const p = path.join(MEM, path.basename(f)); if (fs.existsSync(p)) shell.showItemInFolder(p); return true; });
  ipcMain.handle('upd:check', () => updCheck());
  ipcMain.handle('upd:get', (_e, url) => updGet(url));
  ipcMain.handle('upd:cancel', () => { updCancel = true; return true; });
  ipcMain.handle('upd:install', () => {
    if (!updFile || !fs.existsSync(updFile)) return 'nofile';
    try { spawn(updFile, [], { detached: true, stdio: 'ignore' }).unref(); quitting = true; setTimeout(() => app.quit(), 300); return 'ok'; } catch (e) { return 'fail'; }
  });
  ipcMain.handle('theme', (_e, m) => {
    nativeTheme.themeSource = m === 'dark' ? 'dark' : m === 'light' ? 'light' : 'system';
    try { win.setTitleBarOverlay(barColors(nativeTheme.shouldUseDarkColors)); win.setBackgroundColor(nativeTheme.shouldUseDarkColors ? '#101015' : '#f1f1f2'); } catch (e) { }
    return true;
  });
  ipcMain.handle('sys:bt', () => { shell.openExternal('ms-settings:bluetooth'); return true; });
  ipcMain.handle('alarm:show', () => { win.show(); win.focus(); return true; });
  ipcMain.handle('alarm:armed', (_e, on) => { keepInTray = !!on; if (on && !tray) createTray(); return true; });
  ipcMain.handle('win:max', () => { win.isMaximized() ? win.unmaximize() : win.maximize(); return win.isMaximized(); });
}

// ---------- boot ----------
if (!app.requestSingleInstanceLock()) { app.quit(); } else {
  app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.show(); win.focus(); } });
  app.whenReady().then(() => {
    DATA = app.getPath('userData'); ART = path.join(DATA, 'art'); MEM = path.join(DATA, 'memories'); LYR = path.join(DATA, 'lyrics');
    for (const d of [DATA, ART, MEM, LYR]) fs.mkdirSync(d, { recursive: true });
    loadCfg();
    try { setLib(JSON.parse(fs.readFileSync(libFile(), 'utf8'))); } catch (e) { setLib([]); }
    protocol.handle('air', onAir);
    session.defaultSession.setPermissionRequestHandler((_wc, perm, cb) => cb(perm === 'media' || perm === 'clipboard-sanitized-write'));
    session.defaultSession.setPermissionCheckHandler((_wc, perm) => perm === 'media' || perm === 'clipboard-sanitized-write');
    Menu.setApplicationMenu(null);
    ipc(); createWindow();
    if (!lib.length) startScan(); else setTimeout(startScan, 1500);
    app.on('activate', () => { if (!BrowserWindow.getAllWindows().length) createWindow(); });
  });
  app.on('before-quit', () => { quitting = true; });
  app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
}
