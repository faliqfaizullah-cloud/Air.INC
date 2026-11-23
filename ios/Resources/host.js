/* Air.INC iOS host: implements the AirHost contract on top of WKWebView message handlers. */
(function () {
  var pend = {}, seq = 0, lis = {};
  function post(o) { try { window.webkit.messageHandlers.air.postMessage(o); } catch (e) { } }
  window.__airReply = function (id, ok, v) { var p = pend[id]; if (!p) return; delete pend[id]; ok ? p.res(v) : p.rej(new Error(String(v))); };
  window.__airEmit = function (ch, args) { (lis[ch] || []).forEach(function (f) { try { f.apply(null, args || []); } catch (e) { } }); };
  function b64(u8) { var s = '', n = 0x8000; for (var i = 0; i < u8.length; i += n) s += String.fromCharCode.apply(null, u8.subarray(i, i + n)); return btoa(s); }
  window.AirHost = {
    platform: 'ios',
    ver: (document.documentElement.getAttribute('data-ver') || window.__airVer || ''),
    invoke: function (ch) {
      var a = Array.prototype.slice.call(arguments, 1);
      if (ch === 'mem:save' && a[0] && a[0].bytes) { var o = {}; for (var k in a[0]) o[k] = a[0][k]; o.bytes = b64(a[0].bytes); a = [o]; }
      return new Promise(function (res, rej) { var id = ++seq; pend[id] = { res: res, rej: rej }; post({ id: id, ch: ch, args: a }); });
    },
    sync: function () { return null; },
    on: function (ch, cb) { (lis[ch] = lis[ch] || []).push(cb); },
    mediaUrl: function (id) { return '/media/' + id; },
    memUrl: function (f) { return '/mem/' + f; },
    castUrl: function (t) { return '/cast/' + t; }
  };
})();
