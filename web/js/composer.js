// Komponerar innehållet i ett fält: foto + signatur + namn/datum, skalat så att allt får plats.
import { loadImage, sealDataURL, SEAL_ID } from './seal.js';
import { getLang } from './i18n.js';

const cache = new Map();
async function img(src) {
  if (!src) return null;
  if (!cache.has(src)) cache.set(src, loadImage(src).catch(() => null));
  return cache.get(src);
}

export function stampSrc(stampId, settings) {
  if (stampId === SEAL_ID) return sealDataURL();
  return settings.stamps.find(s => s.id === stampId)?.data || null;
}

/** Ritar fältet på en ny canvas med storleken pxW × pxH. Returnerar null om inget finns att rita. */
export async function composeField(item, settings, pxW, pxH) {
  pxW = Math.max(1, Math.round(pxW)); pxH = Math.max(1, Math.round(pxH));
  const c = document.createElement('canvas');
  c.width = pxW; c.height = pxH;
  const ctx = c.getContext('2d');

  if (item.type === 'stamp') {
    const im = await img(stampSrc(item.stampId, settings));
    if (!im) return null;
    contain(ctx, im, 0, 0, pxW, pxH);
    return c;
  }
  if (!item.sig) return null;
  const sig = await img(item.sig);
  const photo = item.photo ? await img(item.photo) : null;

  // Textrader under signaturen
  const lines = [];
  if (item.name) lines.push({ text: item.name, bold: true });
  if (item.date) lines.push({ text: item.date, bold: false });
  const fontPx = Math.max(6, Math.min(pxH * 0.13, pxW * 0.06));
  const lineH = fontPx * 1.25;
  const textBlock = lines.length ? lines.length * lineH + fontPx * 0.35 : 0;

  // Foto till vänster: full höjd, högst 38 % av bredden
  const gap = pxW * 0.035;
  let sx = 0;
  if (photo) {
    const ar = photo.width / photo.height;
    let ph = pxH, pw = ph * ar;
    if (pw > pxW * 0.38) { pw = pxW * 0.38; ph = pw / ar; }
    const py = (pxH - ph) / 2;
    const r = Math.min(pw, ph) * 0.08;
    ctx.save();
    roundRect(ctx, 0, py, pw, ph, r); ctx.clip();
    ctx.drawImage(photo, 0, py, pw, ph);
    ctx.restore();
    ctx.strokeStyle = 'rgba(15,42,107,.35)'; ctx.lineWidth = Math.max(1, pxW * 0.003);
    roundRect(ctx, 0, py, pw, ph, r); ctx.stroke();
    sx = pw + gap;
  }
  const areaW = pxW - sx, areaH = pxH - textBlock;
  if (sig && areaH > 4) contain(ctx, sig, sx, 0, areaW, areaH, 'bottom');

  if (lines.length) {
    let y = areaH + fontPx * 0.15;
    ctx.strokeStyle = 'rgba(15,42,107,.45)'; ctx.lineWidth = Math.max(1, fontPx * 0.06);
    ctx.beginPath(); ctx.moveTo(sx, y); ctx.lineTo(pxW, y); ctx.stroke();
    ctx.fillStyle = '#1b2440'; ctx.textBaseline = 'top';
    y += fontPx * 0.2;
    for (const l of lines) {
      ctx.font = `${l.bold ? '600 ' : ''}${fontPx}px -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif`;
      ctx.fillText(fitText(ctx, l.text, areaW), sx, y);
      y += lineH;
    }
  }
  return c;
}

export function todayString() {
  return new Date().toLocaleDateString(getLang() === 'sv' ? 'sv-SE' : 'en-GB', { year: 'numeric', month: 'long', day: 'numeric' });
}

function contain(ctx, im, x, y, w, h, valign = 'middle') {
  const s = Math.min(w / im.width, h / im.height);
  const dw = im.width * s, dh = im.height * s;
  const dy = valign === 'bottom' ? y + h - dh : y + (h - dh) / 2;
  ctx.drawImage(im, x + (w - dw) / 2, dy, dw, dh);
}
function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath(); ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
}
function fitText(ctx, text, maxW) {
  if (ctx.measureText(text).width <= maxW) return text;
  let s = text;
  while (s.length > 1 && ctx.measureText(s + '…').width > maxW) s = s.slice(0, -1);
  return s + '…';
}
