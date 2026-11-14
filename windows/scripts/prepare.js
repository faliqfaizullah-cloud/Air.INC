'use strict';
// Builds windows/www from the Android UI (single source of truth: app/src/main/assets/index.html).
//  * copies the page + fonts
//  * converts viewport units (vw/vh) to container units (cqw/cqh) so the phone-designed screens size themselves
//    from the "stage" card instead of the whole desktop window
//  * injects the Windows bridge (replaces the Android native layer) and the desktop layout
const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '..');
const src = path.join(root, '..', 'app', 'src', 'main', 'assets');
const www = path.join(root, 'www');

function copyDir(a, b) {
  fs.mkdirSync(b, { recursive: true });
  for (const e of fs.readdirSync(a, { withFileTypes: true })) {
    const s = path.join(a, e.name), d = path.join(b, e.name);
    if (e.isDirectory()) copyDir(s, d); else fs.copyFileSync(s, d);
  }
}
function transform(html) {
  let n = 0;
  html = html.replace(/(\d*\.?\d+)(vw|vh)(?![a-zA-Z])/g, (m, num, u) => { n++; return num + (u === 'vw' ? 'cqw' : 'cqh'); });
  const inject = (needle, text, after) => {
    const i = html.indexOf(needle); if (i < 0) throw new Error('missing ' + needle);
    html = after ? html.slice(0, i + needle.length) + text + html.slice(i + needle.length) : html.slice(0, i) + text + html.slice(i);
  };
  inject('<head>', '\n<script src="bridge.js"></script>', true);
  inject('</head>', '<link rel="stylesheet" href="desk.css">\n');
  inject('</body>', '<script src="desk.js"></script>\n');
  return { html, n };
}
function main() {
  fs.rmSync(www, { recursive: true, force: true });
  copyDir(src, www);
  const r = transform(fs.readFileSync(path.join(src, 'index.html'), 'utf8'));
  fs.writeFileSync(path.join(www, 'index.html'), r.html);
  for (const f of ['bridge.js', 'pitch-worklet.js', 'desk.css', 'desk.js']) fs.copyFileSync(path.join(root, 'renderer', f), path.join(www, f));
  if (process.env.GITHUB_REPOSITORY) fs.writeFileSync(path.join(root, 'repo.json'), JSON.stringify({ repo: process.env.GITHUB_REPOSITORY }));
  else if (!fs.existsSync(path.join(root, 'repo.json'))) fs.writeFileSync(path.join(root, 'repo.json'), JSON.stringify({ repo: '' }));
  console.log('www ready (' + r.n + ' viewport units converted)');
}
if (require.main === module) main(); else module.exports = { transform };
