// Läser in PDF (pdf.js) och Word .docx (docx-preview + html2canvas) till renderade sidor.
import * as pdfjsLib from '../vendor/pdf.min.mjs';
pdfjsLib.GlobalWorkerOptions.workerSrc = new URL('../vendor/pdf.worker.min.mjs', import.meta.url).href;

/**
 * @returns {Promise<{type:'pdf'|'docx', name:string, bytes:ArrayBuffer, pages:Array<{canvas:HTMLCanvasElement, width:number, height:number, vp?:any}>}>}
 * width/height = sidans storlek i "visningspunkter" (pt).
 */
export async function loadDocument(file, onProgress = () => {}) {
  const name = file.name || 'dokument';
  const lower = name.toLowerCase();
  const bytes = await file.arrayBuffer();
  const head = new Uint8Array(bytes.slice(0, 5));
  const isPdf = lower.endsWith('.pdf') || file.type === 'application/pdf' || String.fromCharCode(...head) === '%PDF-';
  const isZip = head[0] === 0x50 && head[1] === 0x4b;
  if (isPdf) return { type: 'pdf', name, bytes, pages: await renderPdf(bytes, onProgress) };
  if (lower.endsWith('.docx') || isZip) return { type: 'docx', name, bytes, pages: await renderDocx(bytes, onProgress) };
  const err = new Error('unsupported'); err.code = 'unsupported'; throw err;
}

function targetWidthPx() {
  const dpr = Math.min(window.devicePixelRatio || 1, 3);
  return Math.min(Math.round(Math.min(window.innerWidth, 900) * dpr * 1.25), 2200);
}

async function renderPdf(bytes, onProgress) {
  const pdf = await pdfjsLib.getDocument({ data: new Uint8Array(bytes.slice(0)) }).promise;
  const pages = [];
  for (let i = 1; i <= pdf.numPages; i++) {
    onProgress(i, pdf.numPages);
    const page = await pdf.getPage(i);
    const vp1 = page.getViewport({ scale: 1 });
    const scale = targetWidthPx() / vp1.width;
    const vp = page.getViewport({ scale });
    const canvas = document.createElement('canvas');
    canvas.width = Math.floor(vp.width); canvas.height = Math.floor(vp.height);
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, canvas.width, canvas.height);
    await page.render({ canvasContext: ctx, viewport: vp }).promise;
    pages.push({ canvas, width: vp1.width, height: vp1.height, vp: vp1 });
  }
  return pages;
}

async function renderDocx(bytes, onProgress) {
  const host = document.createElement('div');
  host.className = 'docx-host';
  document.body.appendChild(host);
  try {
    await window.docx.renderAsync(bytes, host, host, {
      className: 'docx', inWrapper: false, breakPages: true, ignoreWidth: false, ignoreHeight: false,
      ignoreLastRenderedPageBreak: false, renderHeaders: true, renderFooters: true, renderFootnotes: true,
      experimental: true, useBase64URL: true,
    });
    // Vänta på bilder och typsnitt
    await Promise.all([...host.querySelectorAll('img')].map(img => img.complete ? 0 : new Promise(r => { img.onload = img.onerror = r; })));
    if (document.fonts?.ready) await document.fonts.ready;
    const sections = [...host.querySelectorAll('section.docx')];
    if (!sections.length) throw new Error('Tomt dokument');
    const pages = [];
    for (let i = 0; i < sections.length; i++) {
      onProgress(i + 1, sections.length);
      const s = sections[i];
      const w = s.offsetWidth, h = s.offsetHeight;
      const scale = Math.min(targetWidthPx() / w, 3);
      const canvas = await window.html2canvas(s, { scale, backgroundColor: '#ffffff', useCORS: true, logging: false, windowWidth: Math.max(w + 40, 1024) });
      pages.push({ canvas, width: w * 0.75, height: h * 0.75 }); // px → pt
    }
    return pages;
  } finally { host.remove(); }
}
