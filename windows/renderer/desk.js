/* Air.INC for Windows: desktop shell around the shared UI (sidebar, stage, library panel, now-playing bar, shortcuts). */
(function () {
  'use strict';
  if (!window.AirHost) return;
  var $ = function (id) { return document.getElementById(id); };
  var B = document.body; B.classList.add('desk');
  var P = function (a, o) { try { AirPlayer.cmd(a, JSON.stringify(o || {})); } catch (e) { } };
  var fmt = function (ms) { ms = Math.max(0, ms | 0); var s = Math.floor(ms / 1000); return Math.floor(s / 60) + ':' + ('0' + (s % 60)).slice(-2); };
  var esc = function (x) { return String(x == null ? '' : x).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); };
  var ic = function (d) { return '<svg viewBox="0 0 24 24">' + d + '</svg>'; };
  var ICON = {
    explore: '<circle cx="12" cy="12" r="9"/><path d="M15.5 8.5l-2 5-5 2 2-5z"/>',
    music: '<path d="M9 18V6l10-2v12"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="16.5" cy="16" r="2.5"/>',
    library: '<rect x="4" y="5" width="16" height="15" rx="3"/><path d="M4 10h16M9 3v4M15 3v4"/>',
    eq: '<path d="M6 4v16M12 4v16M18 4v16"/><circle cx="6" cy="14" r="2" fill="currentColor"/><circle cx="12" cy="8" r="2" fill="currentColor"/><circle cx="18" cy="15" r="2" fill="currentColor"/>',
    alarm: '<circle cx="12" cy="13" r="7.5"/><path d="M12 9v4l3 2M5 4L3 6M19 4l2 2"/>',
    wheel: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="3.2"/>',
    game: '<rect x="3" y="8" width="18" height="10" rx="5"/><path d="M8 11v4M6 13h4"/><circle cx="16" cy="12.5" r=".8" fill="currentColor"/><circle cx="18" cy="14.5" r=".8" fill="currentColor"/>',
    board1: '<path d="M5 20V10M10 20V5M15 20v-8M20 20V8"/>',
    board2: '<path d="M4 7h10M4 12h16M4 17h7"/>',
    analytics: '<path d="M12 3v9h9"/><path d="M20 15a8.5 8.5 0 1 1-11-11"/>',
    sales: '<path d="M4 17l5-5 4 3 7-8"/><path d="M15 7h5v5"/>',
    insights: '<path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z"/><path d="M18 16l.8 2.2L21 19l-2.2.8L18 22l-.8-2.2L15 19l2.2-.8z"/>',
    product: '<path d="M12 3l8 4.5v9L12 21l-8-4.5v-9z"/><path d="M12 12l8-4.5M12 12L4 7.5M12 12v9"/>',
    memory: '<path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.600-7 10-7 10z"/>',
    folder: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2.5h8a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
    theme: '<circle cx="12" cy="12" r="4"/><path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6L7 7M17 17l1.400 1.400M5.6 18.400L7 17M17 7l1.400-1.400"/>',
    glow: '<path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z"/>',
    update: '<path d="M12 4v11M7.500 10.500L12 15l4.500-4.500M5 19h14"/>',
    search: '<circle cx="11" cy="11" r="6.500"/><path d="M16 16l4 4"/>',
  };
  var GROUPS = [
    ['Listen', [['music', 'Music'], ['explore', 'Explore'], ['wheel', 'Click wheel'], ['eq', 'Equalizer'], ['alarm', 'Alarm']]],
    ['Library', [['library', 'Calendar'], ['memory', 'Memories']]],
    ['Play', [['game', 'Game']]],
    ['Insights', [['board1', 'Board · Month'], ['board2', 'Board · Weekdays'], ['analytics', 'Analytics'], ['sales', 'Sales'], ['insights', 'Data Insights'], ['product', 'Product Analytics']]],
  ];

  /* ---------- shell ---------- */
  var shell = document.createElement('div'); shell.id = 'desk';
  var side = '';
  GROUPS.forEach(function (g) {
    side += '<div class="dGrp">' + g[0] + '</div>';
    g[1].forEach(function (it, i) { side += '<button class="dItem" data-go="' + it[0] + '" title="' + esc(it[1]) + '">' + ic(ICON[it[0]]) + '<span class="dTxt">' + esc(it[1]) + '</span></button>'; });
  });
  var R = {
    shuf: '<path d="M3 7h4l10 10h4M3 17h4l3-3M14 10l3-3h4M18 4l3 3-3 3M18 14l3 3-3 3"/>',
    rep: '<path d="M4 11V9a3 3 0 0 1 3-3h12M16 3l3 3-3 3M20 13v2a3 3 0 0 1-3 3H5M8 21l-3-3 3-3"/>',
    prev: '<path d="M6 5v14M19 5l-9 7 9 7z"/>', next: '<path d="M18 5v14M5 5l9 7-9 7z"/>',
    vol: '<path d="M4 9v6h4l5 4V5L8 9z"/><path d="M16 9a4 4 0 0 1 0 6M18.500 6.500a8 8 0 0 1 0 11"/>',
    mute: '<path d="M4 9v6h4l5 4V5L8 9z"/><path d="M17 9l5 6M22 9l-5 6"/>',
    lyr: '<path d="M5 7h10M5 12h14M5 17h8"/>', kar: '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/>',
  };
  shell.innerHTML =
    '<div id="dTop"><div id="dBrand"><i></i><span>Air<em>.INC</em></span></div>' +
    '<div id="dSearch">' + ic(ICON.search) + '<input id="dq" type="text" placeholder="Search songs, artists, albums" spellcheck="false" autocomplete="off"><kbd>Ctrl K</kbd></div></div>' +
    '<aside id="dSide">' + side + '<div class="grow"></div><div class="dFoot">' +
    '<button class="dItem" id="dAdd">' + ic(ICON.folder) + '<span class="dTxt">Add music folder</span></button>' +
    '<button class="dItem" id="dTheme">' + ic(ICON.theme) + '<span class="dTxt">Theme</span><span class="dTag dTxt" id="dThemeV"></span></button>' +
    '<button class="dItem" id="dGlow">' + ic(ICON.glow) + '<span class="dTxt">Glow effect</span><span class="dTag dTxt" id="dGlowV"></span></button>' +
    '<button class="dItem" id="dUpd">' + ic(ICON.update) + '<span class="dTxt">Check for updates</span></button></div></aside>' +
    '<section id="dStage"></section>' +
    '<aside id="dQ"><div class="qNow"><div class="qCov" id="qCov"></div><div class="qMeta"><b id="qT">Nothing playing</b><span id="qA">Pick a song</span>' +
    '<div class="qb"><button class="chipb" id="qLy">Lyrics</button><button class="chipb" id="qKa">Karaoke</button></div></div></div>' +
    '<div class="qHead"><b id="qH">Library</b><span id="qN"></span></div><div id="dList"></div></aside>' +
    '<footer id="dBar"><div class="L"><div class="qCov" id="bCov"></div><div class="qMeta"><b id="bT">Air.INC</b><span id="bA"></span></div></div>' +
    '<div class="C"><div class="ctl"><button class="bt" id="bSh" title="Shuffle (S)">' + ic(R.shuf) + '</button><button class="bt" id="bPr" title="Previous (Ctrl+←)">' + ic(R.prev) + '</button>' +
    '<button class="bt pp" id="bPp" title="Play / pause (Space)"></button><button class="bt" id="bNx" title="Next (Ctrl+→)">' + ic(R.next) + '</button><button class="bt" id="bRp" title="Repeat (R)">' + ic(R.rep) + '</button></div>' +
    '<div class="seekr"><span id="bCur">0:00</span><input id="bSeek" type="range" min="0" max="1000" value="0"><span id="bDur">0:00</span></div></div>' +
    '<div class="R"><button class="bt spdb" id="bSp" title="Speed">x1</button><button class="bt" id="bLy" title="Lyrics (L)">' + ic(R.lyr) + '</button><button class="bt" id="bKa" title="Karaoke (K)">' + ic(R.kar) + '</button>' +
    '<div class="vol"><button class="bt" id="bMu" title="Mute (M)"></button><input id="bVol" type="range" min="0" max="100" value="100"></div></div></footer>';
  B.appendChild(shell);
  var toastEl = document.createElement('div'); toastEl.id = 'dToast'; B.appendChild(toastEl);
  var toastT = 0; function toast(m) { toastEl.textContent = m; toastEl.classList.add('on'); clearTimeout(toastT); toastT = setTimeout(function () { toastEl.classList.remove('on'); }, 2200); }
  window.addEventListener('airnote', function (e) { toast(e.detail); });
  var app = $('app'); $('dStage').appendChild(app);
  var PLAY = '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>', PAUSE = '<svg viewBox="0 0 24 24"><path d="M7 5h3.500v14H7zM13.500 5H17v14h-3.500z"/></svg>';
  $('bPp').innerHTML = PLAY; $('bMu').innerHTML = ic(R.vol);

  /* ---------- layout ---------- */
  function layout() {
    var W = innerWidth, Hh = innerHeight, cs = getComputedStyle(B);
    var top = parseFloat(cs.getPropertyValue('--top')) || 40, bar = (parseFloat(cs.getPropertyValue('--bar')) || 92) + 14;
    var sb = $('dSide').getBoundingClientRect().width, qw = $('dQ').getBoundingClientRect().width;
    var availW = W - sb - qw - 24, availH = Hh - top - bar - 12;
    var sw = Math.max(360, Math.min(availW, availH * 0.64, 620));
    B.style.setProperty('--sw', Math.round(sw) + 'px'); B.style.setProperty('--sh', Math.round(availH) + 'px');
    try { var tb = navigator.windowControlsOverlay; if (tb && tb.visible) { var r = tb.getTitlebarAreaRect(); B.style.setProperty('--ctl', Math.max(0, W - r.x - r.width) + 'px'); } } catch (e) { }
  }
  addEventListener('resize', layout); layout();
  try { navigator.windowControlsOverlay && navigator.windowControlsOverlay.addEventListener('geometrychange', layout); } catch (e) { }
  setTimeout(layout, 60);

  /* ---------- sidebar ---------- */
  var items = shell.querySelectorAll('#dSide .dItem[data-go]');
  function mark() { for (var i = 0; i < items.length; i++) items[i].classList.toggle('sel', items[i].getAttribute('data-go') === cur); }
  Array.prototype.forEach.call(items, function (b) { b.onclick = function () { try { if (lyOpenNow()) lyClose(); } catch (e) { } go(b.getAttribute('data-go')); }; });
  var _go = window.go; window.go = function (id) { var r = _go.apply(this, arguments); mark(); return r; }; mark();
  $('dAdd').onclick = function () { AirHost.invoke('lib:add').then(function (ok) { if (ok) toast('Scanning your music…'); }); };
  $('dTheme').onclick = function () { localStorage.th = TH[(TH.indexOf(themeNow()) + 1) % 3]; applyTheme(); themeLbl(); };
  var themeLbl = function () { $('dThemeV').textContent = themeNow(); };
  $('dGlow').onclick = function () { setGlow(!glowOn()); glowLbl(); };
  var glowLbl = function () { $('dGlowV').textContent = glowOn() ? 'On' : 'Off'; };
  $('dUpd').onclick = function () { upOpen(); };
  themeLbl(); glowLbl();

  /* ---------- library panel ---------- */
  var q = '', hits = null, lastIdx = -2, lastPlay = null, built = '';
  function artOf(t) { return t && t.art ? "url('" + String(t.art).replace(/'/g, '%27') + "')" : ''; }
  function buildList() {
    var list = $('dList'), n = tracks.length;
    var ql = q.trim().toLowerCase();
    hits = null;
    if (ql) { hits = []; for (var i = 0; i < n; i++) { var t = tracks[i]; if ((t.n + ' ' + t.artist + ' ' + t.album).toLowerCase().indexOf(ql) >= 0) hits.push(i); } }
    var arr = hits || null, total = hits ? hits.length : n;
    $('qH').textContent = ql ? 'Results' : 'Library'; $('qN').textContent = total + (total === 1 ? ' song' : ' songs');
    if (!n) { list.innerHTML = '<div class="qEmpty">No music yet.<br>Add a folder with your songs.<br><button id="qAdd">Add music folder</button></div>'; var b = $('qAdd'); if (b) b.onclick = function () { $('dAdd').click(); }; built = ''; return; }
    if (!total) { list.innerHTML = '<div class="qEmpty">Nothing matches “' + esc(q) + '”.</div>'; built = ''; return; }
    var html = '', idxs = arr || null;
    for (var k = 0; k < total; k++) {
      var i2 = idxs ? idxs[k] : k, t2 = tracks[i2], a = artOf(t2);
      html += '<div class="qRow" data-i="' + i2 + '"><div class="c"' + (a ? ' style="background-image:' + a + '"' : '') + '></div><div class="t"><b>' + esc(t2.n) + '</b><span>' + esc(t2.artist || t2.album || '') + '</span></div><div class="d">' + (t2.dur ? fmt(t2.dur) : '') + '</div></div>';
    }
    list.innerHTML = html; built = ql + '|' + n; lastIdx = -2; markRow();
  }
  function markRow() {
    var list = $('dList'), on = list.querySelector('.qRow.on'); if (on) on.classList.remove('on', 'pl');
    if (st.idx < 0) return; var row = list.querySelector('.qRow[data-i="' + st.idx + '"]');
    if (row) { row.classList.add('on'); row.classList.toggle('pl', !!st.playing); if (st.idx !== lastIdx) { lastIdx = st.idx; if (!q) row.scrollIntoView({ block: 'nearest' }); } }
  }
  $('dList').onclick = function (e) {
    var r = e.target.closest('.qRow'); if (!r) return; var i = +r.getAttribute('data-i');
    P('queue', { q: hits ? hits.slice() : [] }); P('play', { i: i });
  };
  $('dList').ondblclick = function () { };
  var dq = $('dq'), dqT = 0;
  dq.oninput = function () { clearTimeout(dqT); dqT = setTimeout(function () { q = dq.value; buildList(); }, 120); };
  dq.onkeydown = function (e) { if (e.key === 'Escape') { dq.value = ''; q = ''; buildList(); dq.blur(); } else if (e.key === 'Enter' && hits && hits.length) { P('queue', { q: hits.slice() }); P('play', { i: hits[0] }); dq.blur(); } e.stopPropagation(); };
  var _lib = window.airLib; window.airLib = function () { var r = _lib.apply(this, arguments); buildList(); nowUI(true); return r; };
  buildList();
  $('qLy').onclick = function () { go('music'); lyOpen(); };
  $('qKa').onclick = function () { go('music'); setLyMode('karaoke'); lyOpen(); };
  $('bLy').onclick = function () { if (lyOpenNow()) lyClose(); else { go('music'); lyOpen(); } };
  $('bKa').onclick = function () { go('music'); setLyMode('karaoke'); lyOpen(); };

  /* ---------- now playing bar ---------- */
  var dragging = false, vol0 = 1, shown = '';
  function nowUI(force) {
    var t = tracks[st.idx], key = (t ? t.id : '') + '|' + (t ? t.art : '');
    if (force || key !== shown) {
      shown = key; var a = artOf(t);
      ['qCov', 'bCov'].forEach(function (id) { $(id).style.backgroundImage = a || ''; });
      $('qT').textContent = $('bT').textContent = t ? t.n : (tracks.length ? 'Pick a song' : 'Nothing playing');
      $('qA').textContent = $('bA').textContent = t ? (t.artist || t.album || '') : '';
      document.title = t ? t.n + ' — Air.INC' : 'Air.INC';
    }
  }
  function barUI() {
    $('bPp').innerHTML = st.playing ? PAUSE : PLAY;
    $('bSh').classList.toggle('on', !!st.shuf); $('bRp').classList.toggle('on', !!st.rep);
    $('bSp').textContent = 'x' + (st.speed || 1); $('bSp').classList.toggle('on', (st.speed || 1) !== 1);
    $('bKa').classList.toggle('on', !!st.kar);
    var v = Math.round((st.vol == null ? 1 : st.vol) * 100); if (document.activeElement !== $('bVol')) $('bVol').value = v; $('bVol').style.setProperty('--p', v + '%');
    $('bMu').innerHTML = ic(v === 0 ? R.mute : R.vol); $('qKa').classList.toggle('on', !!st.kar);
    markRow();
  }
  function tick() {
    if (dragging) return;
    var dur = st.dur || 0, pos = Math.min(dur || 1e9, Math.max(0, curPos()));
    $('bCur').textContent = fmt(pos); $('bDur').textContent = fmt(dur);
    var p = dur ? Math.round(pos / dur * 1000) : 0; $('bSeek').value = p; $('bSeek').style.setProperty('--p', (p / 10) + '%');
  }
  var _st = window.airState; window.airState = function () { var r = _st.apply(this, arguments); nowUI(); barUI(); tick(); return r; };
  setInterval(tick, 250);
  var sk = $('bSeek');
  sk.oninput = function () { dragging = true; var dur = st.dur || 0; $('bCur').textContent = fmt(sk.value / 1000 * dur); sk.style.setProperty('--p', (sk.value / 10) + '%'); };
  sk.onchange = function () { P('seek', { ms: Math.round(sk.value / 1000 * (st.dur || 0)) }); dragging = false; };
  $('bPp').onclick = function () { toggle(); };
  $('bNx').onclick = function () { P('next'); }; $('bPr').onclick = function () { P('prev'); };
  $('bSh').onclick = function () { P('shuf', { b: !st.shuf }); }; $('bRp').onclick = function () { P('rep', { b: !st.rep }); };
  $('bSp').onclick = function () { $('spd').onclick(); setTimeout(barUI, 30); };
  $('bVol').oninput = function (e) { var v = +e.target.value / 100; vol0 = v || vol0; setVol(v); e.target.style.setProperty('--p', e.target.value + '%'); };
  $('bMu').onclick = function () { if ((st.vol || 0) > 0) { vol0 = st.vol; setVol(0); } else setVol(vol0 || 1); };
  var bump = function (d) { setVol(Math.max(0, Math.min(1, (st.vol == null ? 1 : st.vol) + d))); };
  $('dBar').addEventListener('wheel', function (e) { if (e.target.closest('.R')) { e.preventDefault(); bump(e.deltaY < 0 ? 0.04 : -0.04); } }, { passive: false });
  $('dStage').addEventListener('wheel', function (e) { if (cur === 'wheel' || (cur === 'music' && !lyOpenNow())) { e.preventDefault(); bump(e.deltaY < 0 ? 0.03 : -0.03); } }, { passive: false });
  nowUI(true); barUI(); tick();

  /* ---------- keyboard ---------- */
  var typing = function (t) { return t && (t.tagName === 'INPUT' && t.type !== 'range' || t.tagName === 'TEXTAREA' || t.isContentEditable); };
  document.addEventListener('keydown', function (e) {
    var t = e.target, k = e.key, c = e.ctrlKey || e.metaKey;
    if (c && (k === 'k' || k === 'f')) { e.preventDefault(); dq.focus(); dq.select(); return; }
    if (typing(t)) return;
    if (k === 'Escape') { try { if (!window.airBack || !airBack()) { if (dq.value) { dq.value = ''; q = ''; buildList(); } } } catch (x) { } return; }
    if (e.altKey && /^[1-9]$/.test(k)) { var it = items[+k - 1]; if (it) { e.preventDefault(); it.click(); } return; }
    if (k === ' ') { e.preventDefault(); toggle(); }
    else if (k === 'ArrowRight') { e.preventDefault(); c ? P('next') : P('seek', { ms: Math.min(st.dur || 0, curPos() + (e.shiftKey ? 15000 : 5000)) }); }
    else if (k === 'ArrowLeft') { e.preventDefault(); c ? P('prev') : P('seek', { ms: Math.max(0, curPos() - (e.shiftKey ? 15000 : 5000)) }); }
    else if (k === 'ArrowUp' && t.tagName !== 'INPUT') { e.preventDefault(); bump(0.05); }
    else if (k === 'ArrowDown' && t.tagName !== 'INPUT') { e.preventDefault(); bump(-0.05); }
    else if (!c && !e.altKey) {
      var l = k.toLowerCase();
      if (l === 'm') $('bMu').click(); else if (l === 'l') $('bLy').click(); else if (l === 'k') $('bKa').click();
      else if (l === 's') $('bSh').click(); else if (l === 'r') $('bRp').click(); else if (l === '/') { e.preventDefault(); dq.focus(); }
    }
  });
  // right click shouldn't open the browser menu
  addEventListener('contextmenu', function (e) { if (!typing(e.target)) e.preventDefault(); });
})();
