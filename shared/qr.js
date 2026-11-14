/* Minimal QR Code encoder (byte mode, versions 1-10, ECC L/M). Returns array of rows of 0/1. Based on the standard algorithm. */
function qrEncode(text, ecl) {
  'use strict';
  var L = ecl === 'L';
  var ECC = L ? [7, 10, 15, 20, 26, 18, 20, 24, 30, 18] : [10, 16, 26, 18, 24, 16, 18, 22, 22, 26];
  var BLK = L ? [1, 1, 1, 1, 1, 2, 2, 2, 2, 4] : [1, 1, 1, 2, 2, 4, 4, 4, 5, 5];
  var bytes = []; var enc = unescape(encodeURIComponent(text)); for (var i = 0; i < enc.length; i++) bytes.push(enc.charCodeAt(i));
  var raw = function (v) { var r = (16 * v + 128) * v + 64; if (v >= 2) { var na = Math.floor(v / 7) + 2; r -= (25 * na - 10) * na - 55; if (v >= 7) r -= 36; } return r; };
  var ver, dataCw;
  for (ver = 1; ver <= 10; ver++) {
    dataCw = Math.floor(raw(ver) / 8) - ECC[ver - 1] * BLK[ver - 1];
    if ((4 + (ver >= 10 ? 16 : 8) + bytes.length * 8) <= dataCw * 8) break;
  }
  if (ver > 10) throw new Error('text too long');
  var bits = []; var put = function (v, n) { for (var i = n - 1; i >= 0; i--) bits.push((v >>> i) & 1); };
  put(4, 4); put(bytes.length, ver >= 10 ? 16 : 8); bytes.forEach(function (b) { put(b, 8); });
  put(0, Math.min(4, dataCw * 8 - bits.length)); while (bits.length % 8) bits.push(0);
  for (var pad = 0xEC; bits.length < dataCw * 8; pad ^= 0xEC ^ 0x11) put(pad, 8);
  var data = []; for (i = 0; i < bits.length; i += 8) { var b = 0; for (var j = 0; j < 8; j++) b = (b << 1) | bits[i + j]; data.push(b); }
  // Reed-Solomon
  var exp = [], log = []; var x = 1; for (i = 0; i < 255; i++) { exp[i] = x; log[x] = i; x <<= 1; if (x & 256) x ^= 0x11D; }
  var mul = function (a, b) { return a && b ? exp[(log[a] + log[b]) % 255] : 0; };
  var eccLen = ECC[ver - 1], gen = [1];
  for (i = 0; i < eccLen; i++) { var ng = new Array(gen.length + 1).fill(0); for (j = 0; j < gen.length; j++) { ng[j] ^= gen[j]; ng[j + 1] ^= mul(gen[j], exp[i]); } gen = ng; }
  var rs = function (d) { var r = new Array(eccLen).fill(0); d.forEach(function (c) { var f = c ^ r.shift(); r.push(0); for (var k = 0; k < eccLen; k++) r[k] ^= mul(gen[k + 1], f); }); return r; };
  var nb = BLK[ver - 1], total = Math.floor(raw(ver) / 8), shortLen = Math.floor(total / nb) - eccLen, nLong = total % nb, blocks = [], k = 0;
  for (i = 0; i < nb; i++) { var len = shortLen + (i < nb - nLong ? 0 : 1), d = data.slice(k, k + len); k += len; blocks.push({ d: d, e: rs(d) }); }
  var cw = []; for (i = 0; i <= shortLen; i++) blocks.forEach(function (bl) { if (i < bl.d.length) cw.push(bl.d[i]); });
  for (i = 0; i < eccLen; i++) blocks.forEach(function (bl) { cw.push(bl.e[i]); });
  // matrix
  var size = ver * 4 + 17, m = [], fn = []; for (i = 0; i < size; i++) { m.push(new Array(size).fill(0)); fn.push(new Array(size).fill(false)); }
  var set = function (r, c, v) { m[r][c] = v ? 1 : 0; fn[r][c] = true; };
  for (i = 0; i < size; i++) { set(6, i, i % 2 === 0); set(i, 6, i % 2 === 0); }
  var finder = function (cy, cx) { for (var dy = -4; dy <= 4; dy++) for (var dx = -4; dx <= 4; dx++) { var dist = Math.max(Math.abs(dx), Math.abs(dy)), yy = cy + dy, xx = cx + dx; if (yy >= 0 && yy < size && xx >= 0 && xx < size) set(yy, xx, dist !== 2 && dist !== 4); } };
  finder(3, 3); finder(3, size - 4); finder(size - 4, 3);
  var al = []; if (ver > 1) { var na2 = Math.floor(ver / 7) + 2, step = Math.ceil((ver * 4 + 4) / (na2 * 2 - 2)) * 2; al = [6]; for (i = 0, k = size - 7; i < na2 - 1; i++, k -= step) al.splice(1, 0, k); }
  al.forEach(function (ay, ia) { al.forEach(function (ax, ib) { if ((ia === 0 && ib === 0) || (ia === 0 && ib === al.length - 1) || (ia === al.length - 1 && ib === 0)) return; for (var dy = -2; dy <= 2; dy++) for (var dx = -2; dx <= 2; dx++) set(ay + dy, ax + dx, Math.max(Math.abs(dx), Math.abs(dy)) !== 1); }); });
  var fmt = function (mask) {
    var d = ((L ? 1 : 0) << 3) | mask, r = d; for (var q = 0; q < 10; q++) r = (r << 1) ^ ((r >>> 9) * 0x537);
    var bitsF = ((d << 10) | r) ^ 0x5412, g = function (q) { return ((bitsF >>> q) & 1) !== 0; };
    for (var q2 = 0; q2 <= 5; q2++) set(q2, 8, g(q2)); set(7, 8, g(6)); set(8, 8, g(7)); set(8, 7, g(8)); for (q2 = 9; q2 < 15; q2++) set(8, 14 - q2, g(q2));
    for (q2 = 0; q2 < 8; q2++) set(8, size - 1 - q2, g(q2)); for (q2 = 8; q2 < 15; q2++) set(size - 15 + q2, 8, g(q2)); set(size - 8, 8, true);
  };
  fmt(0);
  if (ver >= 7) { var rr = ver; for (i = 0; i < 12; i++) rr = (rr << 1) ^ ((rr >>> 11) * 0x1F25); var vb = (ver << 12) | rr; for (i = 0; i < 18; i++) { var bt = ((vb >>> i) & 1) !== 0, a = size - 11 + i % 3, bb = Math.floor(i / 3); set(bb, a, bt); set(a, bb, bt); } }
  // codewords, zig-zag
  var allBits = []; cw.forEach(function (c) { for (var q = 7; q >= 0; q--) allBits.push((c >>> q) & 1); });
  var bi = 0; for (var right = size - 1; right >= 1; right -= 2) { if (right === 6) right = 5; for (var vert = 0; vert < size; vert++) for (j = 0; j < 2; j++) { var xx = right - j, up = ((right + 1) & 2) === 0, yy = up ? size - 1 - vert : vert; if (!fn[yy][xx] && bi < allBits.length) m[yy][xx] = allBits[bi++]; } }
  var maskFn = [function (x, y) { return (x + y) % 2 === 0; }, function (x, y) { return y % 2 === 0; }, function (x, y) { return x % 3 === 0; }, function (x, y) { return (x + y) % 3 === 0; }, function (x, y) { return (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0; }, function (x, y) { return x * y % 2 + x * y % 3 === 0; }, function (x, y) { return (x * y % 2 + x * y % 3) % 2 === 0; }, function (x, y) { return ((x + y) % 2 + x * y % 3) % 2 === 0; }];
  var applyMask = function (mask) { for (var y = 0; y < size; y++) for (var x2 = 0; x2 < size; x2++) if (!fn[y][x2] && maskFn[mask](x2, y)) m[y][x2] ^= 1; };
  var penalty = function () {
    var p = 0, y, x2, run, dark = 0;
    var line = function (get) { var c = get(0), n = 1, s = 0; for (var t = 1; t < size; t++) { if (get(t) === c) { n++; if (n === 5) s += 3; else if (n > 5) s++; } else { c = get(t); n = 1; } } return s; };
    for (y = 0; y < size; y++) { p += line(function (t) { return m[y][t]; }); p += line(function (t) { return m[t][y]; }); }
    for (y = 0; y < size - 1; y++) for (x2 = 0; x2 < size - 1; x2++) { var c = m[y][x2]; if (c === m[y][x2 + 1] && c === m[y + 1][x2] && c === m[y + 1][x2 + 1]) p += 3; }
    var pat = function (get) { var s = 0; for (var t = 0; t + 11 <= size; t++) { var a = 0, bq = 0; for (var u = 0; u < 11; u++) { a = (a << 1) | get(t + u); bq = (bq << 1) | get(t + 10 - u); } if (a === 0x5D0 || a === 0x05D || bq === 0x5D0 || bq === 0x05D) s += 40; } return s; };
    for (y = 0; y < size; y++) { p += pat(function (t) { return m[y][t]; }); p += pat(function (t) { return m[t][y]; }); }
    for (y = 0; y < size; y++) for (x2 = 0; x2 < size; x2++) dark += m[y][x2];
    p += Math.floor(Math.abs(dark * 20 - size * size * 10) / (size * size)) * 10 - 0; return p;
  };
  var best = 0, bestP = 1e9; for (var mk = 0; mk < 8; mk++) { applyMask(mk); fmt(mk); var pp = penalty(); if (pp < bestP) { bestP = pp; best = mk; } applyMask(mk); }
  applyMask(best); fmt(best);
  return m;
}
if (typeof module !== 'undefined') module.exports = { qrEncode: qrEncode };
