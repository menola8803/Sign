// Inbyggt sigill för Scandinavian Nexum Consulting AB (ritas som SVG, konverteras till PNG vid behov).
export const SEAL_ID = 'snc-seal';

export function sealSVG(color = '#1d3a8a') {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 400" width="400" height="400">
  <defs>
    <path id="top" d="M 60 200 A 140 140 0 0 1 340 200"/>
    <path id="bot" d="M 52 200 A 148 148 0 0 0 348 200"/>
  </defs>
  <g fill="none" stroke="${color}">
    <circle cx="200" cy="200" r="190" stroke-width="10"/>
    <circle cx="200" cy="200" r="172" stroke-width="3"/>
    <circle cx="200" cy="200" r="112" stroke-width="3"/>
  </g>
  <g fill="${color}" font-family="Georgia, 'Times New Roman', serif" font-weight="700">
    <text font-size="27" letter-spacing="2.2" text-anchor="middle"><textPath href="#top" startOffset="50%">SCANDINAVIAN NEXUM</textPath></text>
    <text font-size="27" letter-spacing="2.2" text-anchor="middle" dominant-baseline="hanging"><textPath href="#bot" startOffset="50%">CONSULTING AB</textPath></text>
    <text x="200" y="210" font-size="62" text-anchor="middle" letter-spacing="3">S.N.C</text>
    <text x="200" y="258" font-size="30" text-anchor="middle" letter-spacing="8">AB</text>
  </g>
  <line x1="138" y1="225" x2="262" y2="225" stroke="${color}" stroke-width="3"/>
</svg>`;
}

export function sealDataURL() {
  return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(sealSVG());
}

/** Laddar en bild (dataURL/URL) som HTMLImageElement. */
export function loadImage(src) {
  return new Promise((res, rej) => {
    const img = new Image();
    img.onload = () => res(img);
    img.onerror = rej;
    img.src = src;
  });
}
