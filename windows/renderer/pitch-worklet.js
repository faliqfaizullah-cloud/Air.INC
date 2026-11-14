// Granular pitch shifter (two crossfaded delay-line heads). Used for the karaoke "key" control so
// the key changes without changing the tempo. ratio = 2^(semitones/12).
class AirPitch extends AudioWorkletProcessor {
  static get parameterDescriptors() { return [{ name: 'ratio', defaultValue: 1, minValue: 0.25, maxValue: 4, automationRate: 'k-rate' }]; }
  constructor() {
    super();
    this.N = 16384; this.mask = this.N - 1;
    this.buf = [new Float32Array(this.N), new Float32Array(this.N)];
    this.w = 0; this.G = Math.round(sampleRate * 0.055); this.ph = 0;
  }
  process(inputs, outputs, params) {
    const I = inputs[0], O = outputs[0];
    if (!O || !O.length) return true;
    const n = O[0].length, r = params.ratio[0], N = this.N, m = this.mask, G = this.G, step = (1 - r) / G;
    for (let i = 0; i < n; i++) {
      for (let c = 0; c < 2; c++) this.buf[c][this.w] = (I && I[c] ? I[c][i] : (I && I[0] ? I[0][i] : 0)) || 0;
      let p0 = this.ph, p1 = p0 + 0.5; if (p1 >= 1) p1 -= 1;
      const d0 = 4 + G * p0, d1 = 4 + G * p1;
      const w0 = Math.sin(Math.PI * p0), w1 = Math.sin(Math.PI * p1), g0 = w0 * w0, g1 = w1 * w1;
      for (let c = 0; c < O.length; c++) {
        const b = this.buf[c < 2 ? c : 0];
        let a = this.w - d0, f = Math.floor(a), t = a - f; const x0 = b[f & m] * (1 - t) + b[(f + 1) & m] * t;
        a = this.w - d1; f = Math.floor(a); t = a - f; const x1 = b[f & m] * (1 - t) + b[(f + 1) & m] * t;
        O[c][i] = x0 * g0 + x1 * g1;
      }
      this.w = (this.w + 1) & m;
      this.ph += step; if (this.ph >= 1) this.ph -= 1; else if (this.ph < 0) this.ph += 1;
    }
    return true;
  }
}
registerProcessor('air-pitch', AirPitch);
