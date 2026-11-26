/* Air.INC · iOS-only Now Playing layout (Liquid Glass): big album art, seek bar, transport and a glass toolbar.
   It sits on top of the shared UI and talks to it through the same globals (st, tracks, P). */
(function () {
  'use strict';
  var $ = function (i) { return document.getElementById(i); };
  var music = $('music'); if (!music) return;
  var title = music.querySelector('.title'), grid = music.querySelector('.mgrid'), strip = music.querySelector('.strip');
  if (!title || !grid || !strip) return;
  document.body.classList.add('ix');

  var art = document.createElement('div'); art.id = 'ixArt';
  art.innerHTML = '<i class="ixImg"></i><i class="ixGloss"></i>';
  music.insertBefore(art, title);

  var ctl = document.createElement('div'); ctl.id = 'ixCtl';
  ctl.innerHTML =
    '<div class="ixSeek"><span id="ixCur">0:00</span><div id="ixBar"><i></i><b></b></div><span id="ixDur">0:00</span></div>' +
    '<div class="ixTrans">' +
    '<button id="ixPrev" aria-label="Previous"><svg viewBox="0 0 24 24"><path d="M6 6v12M19 6v12l-10-6z" fill="currentColor"/></svg></button>' +
    '<button id="ixPlay" class="big" aria-label="Play"><svg class="pl" viewBox="0 0 24 24"><path d="M8 5v14l11-7z" fill="currentColor"/></svg><svg class="ps" viewBox="0 0 24 24"><path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z" fill="currentColor"/></svg></button>' +
    '<button id="ixNext" aria-label="Next"><svg viewBox="0 0 24 24"><path d="M18 6v12M5 6v12l10-6z" fill="currentColor"/></svg></button></div>';
  music.insertBefore(ctl, grid);

  // extra glass chips: speed + sleep timer (they drive the original controls)
  var chips = document.createElement('div'); chips.className = 'ixChips';
  chips.innerHTML = '<button id="ixSpd"><svg viewBox="0 0 24 24"><path d="M4 18a8 8 0 1 1 16 0M12 18l4-6" fill="none"/></svg><span>x1</span></button><button id="ixSlp"><svg viewBox="0 0 24 24"><path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z" fill="none"/></svg><span>Sleep</span></button>';
  grid.insertBefore(chips, grid.firstChild);
  $('ixSpd').onclick = function () { var b = $('spd'); if (b) b.click(); };
  $('ixSlp').onclick = function () { var b = $('sleep'); if (b) b.click(); };
  $('ixPrev').onclick = function () { try { hap('click'); } catch (e) { } P('prev'); };
  $('ixNext').onclick = function () { try { hap('click'); } catch (e) { } P('next'); };
  $('ixPlay').onclick = function () { try { hap('heavy'); } catch (e) { } if (!tracks.length) { try { askScan(); } catch (e) { } return; } if (st.idx < 0) P('play', { i: 0 }); else P('toggle'); };

  var bar = $('ixBar'), drag = false, dragF = 0;
  function frac(e) { var r = bar.getBoundingClientRect(); return Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)); }
  bar.addEventListener('pointerdown', function (e) { drag = true; dragF = frac(e); bar.setPointerCapture && bar.setPointerCapture(e.pointerId); bar.classList.add('drag'); e.preventDefault(); });
  bar.addEventListener('pointermove', function (e) { if (drag) dragF = frac(e); });
  var up = function () { if (!drag) return; drag = false; bar.classList.remove('drag'); if (st.dur > 0) P('seek', { ms: Math.round(dragF * st.dur) }); };
  bar.addEventListener('pointerup', up); bar.addEventListener('pointercancel', up);

  var fmt = function (ms) { var s = Math.max(0, Math.round(ms / 1000)); return Math.floor(s / 60) + ':' + ('0' + (s % 60)).slice(-2); };
  var lastArt = null;
  function tick() {
    if (!music.classList.contains('on')) return;
    var t = tracks && tracks[st.idx], dur = st.dur || (t && t.dur) || 0, pos = st.pos || 0;
    if (st.playing && typeof stTs === 'number') pos += (performance.now() - stTs) * (st.speed || 1);
    pos = Math.min(pos, dur || pos);
    var f = drag ? dragF : (dur > 0 ? pos / dur : 0);
    bar.firstChild.style.width = (f * 100) + '%'; bar.lastChild.style.left = (f * 100) + '%';
    $('ixCur').textContent = fmt(drag ? dragF * dur : pos); $('ixDur').textContent = fmt(dur);
    document.body.classList.toggle('ixPlaying', !!st.playing);
    var u = t && t.art ? t.art : '';
    if (u !== lastArt) { lastArt = u; art.firstChild.style.backgroundImage = u ? 'url("' + u + '")' : ''; art.classList.toggle('none', !u); }
    var sp = $('spdTxt'); if (sp) $('ixSpd').lastChild.textContent = sp.textContent || 'x1';
    var sl = $('slTxt'); if (sl) $('ixSlp').lastChild.textContent = (sl.textContent || '').trim() || 'Sleep';
  }
  setInterval(tick, 200); tick();
})();
