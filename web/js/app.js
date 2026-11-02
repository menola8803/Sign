// S.N.C Sign – huvudlogik. Utvecklare: Claes Sundman, S.N.C AB.
import { t, tr, setLang, getLang, applyI18n } from './i18n.js';
import * as store from './store.js';
import { SEAL_ID, sealDataURL } from './seal.js';
import { composeField, todayString, stampSrc } from './composer.js';
import { SignaturePad } from './signpad.js';
import { APP_VERSION, APK_URL } from './version.js';
import { fetchUpdates, cmpVersion } from './updates.js';
import { isNative, ScreenOrientation } from './native.js';

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];

// ---------- Tillstånd ----------
let doc = null;          // inläst dokument
let items = [];          // fält: {id, type:'sign'|'stamp', page, x,y,w,h (andelar), sig?, photo?, name?, date?, stampId?}
let placeMode = null;    // null | {type:'sign'} | {type:'stamp', stampId}
let current = 'home';
const history = [];
let swReg = null;
let suppressPop = false; // när vi själva anropar history.back()
function historyBack() { suppressPop = true; window.history.back(); }

// ---------- Uppstart ----------
setLang(store.get().lang);
$$('.app-version').forEach(e => e.textContent = APP_VERSION);
let started = false;
// Startas när hela modulen är inläst, så att alla variabler finns
document.addEventListener('DOMContentLoaded', () => queueMicrotask(init));
if (document.readyState !== 'loading') queueMicrotask(init);
function init() {
  if (started) return; started = true;
  applyI18n();
  bindHome(); bindEditor(); bindSign(); bindSettings(); bindDev();
  setTimeout(() => {
    $('#splash').classList.add('out');
    show('home', false);
    setTimeout(() => $('#splash').remove(), 700);
  }, 1700);
  registerSW();
  // Tyst kontroll av uppdateringar i bakgrunden
  setTimeout(() => fetchUpdates().then(r => { $('#home-update-dot').hidden = !r.hasUpdate; }).catch(() => {}), 2500);
  window.addEventListener('popstate', () => {
    if (suppressPop) { suppressPop = false; return; }
    if (current !== 'home') back(true);
  });
  if (isNative()) document.documentElement.classList.add('native');
}

// ---------- Navigering ----------
function show(name, push = true) {
  if (push && current !== name) { history.push(current); window.history.pushState({ v: name }, ''); }
  $$('.view').forEach(v => v.hidden = v.id !== `view-${name}`);
  current = name;
  if (name === 'settings') renderSettings();
  if (name === 'dev') renderDev();
}
function back(fromPop = false) {
  if (current === 'sign') { closeSign(false, fromPop); return; }
  const prev = history.pop() || 'home';
  if (!fromPop) historyBack();
  show(prev, false);
}
$$('[data-back]').forEach(b => b.addEventListener('click', () => back()));

function toast(msg, ms = 2800) {
  const el = $('#toast');
  el.textContent = msg; el.hidden = false;
  requestAnimationFrame(() => el.classList.add('in'));
  clearTimeout(toast._t);
  toast._t = setTimeout(() => { el.classList.remove('in'); setTimeout(() => el.hidden = true, 300); }, ms);
}

// ---------- Hem ----------
function bindHome() {
  $('#btn-open').addEventListener('click', () => $('#file-input').click());
  $$('[data-go]').forEach(b => b.addEventListener('click', () => show(b.dataset.go)));
  $('#file-input').addEventListener('change', async e => {
    const f = e.target.files[0]; e.target.value = '';
    if (f) openDocument(f);
  });
}

async function openDocument(file) {
  show('editor');
  items = []; doc = null; setPlaceMode(null);
  $('#pages').innerHTML = '';
  $('#doc-name').textContent = file.name;
  $('#doc-meta').textContent = '';
  $('#loading').hidden = false;
  $('#loading-text').textContent = t('loading');
  try {
    const { loadDocument } = await import('./documents.js');
    doc = await loadDocument(file, (i, n) => { $('#loading-text').textContent = `${t('loading')} ${i}/${n}`; });
    $('#doc-meta').textContent = `${doc.type.toUpperCase()} · ${doc.pages.length} ${t('pages')}` + (doc.type === 'docx' ? ` · ${t('docxNote')}` : '');
    renderPages();
    updateHint();
  } catch (e) {
    console.error(e);
    toast(e.code === 'unsupported' ? t('unsupported') : t('openFail'), 4000);
    back();
  } finally { $('#loading').hidden = true; }
}

// ---------- Editor ----------
function bindEditor() {
  $('#btn-add-sign').addEventListener('click', () => setPlaceMode(placeMode?.type === 'sign' ? null : { type: 'sign' }));
  $('#btn-add-stamp').addEventListener('click', openStampSheet);
  $('#btn-export').addEventListener('click', exportPdf);
  $('#stamp-sheet-close').addEventListener('click', () => $('#stamp-sheet').hidden = true);
  $('#stamp-sheet').addEventListener('click', e => { if (e.target.id === 'stamp-sheet') e.currentTarget.hidden = true; });
}

function setPlaceMode(m) {
  placeMode = m;
  $('#btn-add-sign').classList.toggle('active', m?.type === 'sign');
  $('#btn-add-stamp').classList.toggle('active', m?.type === 'stamp');
  $('#pages').classList.toggle('placing', !!m);
  updateHint();
}
function updateHint() {
  const h = $('#editor-hint');
  let msg = '';
  if (placeMode?.type === 'sign') msg = t('tapToPlace');
  else if (placeMode?.type === 'stamp') msg = t('tapToPlaceStamp');
  else if (doc && !items.length) msg = t('noFields');
  h.textContent = msg; h.hidden = !msg;
}

function renderPages() {
  const wrap = $('#pages');
  wrap.innerHTML = '';
  doc.pages.forEach((p, i) => {
    const el = document.createElement('div');
    el.className = 'page';
    el.dataset.page = i;
    el.style.aspectRatio = `${p.width} / ${p.height}`;
    p.canvas.className = 'page-canvas';
    el.appendChild(p.canvas);
    const num = document.createElement('span'); num.className = 'page-num'; num.textContent = `${i + 1} / ${doc.pages.length}`;
    el.appendChild(num);
    el.addEventListener('click', e => onPageTap(e, el, i));
    wrap.appendChild(el);
  });
  items.forEach(renderItem);
}

function onPageTap(e, el, page) {
  if (!placeMode || e.target.closest('.field')) return;
  const r = el.getBoundingClientRect();
  const fx = (e.clientX - r.left) / r.width, fy = (e.clientY - r.top) / r.height;
  const pageAR = r.width / r.height;
  let w, h;
  if (placeMode.type === 'sign') {
    const hasPhoto = store.get().includePhoto && store.get().photo;
    w = Math.min(0.5, hasPhoto ? 0.48 : 0.4);
    h = (w * pageAR) / (hasPhoto ? 2.6 : 3);
  } else { w = 0.22; h = w * pageAR; }
  const item = {
    id: Math.random().toString(36).slice(2, 9), type: placeMode.type, page,
    x: clamp(fx - w / 2, 0, 1 - w), y: clamp(fy - h / 2, 0, 1 - h), w, h,
    stampId: placeMode.stampId,
  };
  items.push(item);
  renderItem(item);
  setPlaceMode(null);
  if (item.type === 'sign') openSign(item);
}

function renderItem(item) {
  const pageEl = $(`.page[data-page="${item.page}"]`);
  if (!pageEl) return;
  let el = $(`.field[data-id="${item.id}"]`);
  if (!el) {
    el = document.createElement('div');
    el.className = `field field-${item.type}`;
    el.dataset.id = item.id;
    el.innerHTML = `<canvas></canvas><span class="field-label"></span>
      <button class="field-del" aria-label="${t('remove')}">×</button>
      <span class="field-resize"></span>`;
    pageEl.appendChild(el);
    bindFieldGestures(el, item);
  }
  el.style.left = item.x * 100 + '%'; el.style.top = item.y * 100 + '%';
  el.style.width = item.w * 100 + '%'; el.style.height = item.h * 100 + '%';
  const signed = item.type === 'stamp' || !!item.sig;
  el.classList.toggle('signed', signed);
  $('.field-label', el).textContent = signed ? '' : t('tapToSign');
  drawItemPreview(item, el);
}

async function drawItemPreview(item, el) {
  const cv = $('canvas', el);
  const r = el.getBoundingClientRect();
  const dpr = Math.min(window.devicePixelRatio || 1, 3);
  const comp = await composeField(item, store.get(), r.width * dpr, r.height * dpr);
  cv.width = Math.max(1, Math.round(r.width * dpr)); cv.height = Math.max(1, Math.round(r.height * dpr));
  const ctx = cv.getContext('2d');
  ctx.clearRect(0, 0, cv.width, cv.height);
  if (comp) ctx.drawImage(comp, 0, 0, cv.width, cv.height);
}

function bindFieldGestures(el, item) {
  $('.field-del', el).addEventListener('click', e => {
    e.stopPropagation();
    items = items.filter(i => i !== item); el.remove(); updateHint();
  });
  const resize = $('.field-resize', el);
  let start = null;
  const onDown = (e, mode) => {
    e.stopPropagation(); e.preventDefault();
    const pr = el.parentElement.getBoundingClientRect();
    start = { mode, x: e.clientX, y: e.clientY, item: { ...item }, pw: pr.width, ph: pr.height, moved: false };
    el.setPointerCapture(e.pointerId);
    el.classList.add('active');
  };
  el.addEventListener('pointerdown', e => { if (!e.target.closest('.field-del')) onDown(e, e.target === resize ? 'resize' : 'move'); });
  el.addEventListener('pointermove', e => {
    if (!start) return;
    const dx = (e.clientX - start.x) / start.pw, dy = (e.clientY - start.y) / start.ph;
    if (!start.moved && Math.hypot(e.clientX - start.x, e.clientY - start.y) < 6) return;
    start.moved = true;
    if (start.mode === 'move') {
      item.x = clamp(start.item.x + dx, 0, 1 - item.w);
      item.y = clamp(start.item.y + dy, 0, 1 - item.h);
    } else {
      const keep = item.type === 'stamp';
      item.w = clamp(start.item.w + dx, 0.06, 1 - item.x);
      item.h = keep ? item.w * (start.item.h / start.item.w) : clamp(start.item.h + dy, 0.025, 1 - item.y);
      if (item.y + item.h > 1) { item.h = 1 - item.y; if (keep) item.w = item.h * (start.item.w / start.item.h); }
    }
    el.style.left = item.x * 100 + '%'; el.style.top = item.y * 100 + '%';
    el.style.width = item.w * 100 + '%'; el.style.height = item.h * 100 + '%';
  });
  const end = () => {
    if (!start) return;
    const s = start; start = null;
    el.classList.remove('active');
    if (s.moved) { if (s.mode === 'resize') drawItemPreview(item, el); }
    else if (item.type === 'sign') openSign(item);
  };
  el.addEventListener('pointerup', end);
  el.addEventListener('pointercancel', end);
}

// ---------- Stämplar ----------
function allStamps() {
  return [{ id: SEAL_ID, name: 'Scandinavian Nexum Consulting AB', data: sealDataURL(), builtin: true }, ...store.get().stamps];
}
function openStampSheet() {
  const grid = $('#stamp-pick');
  grid.innerHTML = '';
  for (const s of allStamps()) {
    const b = document.createElement('button');
    b.className = 'stamp-item';
    b.innerHTML = `<img alt=""><span></span>`;
    $('img', b).src = s.data; $('span', b).textContent = s.builtin ? t('builtin') : s.name;
    b.addEventListener('click', () => { $('#stamp-sheet').hidden = true; setPlaceMode({ type: 'stamp', stampId: s.id }); });
    grid.appendChild(b);
  }
  $('#stamp-sheet').hidden = false;
}

// ---------- Export ----------
async function exportPdf() {
  if (!doc) return;
  if (items.some(i => i.type === 'sign' && !i.sig) && !confirm(t('unsigned'))) return;
  $('#loading').hidden = false; $('#loading-text').textContent = t('exporting');
  try {
    const { buildSignedPdf, saveFile, signedName } = await import('./exporter.js');
    const bytes = await buildSignedPdf(doc, items.filter(i => i.type === 'stamp' || i.sig), store.get());
    const res = await saveFile(bytes, signedName(doc.name));
    if (res !== 'aborted') toast(t('exported'));
  } catch (e) { console.error(e); toast(t('exportFail'), 4000); }
  finally { $('#loading').hidden = true; }
}

// ---------- Signering ----------
let pad = null, signingItem = null;
function bindSign() {
  pad = new SignaturePad($('#sign-pad'));
  $('#sign-cancel').addEventListener('click', () => closeSign());
  $('#sign-undo').addEventListener('click', () => pad.undo());
  $('#sign-clear').addEventListener('click', () => pad.clear());
  $('#sign-done').addEventListener('click', finishSign);
  $('#rotate-skip').addEventListener('click', () => { $('#rotate-hint').hidden = true; $('#view-sign').dataset.skip = '1'; });
  $$('.ink').forEach(b => b.addEventListener('click', () => {
    $$('.ink').forEach(x => x.classList.toggle('active', x === b));
    pad.color = b.dataset.ink; pad.redraw();
  }));
  const mq = matchMedia('(orientation: portrait)');
  const check = () => {
    const v = $('#view-sign');
    $('#rotate-hint').hidden = v.hidden || !mq.matches || v.dataset.skip === '1' || !matchMedia('(pointer: coarse)').matches;
  };
  mq.addEventListener?.('change', check);
  window.addEventListener('resize', check);
  bindSign.check = check;
}

async function openSign(item) {
  signingItem = item;
  const s = store.get();
  $('#sign-where').textContent = `· ${t('field')} ${items.filter(i => i.type === 'sign').indexOf(item) + 1}, ${t('page')} ${item.page + 1}`;
  const ph = $('#sign-photo');
  ph.hidden = !(s.includePhoto && s.photo);
  if (!ph.hidden) $('img', ph).src = s.photo;
  pad.strokes = [];
  delete $('#view-sign').dataset.skip;
  history.push(current); window.history.pushState({ v: 'sign' }, '');
  $$('.view').forEach(v => v.hidden = v.id !== 'view-sign');
  current = 'sign';
  await lockLandscape();
  requestAnimationFrame(() => { pad.resize(); bindSign.check(); });
}

async function lockLandscape() {
  try {
    if (isNative()) { await ScreenOrientation.lock({ orientation: 'landscape' }); return; }
    if (matchMedia('(pointer: coarse)').matches && document.documentElement.requestFullscreen && !document.fullscreenElement) {
      await document.documentElement.requestFullscreen({ navigationUI: 'hide' });
    }
    await screen.orientation?.lock?.('landscape');
  } catch { /* iOS m.fl. stöder inte låsning – då visas en uppmaning att vända telefonen */ }
}
async function unlockOrientation() {
  try {
    if (isNative()) { await ScreenOrientation.unlock(); return; }
    screen.orientation?.unlock?.();
    if (document.fullscreenElement) await document.exitFullscreen();
  } catch {}
}

function closeSign(fromDone = false, fromPop = false) {
  unlockOrientation();
  const it = signingItem; signingItem = null;
  const prev = history.pop() || 'editor';
  $$('.view').forEach(v => v.hidden = v.id !== `view-${prev}`);
  current = prev;
  $('#rotate-hint').hidden = true;
  if (!fromPop) historyBack();
  // Rita om fälten när layouten är tillbaka i stående läge
  setTimeout(() => items.forEach(renderItem), 350);
}

function finishSign() {
  if (pad.isEmpty()) { toast(t('emptySig')); return; }
  const s = store.get();
  const it = signingItem;
  it.sig = pad.toTrimmedPNG();
  it.photo = s.includePhoto ? s.photo : null;
  it.name = s.showName && s.name.trim() ? s.name.trim() : '';
  it.date = s.showDate ? todayString() : '';
  // Gör fältet bredare om ett foto lagts till så att allt får plats snyggt
  if (it.photo) {
    const pageEl = $(`.page[data-page="${it.page}"]`).getBoundingClientRect();
    const needW = Math.min(1 - it.x, (it.h * pageEl.height * 2.6) / pageEl.width);
    if (it.w < needW) it.w = needW;
  }
  closeSign(true);
  updateHint();
}

// ---------- Inställningar ----------
function bindSettings() {
  $$('#lang-seg button').forEach(b => b.addEventListener('click', () => {
    store.set({ lang: b.dataset.lang }); setLang(b.dataset.lang); renderSettings(); if (doc) renderPages(); updateHint();
  }));
  $('#set-name').addEventListener('input', e => { store.set({ name: e.target.value }); drawSettingsPreview(); });
  const sw = (id, key) => $(id).addEventListener('change', e => { store.set({ [key]: e.target.checked }); drawSettingsPreview(); });
  sw('#set-showname', 'showName'); sw('#set-showdate', 'showDate'); sw('#set-includephoto', 'includePhoto');
  $('#btn-photo').addEventListener('click', openCamera);
  $('#btn-photo-remove').addEventListener('click', () => { store.set({ photo: null }); renderSettings(); });
  $('#camera-cancel').addEventListener('click', closeCamera);
  $('#camera-shoot').addEventListener('click', capturePhoto);
  $('#btn-stamp-add').addEventListener('click', () => $('#stamp-input').click());
  $('#stamp-input').addEventListener('change', async e => {
    const f = e.target.files[0]; e.target.value = '';
    if (!f) return;
    const data = await downscaleImage(f, 900);
    try {
      store.set({ stamps: [...store.get().stamps, { id: 'st-' + Date.now().toString(36), name: f.name.replace(/\.[^.]+$/, ''), data }] });
    } catch { toast('Storage full'); }
    renderSettings();
  });
}

function renderSettings() {
  const s = store.get();
  $$('#lang-seg button').forEach(b => b.classList.toggle('active', b.dataset.lang === getLang()));
  $('#set-name').value = s.name;
  $('#set-showname').checked = s.showName; $('#set-showdate').checked = s.showDate; $('#set-includephoto').checked = s.includePhoto;
  const box = $('#photo-box');
  box.innerHTML = s.photo ? `<img alt="">` : `<span>${t('noPhoto')}</span>`;
  if (s.photo) $('img', box).src = s.photo;
  $('#btn-photo span').textContent = s.photo ? t('retake') : t('takePhoto');
  $('#btn-photo-remove').hidden = !s.photo;
  const list = $('#stamp-list'); list.innerHTML = '';
  for (const st of allStamps()) {
    const d = document.createElement('div'); d.className = 'stamp-item';
    d.innerHTML = `<img alt=""><span></span>${st.builtin ? `<em>${t('builtin')}</em>` : `<button class="stamp-del">${t('remove')}</button>`}`;
    $('img', d).src = st.data; $('span', d).textContent = st.name;
    $('.stamp-del', d)?.addEventListener('click', () => { store.set({ stamps: store.get().stamps.filter(x => x.id !== st.id) }); renderSettings(); });
    list.appendChild(d);
  }
  drawSettingsPreview();
}

// Förhandsvisning av hur signaturfältet blir
const DEMO_SIG = (() => {
  const c = document.createElement('canvas'); c.width = 600; c.height = 200;
  const x = c.getContext('2d'); x.strokeStyle = '#0f2a6b'; x.lineWidth = 7; x.lineCap = x.lineJoin = 'round';
  x.beginPath(); x.moveTo(20, 150); x.bezierCurveTo(80, 20, 140, 10, 150, 110); x.bezierCurveTo(160, 190, 200, 170, 240, 90);
  x.bezierCurveTo(280, 20, 300, 60, 320, 120); x.bezierCurveTo(340, 170, 380, 150, 420, 80); x.bezierCurveTo(450, 40, 520, 60, 580, 100); x.stroke();
  return c.toDataURL();
})();
async function drawSettingsPreview() {
  const s = store.get();
  const cv = $('#sig-preview');
  const r = cv.getBoundingClientRect(); if (!r.width) return;
  const dpr = window.devicePixelRatio || 1;
  const item = { type: 'sign', sig: DEMO_SIG, photo: s.includePhoto ? s.photo : null, name: s.showName ? (s.name || t('namePh').replace(/^.*?\s/, '')) : '', date: s.showDate ? todayString() : '' };
  const comp = await composeField(item, s, r.width * dpr, r.height * dpr);
  cv.width = r.width * dpr; cv.height = r.height * dpr;
  const ctx = cv.getContext('2d'); ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, cv.width, cv.height);
  if (comp) ctx.drawImage(comp, 0, 0);
}

// ---------- Kamera ----------
let stream = null;
async function openCamera() {
  $('#camera').hidden = false;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'user', width: { ideal: 1280 }, height: { ideal: 1280 } }, audio: false });
    $('#camera-video').srcObject = stream;
  } catch (e) { console.error(e); closeCamera(); toast(t('cameraError'), 4500); }
}
function closeCamera() {
  stream?.getTracks().forEach(tr => tr.stop()); stream = null;
  $('#camera-video').srcObject = null;
  $('#camera').hidden = true;
}
function capturePhoto() {
  const v = $('#camera-video');
  if (!v.videoWidth) return;
  // Beskär till porträtt 3:4 och spara som JPEG
  const ar = 3 / 4;
  let sw = v.videoWidth, sh = v.videoHeight;
  if (sw / sh > ar) sw = sh * ar; else sh = sw / ar;
  const sx = (v.videoWidth - sw) / 2, sy = (v.videoHeight - sh) / 2;
  const c = document.createElement('canvas'); c.width = 480; c.height = 640;
  const ctx = c.getContext('2d');
  ctx.translate(c.width, 0); ctx.scale(-1, 1); // spegla som i förhandsvisningen
  ctx.drawImage(v, sx, sy, sw, sh, 0, 0, c.width, c.height);
  try { store.set({ photo: c.toDataURL('image/jpeg', 0.85), includePhoto: true }); } catch { toast('Storage full'); }
  closeCamera();
  renderSettings();
}

function downscaleImage(file, max) {
  return new Promise((res, rej) => {
    const fr = new FileReader();
    fr.onload = () => {
      if (file.type === 'image/svg+xml') return res(fr.result);
      const im = new Image();
      im.onload = () => {
        const s = Math.min(1, max / Math.max(im.width, im.height));
        const c = document.createElement('canvas'); c.width = Math.round(im.width * s); c.height = Math.round(im.height * s);
        c.getContext('2d').drawImage(im, 0, 0, c.width, c.height);
        res(c.toDataURL('image/png'));
      };
      im.onerror = rej; im.src = fr.result;
    };
    fr.onerror = rej; fr.readAsDataURL(file);
  });
}

// ---------- Utveckling & uppdateringar ----------
function bindDev() {
  $('#btn-check').addEventListener('click', () => renderDev(true));
  $('#banner-update').addEventListener('click', applyWebUpdate);
}
let devLoaded = false;
async function renderDev(force = false) {
  $('#dev-platform').textContent = isNative() ? t('android') : t('web');
  if (devLoaded && !force) return;
  const status = $('#dev-status');
  status.className = 'status'; status.textContent = t('checking');
  $('#btn-update').hidden = true;
  if (force && swReg) swReg.update().catch(() => {});
  try {
    const r = await fetchUpdates();
    devLoaded = true;
    $('#dev-latest').textContent = 'v' + r.latest;
    $('#home-update-dot').hidden = !r.hasUpdate;
    status.className = 'status ' + (r.hasUpdate ? 'warn' : 'ok');
    status.textContent = r.hasUpdate ? t('updateAvailable') : t('upToDate');
    if (r.hasUpdate) {
      const b = $('#btn-update'); b.hidden = false;
      const rel = r.data.releases[0];
      if (isNative()) { b.textContent = t('downloadApk'); b.onclick = () => window.open(rel.apk || APK_URL, '_system'); }
      else { b.textContent = t('updateNow'); b.onclick = applyWebUpdate; }
    }
    renderReleases(r.data.releases);
  } catch (e) {
    console.error(e);
    status.className = 'status err'; status.textContent = t('fetchFail');
  }
}

function renderReleases(releases) {
  const typeLabel = { new: t('new'), improved: t('improved'), fixed: t('fixed') };
  const card = (rel, open) => {
    const isInstalled = cmpVersion(rel.version, APP_VERSION) === 0;
    const isNewer = cmpVersion(rel.version, APP_VERSION) > 0;
    const el = document.createElement('details');
    el.className = 'card release'; el.open = open;
    el.innerHTML = `
      <summary>
        <span class="rel-ver">v${esc(rel.version)}</span>
        <span class="rel-title">${esc(tr(rel.title))}</span>
        ${isInstalled ? `<span class="badge">${t('current')}</span>` : ''}${isNewer ? `<span class="badge badge-gold">${t('new')}</span>` : ''}
        <span class="rel-date">${t('released')} ${esc(rel.date || '')}</span>
      </summary>
      <h4>${t('whatItMeans')}</h4>
      <p>${esc(tr(rel.summary))}</p>
      <h4>${t('changes')}</h4>
      <ul class="changes">${(rel.changes || []).map(c => `<li><span class="chip chip-${esc(c.type)}">${esc(typeLabel[c.type] || c.type)}</span>${esc(tr(c.text))}</li>`).join('')}</ul>`;
    return el;
  };
  const cur = $('#release-current'), prev = $('#release-prev');
  cur.innerHTML = ''; prev.innerHTML = '';
  if (!releases.length) return;
  cur.appendChild(card(releases[0], true));
  const rest = releases.slice(1);
  if (!rest.length) prev.innerHTML = `<p class="muted pad">${t('noPrevious')}</p>`;
  rest.forEach(r => prev.appendChild(card(r, false)));
}

// Service worker (bara webbappen)
function registerSW() {
  if (isNative() || !('serviceWorker' in navigator) || location.protocol === 'file:') return;
  navigator.serviceWorker.register('sw.js').then(reg => {
    swReg = reg;
    const notify = w => { if (w && navigator.serviceWorker.controller) $('#update-banner').hidden = false; };
    if (reg.waiting) notify(reg.waiting);
    reg.addEventListener('updatefound', () => {
      const w = reg.installing;
      w?.addEventListener('statechange', () => { if (w.state === 'installed') notify(w); });
    });
  }).catch(e => console.warn('SW', e));
  let reloading = false;
  navigator.serviceWorker.addEventListener('controllerchange', () => { if (!reloading) { reloading = true; location.reload(); } });
}
function applyWebUpdate() {
  if (swReg?.waiting) swReg.waiting.postMessage('SKIP_WAITING');
  else location.reload();
}

// ---------- Hjälp ----------
function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
function esc(s) { return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
