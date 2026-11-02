// Skapar den signerade PDF:en med pdf-lib och sparar/delar den.
import { composeField } from './composer.js';
import { isNative, Filesystem, Share } from './native.js';

const PX_PER_PT = 4; // ≈ 288 dpi för signatur och foto

export async function buildSignedPdf(doc, items, settings) {
  const { PDFDocument } = window.PDFLib;
  let pdf;
  if (doc.type === 'pdf') {
    pdf = await PDFDocument.load(doc.bytes.slice(0), { ignoreEncryption: true });
  } else {
    pdf = await PDFDocument.create();
    for (const p of doc.pages) {
      const jpg = await pdf.embedJpg(await canvasBytes(p.canvas, 'image/jpeg', 0.92));
      const page = pdf.addPage([p.width, p.height]);
      page.drawImage(jpg, { x: 0, y: 0, width: p.width, height: p.height });
    }
  }
  const pdfPages = pdf.getPages();
  for (const item of items) {
    const p = doc.pages[item.page];
    const page = pdfPages[item.page];
    if (!p || !page) continue;
    // Fältets hörn i visningskoordinater (pt, origo uppe till vänster)
    const dx = item.x * p.width, dy = item.y * p.height, dw = item.w * p.width, dh = item.h * p.height;
    const rotation = p.vp ? ((p.vp.rotation % 360) + 360) % 360 : 0;
    const comp = await composeField(item, settings, Math.min(dw * PX_PER_PT, 2400), Math.min(dh * PX_PER_PT, 2400));
    if (!comp) continue;
    let x, y, w, h;
    if (p.vp) {
      // pdf.js räknar om till PDF-koordinater (hanterar roterade sidor och beskärning)
      const pts = [[dx, dy], [dx + dw, dy], [dx, dy + dh], [dx + dw, dy + dh]].map(([a, b]) => p.vp.convertToPdfPoint(a, b));
      const xs = pts.map(q => q[0]), ys = pts.map(q => q[1]);
      x = Math.min(...xs); y = Math.min(...ys); w = Math.max(...xs) - x; h = Math.max(...ys) - y;
    } else {
      x = dx; y = p.height - dy - dh; w = dw; h = dh;
    }
    const rotated = rotateCanvas(comp, -rotation);
    const png = await pdf.embedPng(await canvasBytes(rotated, 'image/png'));
    page.drawImage(png, { x, y, width: w, height: h });
  }
  pdf.setProducer('S.N.C Sign – Claes Sundman, S.N.C AB');
  pdf.setCreator('S.N.C Sign');
  pdf.setModificationDate(new Date());
  return pdf.save();
}

function rotateCanvas(src, deg) {
  const d = ((deg % 360) + 360) % 360;
  if (!d) return src;
  const c = document.createElement('canvas');
  const swap = d === 90 || d === 270;
  c.width = swap ? src.height : src.width; c.height = swap ? src.width : src.height;
  const ctx = c.getContext('2d');
  ctx.translate(c.width / 2, c.height / 2);
  ctx.rotate(d * Math.PI / 180);
  ctx.drawImage(src, -src.width / 2, -src.height / 2);
  return c;
}

function canvasBytes(canvas, type, q) {
  return new Promise((res, rej) => canvas.toBlob(b => b ? b.arrayBuffer().then(res, rej) : rej(new Error('toBlob')), type, q));
}

export function signedName(name) {
  const base = name.replace(/\.(pdf|docx)$/i, '');
  return `${base}-signerad.pdf`;
}

/** Sparar filen: i Android-appen via dela-menyn, i webbläsaren som nedladdning. */
export async function saveFile(bytes, filename) {
  if (isNative()) {
    const res = await Filesystem.writeFile({ path: filename, data: toBase64(bytes), directory: 'CACHE' });
    try { await Filesystem.writeFile({ path: `S.N.C Sign/${filename}`, data: toBase64(bytes), directory: 'DOCUMENTS', recursive: true }); } catch {}
    await Share.share({ title: filename, files: [res.uri], dialogTitle: filename });
    return 'shared';
  }
  const blob = new Blob([bytes], { type: 'application/pdf' });
  const file = new File([blob], filename, { type: 'application/pdf' });
  const mobile = matchMedia('(pointer: coarse)').matches;
  if (mobile && navigator.canShare?.({ files: [file] })) {
    try { await navigator.share({ files: [file], title: filename }); return 'shared'; }
    catch (e) { if (e.name === 'AbortError') return 'aborted'; }
  }
  const url = URL.createObjectURL(blob);
  const a = Object.assign(document.createElement('a'), { href: url, download: filename });
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30000);
  return 'downloaded';
}

function toBase64(bytes) {
  let s = '';
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) s += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
  return btoa(s);
}
