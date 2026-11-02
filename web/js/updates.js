// Hämtar uppdateringslistan och jämför med installerad version.
import { APP_VERSION, UPDATE_SOURCES } from './version.js';

import { isNative } from './native.js';
export { isNative };

export function cmpVersion(a, b) {
  const pa = String(a).split('.').map(n => parseInt(n, 10) || 0);
  const pb = String(b).split('.').map(n => parseInt(n, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] || 0) - (pb[i] || 0);
    if (d) return d > 0 ? 1 : -1;
  }
  return 0;
}

async function fetchJson(url) {
  const sep = url.includes('?') ? '&' : '?';
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 9000);
  try {
    const r = await fetch(`${url}${sep}t=${Date.now()}`, { cache: 'no-store', signal: ctrl.signal });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    const j = await r.json();
    if (!Array.isArray(j.releases)) throw new Error('Felaktigt format');
    return j;
  } finally { clearTimeout(timer); }
}

/** Returnerar {data, source, offline}. I appen prövas GitHub först; på webben den egna servern. */
export async function fetchUpdates() {
  const order = isNative()
    ? [...UPDATE_SOURCES.remote, UPDATE_SOURCES.local]
    : [UPDATE_SOURCES.local, ...UPDATE_SOURCES.remote];
  let lastErr;
  for (const url of order) {
    try {
      const data = await fetchJson(url);
      data.releases.sort((a, b) => cmpVersion(b.version, a.version));
      const latest = data.releases[0]?.version || APP_VERSION;
      return { data, latest, source: url, offline: url === UPDATE_SOURCES.local && isNative(), hasUpdate: cmpVersion(latest, APP_VERSION) > 0 };
    } catch (e) { lastErr = e; }
  }
  throw lastErr || new Error('Inga källor');
}
