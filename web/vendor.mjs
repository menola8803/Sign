// Kopierar tredjepartsbibliotek till www/vendor så appen fungerar offline och i Android-appen.
import { copyFileSync, mkdirSync } from 'node:fs';
const out = 'vendor';
mkdirSync(out, { recursive: true });
const files = [
  ['../node_modules/pdfjs-dist/build/pdf.min.mjs', 'pdf.min.mjs'],
  ['../node_modules/pdfjs-dist/build/pdf.worker.min.mjs', 'pdf.worker.min.mjs'],
  ['../node_modules/pdf-lib/dist/pdf-lib.min.js', 'pdf-lib.min.js'],
  ['../node_modules/jszip/dist/jszip.min.js', 'jszip.min.js'],
  ['../node_modules/docx-preview/dist/docx-preview.min.js', 'docx-preview.min.js'],
  ['../node_modules/html2canvas/dist/html2canvas.min.js', 'html2canvas.min.js'],
  ['../node_modules/@capacitor/core/dist/index.js', 'capacitor-core.mjs'],
];
for (const [src, dst] of files) { copyFileSync(src, `${out}/${dst}`); console.log('vendor:', dst); }
