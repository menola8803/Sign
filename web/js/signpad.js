// Signaturplatta med mjuka, tryckkänsliga penndrag. Punkter lagras normaliserat så att
// teckningen överlever att skärmen vänds.
export class SignaturePad {
  constructor(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.strokes = [];
    this.color = '#0f2a6b';
    this.onChange = () => {};
    this._cur = null;
    canvas.addEventListener('pointerdown', e => this._down(e));
    canvas.addEventListener('pointermove', e => this._move(e));
    ['pointerup', 'pointercancel', 'pointerleave'].forEach(n => canvas.addEventListener(n, e => this._up(e)));
    this._ro = new ResizeObserver(() => this.resize());
    this._ro.observe(canvas);
  }
  get baseWidth() { return Math.max(2, Math.min(this.w, this.h) * 0.012); }
  resize() {
    const r = this.canvas.getBoundingClientRect();
    if (!r.width || !r.height) return;
    const dpr = window.devicePixelRatio || 1;
    this.w = r.width; this.h = r.height;
    this.canvas.width = Math.round(r.width * dpr); this.canvas.height = Math.round(r.height * dpr);
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.redraw();
  }
  _pt(e) {
    const r = this.canvas.getBoundingClientRect();
    const p = e.pressure && e.pointerType === 'pen' ? e.pressure : 0.5;
    return { x: (e.clientX - r.left) / r.width, y: (e.clientY - r.top) / r.height, p, t: e.timeStamp };
  }
  _down(e) {
    e.preventDefault();
    this.canvas.setPointerCapture?.(e.pointerId);
    this._cur = [this._pt(e)];
    this.strokes.push(this._cur);
    this.redraw();
  }
  _move(e) {
    if (!this._cur) return;
    e.preventDefault();
    const evs = e.getCoalescedEvents ? e.getCoalescedEvents() : [e];
    for (const ev of evs) this._cur.push(this._pt(ev));
    this.redraw();
  }
  _up() { if (this._cur) { this._cur = null; this.onChange(); } }
  clear() { this.strokes = []; this.redraw(); this.onChange(); }
  undo() { this.strokes.pop(); this.redraw(); this.onChange(); }
  isEmpty() { return this.strokes.length === 0; }
  redraw() {
    const { ctx } = this;
    ctx.clearRect(0, 0, this.w, this.h);
    for (const s of this.strokes) drawStroke(ctx, s, this.w, this.h, this.baseWidth, this.color);
  }
  /** Returnerar en beskuren PNG (genomskinlig bakgrund) med bara signaturen. */
  toTrimmedPNG(scale = 3) {
    if (this.isEmpty()) return null;
    const lw = this.baseWidth;
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const s of this.strokes) for (const p of s) {
      const x = p.x * this.w, y = p.y * this.h;
      minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
    }
    const pad = lw * 2;
    minX -= pad; minY -= pad; maxX += pad; maxY += pad;
    const c = document.createElement('canvas');
    c.width = Math.max(1, Math.round((maxX - minX) * scale));
    c.height = Math.max(1, Math.round((maxY - minY) * scale));
    const ctx = c.getContext('2d');
    ctx.setTransform(scale, 0, 0, scale, -minX * scale, -minY * scale);
    for (const s of this.strokes) drawStroke(ctx, s, this.w, this.h, lw, this.color);
    return c.toDataURL('image/png');
  }
  destroy() { this._ro.disconnect(); }
}

function drawStroke(ctx, pts, w, h, base, color) {
  ctx.strokeStyle = color; ctx.fillStyle = color;
  ctx.lineCap = 'round'; ctx.lineJoin = 'round';
  if (pts.length === 1) {
    ctx.beginPath(); ctx.arc(pts[0].x * w, pts[0].y * h, base * 0.6, 0, Math.PI * 2); ctx.fill(); return;
  }
  // Variabel linjebredd: snabba drag blir tunnare, som med en riktig penna.
  let prevW = base;
  for (let i = 1; i < pts.length; i++) {
    const a = pts[i - 1], b = pts[i];
    const ax = a.x * w, ay = a.y * h, bx = b.x * w, by = b.y * h;
    const dist = Math.hypot(bx - ax, by - ay);
    const dt = Math.max(1, b.t - a.t);
    const v = dist / dt; // px/ms
    const target = base * (0.55 + 0.9 * b.p) * Math.max(0.45, Math.min(1.25, 1.3 - v * 0.35));
    const lw = prevW * 0.7 + target * 0.3;
    prevW = lw;
    ctx.lineWidth = lw;
    ctx.beginPath();
    if (i >= 2) {
      const z = pts[i - 2];
      const mx1 = (z.x * w + ax) / 2, my1 = (z.y * h + ay) / 2;
      const mx2 = (ax + bx) / 2, my2 = (ay + by) / 2;
      ctx.moveTo(mx1, my1); ctx.quadraticCurveTo(ax, ay, mx2, my2);
    } else { ctx.moveTo(ax, ay); ctx.lineTo((ax + bx) / 2, (ay + by) / 2); }
    ctx.stroke();
  }
  const l = pts[pts.length - 1], p = pts[pts.length - 2];
  ctx.beginPath(); ctx.moveTo((p.x * w + l.x * w) / 2, (p.y * h + l.y * h) / 2); ctx.lineTo(l.x * w, l.y * h); ctx.stroke();
}
