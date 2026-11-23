'use strict';
// Builds ios/www from the shared UI (app/src/main/assets/index.html) + the Windows web bridge (Web Audio engine).
// Inline scripts/styles are transpiled for Safari 12 (iOS 12) with esbuild.
const fs = require('fs'), path = require('path');
const root = path.join(__dirname, '..'), assets = path.join(root, '..', 'app', 'src', 'main', 'assets'), win = path.join(root, '..', 'windows', 'renderer');
const www = path.join(root, 'www');
let esbuild = null; try { esbuild = require('esbuild'); } catch (e) { console.warn('esbuild missing: no transpile'); }
const copyDir = (a, b) => { fs.mkdirSync(b, { recursive: true }); for (const e of fs.readdirSync(a, { withFileTypes: true })) { const s = path.join(a, e.name), d = path.join(b, e.name); e.isDirectory() ? copyDir(s, d) : fs.copyFileSync(s, d); } };
const T = { target: ['safari12'], logLevel: 'silent' };
function js(code, name) {
  if (!esbuild) return code;
  try { return esbuild.transformSync(code, { ...T, loader: 'js' }).code; } catch (e) { console.warn('transpile failed for ' + name + ': ' + String(e.message).split('\n')[0]); return code; }
}
function css(code) { if (!esbuild) return code; try { return esbuild.transformSync(code, { ...T, loader: 'css' }).code; } catch (e) { return code; } }
fs.rmSync(www, { recursive: true, force: true });
copyDir(assets, www);
let html = fs.readFileSync(path.join(assets, 'index.html'), 'utf8');
// inline <script> / <style> (without src) -> transpiled
html = html.replace(/<script>([\s\S]*?)<\/script>/g, (m, c) => '<script>' + js(c, 'inline') + '</script>');
html = html.replace(/<style>([\s\S]*?)<\/style>/g, (m, c) => '<style>' + css(c) + '</style>');
if (!html.includes('<head>')) throw new Error('missing <head>');
html = html.replace('<head>', '<head>\n<script src="bridge.js"></script>');
fs.writeFileSync(path.join(www, 'index.html'), html);
for (const f of ['bridge.js', 'pitch-worklet.js']) {
  const c = fs.readFileSync(path.join(win, f), 'utf8');
  fs.writeFileSync(path.join(www, f), f === 'bridge.js' ? js(c, f) : c);   // the worklet needs no transpile (iOS 14.5+ only)
}
console.log('ios/www ready' + (esbuild ? ' (safari12)' : ''));
